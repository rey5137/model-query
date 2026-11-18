package com.rey.modelquery.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/** Test-only access to package-private audit state for tests outside the audit package. */
public final class AuditProbe {
    private AuditProbe() {}

    public static void clear() {
        ReadEvents.clear();
    }

    /** Whether the model carries an audit tag. */
    public static boolean isTagged(Object model) {
        return AuditObserver.tagOf(model) != null;
    }

    /** The test method the JUnit listener recorded for the calling thread. */
    public static String currentTest() {
        return CurrentTest.get();
    }

    /** Every test name recorded for call sites whose text contains {@code callSite}. */
    public static Set<String> tests(String callSite) {
        Set<String> tests = new TreeSet<>();
        ReadEvents.callSites().forEach((key, stats) -> {
            if (key.callSite().contains(callSite)) {
                tests.addAll(stats.tests);
            }
        });
        return tests;
    }

    /** Writes the fork report into {@code dir} and returns the merged report text. */
    public static String mergedReport(Path dir) throws Exception {
        String previous = System.getProperty(SelectAuditReport.DIR_PROPERTY);
        try {
            System.setProperty(SelectAuditReport.DIR_PROPERTY, dir.toString());
            SelectAuditReport.writeForkReport();
            SelectAuditReport.main(dir.toString());
            return Files.readString(dir.resolve("select-audit.txt"));
        } finally {
            if (previous == null) {
                System.clearProperty(SelectAuditReport.DIR_PROPERTY);
            } else {
                System.setProperty(SelectAuditReport.DIR_PROPERTY, previous);
            }
        }
    }
}
