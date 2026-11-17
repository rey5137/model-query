package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Fails the JUnit session when opened if {@code model-query-select-audit} is on the classpath but its javaagent is not
 * attached (RFC 0026 Part D, R-INS-22, D-230).
 */
@Incubating
public final class AuditLauncherSessionListener implements LauncherSessionListener {

    /** Creates a launcher session listener. */
    @Incubating
    public AuditLauncherSessionListener() {}

    @Override
    @Incubating
    public void launcherSessionOpened(LauncherSession session) {
        if (!AuditAgent.isInstalled()) {
            throw new IllegalStateException("model-query-select-audit is on the classpath but its javaagent is not "
                    + "installed; add -javaagent:${io.github.rey5137:model-query-select-audit:jar} "
                    + "(set by maven-dependency-plugin's properties goal) to your surefire "
                    + "or failsafe plugin argLine");
        }
    }
}
