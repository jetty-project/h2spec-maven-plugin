package org.mortbay.jetty.maven.h2spec;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.surefire.report.ReportTestCase;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.reporting.MavenReportException;

public class H2SpecTestSuite {

    public static final String DEFAULT_VERSION = "2.6.1";

    public static String getSpecIdentifier(String specId, String name) {
        return specId + " - " + name;
    }

    public static List<Failure> parseReports(
            final Log logger, final File reportsDirectory, final Set<String> excludeSpecs) {
        logger.debug("Parsing h2spec reports");
        SurefireReportParser parser =
                new SurefireReportParser(Collections.singletonList(reportsDirectory), Locale.getDefault());

        String currentPackageName = "";
        List<Failure> failures = new ArrayList<>();
        try {
            List<ReportTestSuite> parsedReports = parser.parseXMLReportFiles();
            logger.debug(parsedReports.size() + " h2spec reports parsed.");
            for (ReportTestSuite parsedReport : parsedReports) {
                final String name = parsedReport.getFullClassName();
                final String specIdentifier = getSpecIdentifier(currentPackageName, name);
                logger.debug("Spec Identifier [" + specIdentifier + "]");

                String packageName = parsedReport.getPackageName();
                if (!packageName.isEmpty()) {
                    currentPackageName = packageName;
                }

                if (parsedReport.getNumberOfErrors() > 0) {
                    for (ReportTestCase reportTestCase : parsedReport.getTestCases()) {
                        String failureDetail = reportTestCase.getFailureDetail();
                        if (failureDetail != null) {
                            String[] failureTokens = failureDetail.split("\n");
                            boolean ignored = excludeSpecs.contains(specIdentifier);
                            if (ignored)
                                logger.debug("Case Status: FAILED [" + specIdentifier + "] (ignored by excludeSpec): "
                                        + failureDetail);
                            else logger.debug("Case Status: FAILED: [" + specIdentifier + "] " + failureDetail);

                            String expected = failureTokens.length > 0 ? failureTokens[0] : "";
                            String actual = "";
                            int last = failureTokens.length - 1;
                            if (last > 0) actual = failureTokens[last];
                            failures.add(new Failure(name, currentPackageName, actual, expected, ignored));
                        } else {
                            logger.debug("Case Status: SUCCESS");
                        }
                    }
                }
            }
        } catch (MavenReportException e) {
            logger.warn(e.getMessage(), e);
        }

        return failures;
    }
}
