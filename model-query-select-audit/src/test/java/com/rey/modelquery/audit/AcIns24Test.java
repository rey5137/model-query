package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.vendor.HandOffObserver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AC-INS-24: With {@code attach=startup}, the jar without the agent fails the JUnit session when it opens; outside
 * JUnit it logs once and changes no SQL and no result; the jpa default changes neither.
 */
class AcIns24Test {

    @Test
    void ac_ins_24_listener_is_registered_via_service_loader() throws Exception {
        String serviceFile = "META-INF/services/org.junit.platform.launcher.LauncherSessionListener";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(serviceFile)) {
            assertThat(in).as("LauncherSessionListener service registration file").isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertThat(content).contains("com.rey.modelquery.audit.AuditLauncherSessionListener");
        }
    }

    @Test
    void ac_ins_24_missing_agent_fails_junit_session_naming_arg_line() throws Exception {
        Fork result = fork("org.example.audit.MissingAgentLauncherMain", false);
        assertThat(result.exit()).isNotZero();
        assertThat(result.output()).contains("javaagent is not installed",
                "-javaagent:${io.github.rey5137:model-query-select-audit:jar}",
                "maven-dependency-plugin's properties goal", "argLine");
    }

    @Test
    void ac_ins_24_outside_junit_missing_agent_logs_once_and_does_not_throw() throws Exception {
        Fork result = fork("org.example.audit.PlainAuditMain", true);
        assertThat(result.exit()).isZero();
        assertThat(result.output().lines().filter(line -> line.contains("audit is disabled"))).hasSize(1);
    }

    @Test
    void ac_ins_24_jpa_default_noop_observer_changes_neither_result_nor_throws() {
        HandOffObserver none = HandOffObserver.NONE;
        ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                .select(QAuditOrderView.ALL)
                .build();
        AuditOrderView order = new AuditOrderView(1L, 100L, "Notes", Optional.empty(), List.of());

        // Calling handedOff on NONE does nothing and does not throw
        none.handedOff(query, Optional.empty(), List.of(order), List.of());
        assertThat(AuditObserver.tagOf(order)).isNull();
    }
    private record Fork(int exit, String output) {}

    private static Fork fork(String main, boolean withoutJUnit) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        if (withoutJUnit) {
            classpath = java.util.Arrays.stream(classpath.split(java.io.File.pathSeparator))
                    .filter(entry -> !entry.contains("junit") && !entry.contains("surefire"))
                    .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        }
        java.nio.file.Path log = java.nio.file.Files.createTempFile("audit-fork-", ".log");
        Process process = null;
        try {
            var builder = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                    .toString(), "-Xmx128m",
                    // the startup rule: without it the session would attach the agent itself (D-265)
                    "-D" + AuditSettings.ATTACH + "=startup", "-cp", classpath, main);
            builder.environment().clear();
            process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).as("fork completed").isTrue();
            return new Fork(process.exitValue(), java.nio.file.Files.readString(log));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            java.nio.file.Files.deleteIfExists(log);
        }
    }

}
