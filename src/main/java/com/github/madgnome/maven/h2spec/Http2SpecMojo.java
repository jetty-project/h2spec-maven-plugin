package com.github.madgnome.maven.h2spec;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.github.dockerjava.api.command.LogContainerCmd;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.Xpp3DomWriter;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.BaseConsumer;
import org.testcontainers.containers.output.FrameConsumerResultCallback;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.shaded.org.apache.commons.io.FileUtils;
import org.testcontainers.shaded.org.apache.commons.io.IOUtils;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;
import org.testcontainers.utility.ThrowingFunction;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.CharacterIterator;
import java.text.DecimalFormat;
import java.text.StringCharacterIterator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.github.madgnome.maven.h2spec.H2SpecTestSuite.DEFAULT_VERSION;
import static org.testcontainers.containers.output.OutputFrame.OutputType.STDERR;
import static org.testcontainers.containers.output.OutputFrame.OutputType.STDOUT;


@Mojo(name = "h2spec", defaultPhase = LifecyclePhase.INTEGRATION_TEST,
        requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class Http2SpecMojo extends AbstractMojo
{
    static
    {
        TestcontainersConfiguration.getInstance().getProperties().setProperty( "transport.type","httpclient5" );
    }

    /**
     * The port on which the Server will listen.
     */
    @Parameter(property="h2spec.port", defaultValue = "-1", required = true)
    private int port;

    /**
     * Timeout in seconds
     */
    @Parameter(property="h2spec.timeout", defaultValue = "5")
    private int timeout;

    /**
     * Maximum length of HTTP headers
     */
    @Parameter(property="h2spec.maxHeaderLength", defaultValue = "4000")
    private int maxHeaderLength;

    /**
     * A list of cases to exclude during the test. Default is to exclude none.
     */
    @Parameter(property = "h2spec.excludeSpecs")
    private List<String> excludeSpecs;

    /**
     * The class which is used to startup the Server. It will pass the port in as argument to the main(...) method.
     */
    @Parameter(property = "h2spec.mainClass", required = true)
    private String mainClass;

    /**
     * The number of milliseconds to max wait for the server to startup. Default is 10000 ms
     */
    @Parameter(property = "h2spec.waitTime")
    private long waitTime = 10000;

    /**
     * Set this to "true" to ignore a failure during testing. Its use is NOT RECOMMENDED, but quite convenient on
     * occasion.
     */
    @Parameter(property = "maven.test.failure.ignore", defaultValue = "false" )
    private boolean testFailureIgnore;

    @Parameter(property = "maven.test.skip", defaultValue = "false" )
    protected boolean skip;

    @Parameter(property = "h2spec.junitFileName", defaultValue = "TEST-h2spec.xml" )
    private String junitFileName;

    @Parameter(property = "h2spec.verbose", defaultValue = "false" )
    private boolean verbose;

    @Parameter(property = "h2spec.version", defaultValue = DEFAULT_VERSION )
    private String h2specVersion;

    @Parameter(property = "h2spec.containerName", defaultValue = "summerwind/h2spec")
    private String h2specContainerName;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(property = "h2spec.junitPackage", defaultValue = "h2spec")
    private String junitPackage;

    @Parameter(property = "h2spec.reportsDirectory", defaultValue = "${project.build.directory}/surefire-reports")
    private File reportsDirectory;

    @Parameter(property = "h2spec.skipNoDockerAvailable", defaultValue = "false")
    private boolean skipNoDockerAvailable;

    /**
     * maximum timeout in seconds to run the tests
     */
    @Parameter(property = "h2spec.testTimeout", defaultValue = "60")
    private int testTimeout = 60;

    /**
     * maximum timeout in minutes to run all the tests
     */
    @Parameter(property = "h2spec.totalTestTimeout", defaultValue = "10")
    private int totalTestTimeout = 10;

    @SuppressWarnings("unchecked")
    private ClassLoader getClassLoader() throws MojoExecutionException
    {
        try
        {
            List<String> classpathElements = project.getTestClasspathElements();
            classpathElements.add(project.getBuild().getOutputDirectory());
            classpathElements.add(project.getBuild().getTestOutputDirectory());

            return new URLClassLoader(classpathElements.stream().map(s ->
            {
                try
                {
                    return new File(s).toURI().toURL();
                }
                catch (MalformedURLException e)
                {
                    throw new IllegalArgumentException(e);
                }
            }).toArray(URL[]::new), getClass().getClassLoader());
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Couldn't create a classloader", e);
        }
    }

    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if (skip)
        {
            getLog().info("Skip execution of h2spec-maven-plugin");
            return;
        }

        boolean dockerAvailable = DockerClientFactory.instance().isDockerAvailable();

        if (!dockerAvailable && skipNoDockerAvailable){
            getLog().info( "---------------------------------------" );
            getLog().info( "  SKIP H2SPEC AS DOCKER NOT AVAILABLE  " );
            getLog().info( "    DO NOT BE GRUMPY AND INSTALL IT    " );
            getLog().info( "---------------------------------------" );
            return;
        }

        final AtomicReference<Throwable> error = new AtomicReference<>();
        Thread runner = null;
        try
        {
            String host;
            try
            {
                host = InetAddress.getLocalHost().getHostAddress();
            }
            catch (UnknownHostException e)
            {
                getLog().debug("Unable to detect localhost address, using 127.0.0.1 as fallback");
                host = "127.0.0.1";
            }
            if (port == -1)
            {
                // Get some random free port
                port = findRandomOpenPortOnAllLocalInterfaces();
            }
            ClassLoader ori = Thread.currentThread().getContextClassLoader();
            runner = new Thread(() ->
            {
                try
                {
                    Thread.currentThread().setContextClassLoader(getClassLoader());
                    Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(mainClass);
                    Method main = clazz.getMethod("main", String[].class);
                    main.invoke(null, (Object) new String[] {String.valueOf(port) });
                }
                catch (Throwable e)
                {
                    error.set(e);
                } finally
                {
                    Thread.currentThread().setContextClassLoader(ori);
                }
            });
            runner.setDaemon(true);
            runner.start();
            try
            {
                // wait for 500 milliseconds to give the server some time to startup
                Thread.sleep(500);
            }
            catch (InterruptedException ignore)
            {
                Thread.currentThread().interrupt();
            }
            if (waitTime <= 0)
            {
                // use 10 seconds as default
                waitTime = 10000;
            }

            // Wait until the server accepts connections
            long sleepTime = waitTime / 10;
            for (int i = 0; i < 10; i++)
            {
                Throwable cause = error.get();
                if (cause != null)
                {
                    throw new MojoExecutionException("Unable to start server", cause);
                }
                try (Socket socket = new Socket())
                {
                    socket.connect(new InetSocketAddress(host, port));
                    break;
                }
                catch (IOException e)
                {
                    try
                    {
                        Thread.sleep(sleepTime);
                    }
                    catch (InterruptedException ignore)
                    {
                        // restore interrupt state
                        Thread.currentThread().interrupt();
                    }
                }
                if (i == 9)
                {
                    throw new MojoExecutionException("Unable to connect to server in " + waitTime, error.get());
                }
            }

            if (excludeSpecs == null)
            {
                excludeSpecs = Collections.emptyList();
            }

            try
            {
                getLog().info("!!! Exclude specs");
                for (String excludeSpec : excludeSpecs)
                {
                    getLog().info(excludeSpec);
                }

                List<Failure> allFailures;
                List<Failure> nonIgnoredFailures = new ArrayList<>();
                List<Failure> ignoredFailures = new ArrayList<>();

                if (!Files.exists(reportsDirectory.toPath()))
                {
                    getLog().debug("Reports directory " + reportsDirectory.getAbsolutePath() + " does not exist, try creating it...");
                    if (reportsDirectory.mkdirs())
                    {
                        getLog().debug("Reports directory " + reportsDirectory.getAbsolutePath() + " created.");
                    }
                    else
                    {
                        getLog().debug("Failed to create report directory");
                    }
                }

                File junitFile = new File(reportsDirectory, junitFileName);
                //junitFile.createNewFile();
                String imageName = h2specContainerName + ":" + h2specVersion;
                String command = String.format( "-h %s -p %d -j %s -o %d --max-header-length %d",
                                                "host.testcontainers.internal",
                                                port,
                                                "/tmp/junit.xml",
                                                timeout,
                                                maxHeaderLength );
                if ( verbose )
                {
                    command = command + " -v";
                }

                getLog().info( "running image: " + imageName + " with command: " + command);

                Testcontainers.exposeHostPorts(port);

                Files.deleteIfExists(junitFile.toPath());

                Path containerTmp = Paths.get(project.getBuild().getDirectory(), "h2spec_tmp");
                if(Files.exists(containerTmp)) {
                    FileUtils.deleteDirectory( containerTmp.toFile() );
                }
                Files.createDirectories( containerTmp );
                try (GenericContainer h2spec = new GenericContainer( DockerImageName.parse( imageName ) ) )
                {
                    h2spec.withLogConsumer(new MojoLogConsumer(getLog()));
                    h2spec.setWaitStrategy(new LogMessageWaitStrategy(totalTestTimeout).withStartLine("Finished in ")
                                               .withStartupTimeout(Duration.ofSeconds(testTimeout)));
                    h2spec.setPortBindings( Arrays.asList( Integer.toString( port ) ) );
                    h2spec.withCommand( command );
                    h2spec.withFileSystemBind( containerTmp.toString(), "/tmp", BindMode.READ_WRITE );
                    h2spec.start();
                }
                getLog().info( "list tmp files: " +
                               Files.list(containerTmp).map(path -> path.toString()).collect(Collectors.toList()));
                Files.copy( new File(containerTmp.toString(), "junit.xml").toPath(),
                            junitFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING );
                // after container stop to be sure file flushed
                // cleanup so it's readable by Jenkins
                cleanupJunitReportFileOnlyTime(junitFile);
                allFailures =
                    H2SpecTestSuite.parseReports( getLog(), junitFile.getParentFile(), new HashSet<>(excludeSpecs) );

                allFailures.forEach(failure ->
                {
                    if (failure.isIgnored())
                    {
                        ignoredFailures.add(failure);
                    }
                    else
                    {
                        nonIgnoredFailures.add(failure);
                    }
                });

                if (nonIgnoredFailures.size() > 0)
                {
                    StringBuilder sb = new StringBuilder("\nFailed test cases:\n");
                    nonIgnoredFailures.forEach(failure -> sb.append("\t").append(failure.toString()).append("\n\n"));
                    if (!testFailureIgnore)
                    {
                        cleanupJunitReportFile(junitFile);
                        throw new MojoFailureException(sb.toString());
                    }
                }
                else
                {
                    getLog().info("All test cases passed. " + ignoredFailures.size() + " test cases ignored.");
                    // mark fail those ignored/failed test as skipped
                    markedFailedTestAsSkipped(junitFile.toPath());
                }
                cleanupJunitReportFile(junitFile);
            }
            catch (Exception e)
            {
                throw new MojoExecutionException(e.getMessage(), e);
            }

        }
        finally
        {
            if (runner != null)
            {
                runner.interrupt();
            }
        }
    }

    private static class MojoLogConsumer extends ToStringConsumer
    {
        private StringBuilder buffer = new StringBuilder();

        private Log log;

        public MojoLogConsumer( Log log )
        {
            this.log = log;
        }

        @Override
        public void accept( OutputFrame outputFrame )
        {
            super.accept(outputFrame);
            log.info(toUtf8String());
        }
    }

    protected void markedFailedTestAsSkipped( Path junitFile)
        throws IOException, XmlPullParserException
    {
        if(this.excludeSpecs == null || this.excludeSpecs.isEmpty())
        {
            return;
        }
        Xpp3Dom dom;
        try(Reader reader = Files.newBufferedReader( junitFile ))
        {
            dom = Xpp3DomBuilder.build(reader);
            Arrays.stream(dom.getChildren()).forEach(
                testsuite ->
                {
                    if (!"0".equals( testsuite.getAttribute( "errors" ) ))
                    {
                        Arrays.stream( testsuite.getChildren() ).forEach( testcase -> {
                            if ( testcase.getChild( "error" ) != null )
                            {
                                int skipped = Integer.valueOf( testsuite.getAttribute( "skipped" ) );
                                int errors = Integer.valueOf( testsuite.getAttribute( "errors" ) );
                                testsuite.setAttribute( "skipped", Integer.toString( ++skipped ) );
                                testsuite.setAttribute( "errors", Integer.toString( --errors ) );
                                Xpp3Dom skippedDom = new Xpp3Dom( "skipped" );
                                skippedDom.setValue( testcase.getChild( 0 ).getValue() );
                                testcase.addChild( skippedDom );
                                testcase.removeChild( 0 );
                            }
                        } );
                    }
                }
            );
        }
        try (Writer writer = Files.newBufferedWriter( junitFile ))
        {
            Xpp3DomWriter.write(writer, dom);
        }
    }

    private void cleanupJunitReportFile(File junitFile)
        throws IOException, XmlPullParserException
    {
        Xpp3Dom dom;
        try(Reader reader = Files.newBufferedReader( junitFile.toPath() ))
        {
            dom = Xpp3DomBuilder.build( reader);
            Arrays.stream(dom.getChildren()).forEach(
                testsuite ->
                {
                    testsuite.setAttribute( "package", "" );
                    testsuite.setAttribute( "id", "" );
                    Arrays.stream( testsuite.getChildren() )
                        .forEach( testcase -> {
                            String className = testcase.getAttribute("classname");
                            testcase.setAttribute( "classname", junitPackage + "."
                                + StringUtils.replace(testsuite.getAttribute("name"), ' ', '_' ));
                            testcase.setAttribute( "package", "" );
                            testcase.setAttribute( "name",
                                                   StringUtils.replace( className, ' ', '_' ));
                        });
                }
            );
        }
        try (Writer writer = Files.newBufferedWriter(junitFile.toPath()))
        {
            Xpp3DomWriter.write(writer, dom);
        }
    }

    private void cleanupJunitReportFileOnlyTime(File junitFile)
        throws IOException, XmlPullParserException
    {
        DecimalFormat df = new DecimalFormat( "#.#####");
        Xpp3Dom dom;
        try(Reader reader = Files.newBufferedReader( junitFile.toPath() ))
        {
            dom = Xpp3DomBuilder.build( reader);
            Arrays.stream(dom.getChildren()).forEach(
                testsuite ->
                {
                    final float[] time = { (float) 0 };
                    Arrays.stream( testsuite.getChildren() )
                        .forEach( testcase -> time[0] += Float.parseFloat( testcase.getAttribute( "time" ) ));
                    testsuite.setAttribute( "time", df.format( time[0] ) );// Float.toString( time[0] )
                }
            );
        }
        try (Writer writer = Files.newBufferedWriter( junitFile.toPath() ))
        {
            Xpp3DomWriter.write(writer, dom);
        }
    }

    private int findRandomOpenPortOnAllLocalInterfaces()
    {

        try(ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Can't find an open socket", e);
        }
    }

    static class LogMessageWaitStrategy extends AbstractWaitStrategy
    {

        private String startLine;

        private int times = 1;

        private int totalTestTimeout;

        public LogMessageWaitStrategy( String startLine, int times, int totalTestTimeout )
        {
            this.startLine = startLine;
            this.times = times;
            this.totalTestTimeout = totalTestTimeout;
        }

        public LogMessageWaitStrategy( int totalTestTimeout )
        {
            this.totalTestTimeout = totalTestTimeout;
        }

        @Override
        protected void waitUntilReady() {
            WaitingConsumer waitingConsumer = new WaitingConsumer();

            LogContainerCmd cmd = DockerClientFactory.instance().client().logContainerCmd(waitStrategyTarget.getContainerId())
                .withFollowStream(true)
                .withSince(0)
                .withStdOut(true)
                .withStdErr(true);

            try
            {
                try (FrameConsumerResultCallback callback = new FrameConsumerResultCallback()) {
                    callback.addConsumer(STDOUT, waitingConsumer);
                    callback.addConsumer(STDERR, waitingConsumer);

                    cmd.exec(callback);

                    Predicate<OutputFrame> waitPredicate = outputFrame -> {
                        String line = outputFrame.getUtf8String();
                        return line.startsWith(startLine);
                    };
                    try {
                        //waitingConsumer.waitUntilEnd(totalTestTimeout, TimeUnit.MINUTES);
                        waitingConsumer.waitUntil(waitPredicate, totalTestTimeout,
                                                  TimeUnit.MINUTES,
                                                  times);
                    } catch ( TimeoutException e) {
                        throw new ContainerLaunchException( "Timed out waiting for log output matching '" + startLine + "'");
                    }
                }
            }
            catch ( IOException e )
            {
                throw new RuntimeException(e.getMessage(), e);
            }

        }

        public LogMessageWaitStrategy withStartLine( String startLine) {
            this.startLine = startLine;
            return this;
        }

    }


    public void setExcludeSpecs( List<String> excludeSpecs )
    {
        this.excludeSpecs = excludeSpecs;
    }
}
