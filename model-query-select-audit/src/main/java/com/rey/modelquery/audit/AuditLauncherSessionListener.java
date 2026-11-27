package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import java.io.IOException;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Opens the select audit for a JUnit session (RFC 0026 Part D, R-INS-22, D-230, D-265). With no {@code -javaagent},
 * it attaches the agent itself ({@code modelquery.selectAudit.attach=auto}, the default); when there is still no
 * agent, {@code modelquery.selectAudit.missingAgent} decides: {@code fail} (the default) fails the session,
 * {@code warn} logs once and runs unaudited, {@code off} runs unaudited silently and attaches nothing.
 */
@Incubating
public final class AuditLauncherSessionListener implements LauncherSessionListener {

    private static final System.Logger LOG = System.getLogger(AuditLauncherSessionListener.class.getName());

    /** Creates a launcher session listener. */
    @Incubating
    public AuditLauncherSessionListener() {}

    @Override
    @Incubating
    public void launcherSessionOpened(LauncherSession session) {
        if (AuditAgent.disabled()) {
            return;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        // both parsed first, so a wrong value fails the session whether or not an agent is attached
        AuditSettings.Attach attach = AuditSettings.attach(loader);
        AuditSettings.MissingAgent missing = AuditSettings.missingAgent(loader);
        if (!AuditAgent.isInstalled()) {
            if (missing == AuditSettings.MissingAgent.OFF) {
                AuditAgent.disable();
                return;
            }
            String reason = attach == AuditSettings.Attach.AUTO
                    ? SelfAttach.attach()
                    : AuditSettings.ATTACH + "=startup";
            if (reason == null) {
                LOG.log(System.Logger.Level.INFO, "model-query-select-audit: " + SelectAuditReport.RUNTIME_ATTACH_NOTE);
            } else {
                // an agent that started too late (seams loaded first) must not audit part of the run either
                AuditAgent.disable();
                if (missing == AuditSettings.MissingAgent.WARN) {
                    LOG.log(System.Logger.Level.WARNING, "model-query-select-audit has no agent (" + reason
                            + "); tests run unaudited (" + AuditSettings.MISSING_AGENT + "=warn)");
                    return;
                }
                throw new IllegalStateException("model-query-select-audit is on the classpath but its javaagent is "
                        + "not installed (" + reason + "); add "
                        + "-javaagent:${io.github.rey5137:model-query-select-audit:jar} (set by "
                        + "maven-dependency-plugin's properties goal) to your surefire or failsafe plugin argLine, or set "
                        + AuditSettings.MISSING_AGENT + "=warn or off for a run that is not audited");
            }
        }
        session.getLauncher().registerTestExecutionListeners(new AuditTestListener());
    }

    @Override
    @Incubating
    public void launcherSessionClosed(LauncherSession session) {
        if (AuditAgent.isInstalled() && !AuditAgent.disabled()) {
            try {
                SelectAuditReport.writeForkReport();
            } catch (IOException | RuntimeException e) {
                System.getLogger(AuditLauncherSessionListener.class.getName()).log(
                        System.Logger.Level.WARNING, "Failed to write select-audit report", e);
            }
        }
    }
}
