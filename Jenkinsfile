#!groovy

pipeline {
  agent none
  options {
    disableConcurrentBuilds()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '5'))
    timeout(time: 15, unit: 'MINUTES')
    skipDefaultCheckout()
  }
  stages {
    stage( "Parallel Stage" ) {
      parallel {
        stage( "Build / Test - JDK11" ) {
          agent { node { label 'linux-light' } }
          options { timeout( time: 120, unit: 'MINUTES' ) }
          steps {
            checkout scm
            mavenBuild( "jdk11", "clean install javadoc:jar" )
            // Collect up the jacoco execution results
            recordCoverage name: "Coverage", id: "coverage", tools: [[parser: 'JACOCO']], sourceCodeRetention: 'MODIFIED',
                    sourceDirectories: [[path: 'src/main/java']]
            recordIssues id: "jdk11", name: "Static Analysis jdk11", aggregatingResults: true, enabledForFailure: true,
                         tools: [mavenConsole(), java(), checkStyle(), spotBugs(), pmdParser(), errorProne()]
            script {

              if ( env.BRANCH_NAME == 'master' )
              {
                mavenBuild( "jdk11", "deploy" )
              }
            }
          }
        }
        stage( "Build / Test - JDK17" ) {
          agent { node { label 'linux-light' } }
          options { timeout( time: 120, unit: 'MINUTES' ) }
          steps {
            checkout scm
            mavenBuild( "jdk17", "clean install javadoc:jar" )
          }
        }
        stage( "Build / Test - JDK21" ) {
          agent { node { label 'linux-light' } }
          options { timeout( time: 120, unit: 'MINUTES' ) }
          steps {
            checkout scm
            mavenBuild( "jdk21", "clean install javadoc:jar" )
          }
        }
      }
    }
  }
}

/**
 * To other developers, if you are using this method above, please use the following syntax.
 *
 * mavenBuild("<jdk>", "<profiles> <goals> <plugins> <properties>"
 *
 * @param jdk the jdk tool name (in jenkins) to use for this build
 * @param cmdline the command line in "<profiles> <goals> <properties>"`format.
 * @return the Jenkinsfile step representing a maven build
 */
def mavenBuild(jdk, cmdline) {
  script {
    try {
      withEnv(["JAVA_HOME=${ tool "$jdk" }",
               "PATH+MAVEN=${ tool "$jdk" }/bin:${tool "maven3"}/bin",
               "MAVEN_OPTS=-Xms2g -Xmx4g -Djava.awt.headless=true"]) {
        configFileProvider(
                [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          sh "mvn --no-transfer-progress -s $GLOBAL_MVN_SETTINGS -Pci -V -B -e $cmdline"
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml', allowEmptyResults: true
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy
