package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AC-INS-27: the agent settings and their defaults, from a system property or junit-platform.properties (D-265). */
class AuditSettingsTest {

    @Test
    void ac_ins_27_defaults_are_auto_attach_and_fail() {
        assertThat(AuditSettings.parse(AuditSettings.ATTACH, null, AuditSettings.Attach.class,
                AuditSettings.Attach.AUTO)).isEqualTo(AuditSettings.Attach.AUTO);
        assertThat(AuditSettings.parse(AuditSettings.MISSING_AGENT, " ", AuditSettings.MissingAgent.class,
                AuditSettings.MissingAgent.FAIL)).isEqualTo(AuditSettings.MissingAgent.FAIL);
        assertThat(AuditSettings.parse(AuditSettings.MISSING_AGENT, " Warn ", AuditSettings.MissingAgent.class,
                AuditSettings.MissingAgent.FAIL)).isEqualTo(AuditSettings.MissingAgent.WARN);
    }

    @Test
    void ac_ins_27_an_unknown_value_names_the_allowed_ones() {
        assertThatThrownBy(() -> AuditSettings.parse(AuditSettings.MISSING_AGENT, "quiet",
                AuditSettings.MissingAgent.class, AuditSettings.MissingAgent.FAIL))
                .hasMessage("modelquery.selectAudit.missingAgent=quiet is not one of fail|warn|off");
    }

    @Test
    void ac_ins_27_junit_platform_properties_is_read_and_a_system_property_wins(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("junit-platform.properties"),
                "modelquery.selectAudit.attach=startup\nmodelquery.selectAudit.missingAgent=off\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            assertThat(AuditSettings.attach(loader)).isEqualTo(AuditSettings.Attach.STARTUP);
            System.setProperty(AuditSettings.MISSING_AGENT, "warn");
            try {
                assertThat(AuditSettings.missingAgent(loader)).isEqualTo(AuditSettings.MissingAgent.WARN);
            } finally {
                System.clearProperty(AuditSettings.MISSING_AGENT);
            }
            assertThat(AuditSettings.missingAgent(loader)).isEqualTo(AuditSettings.MissingAgent.OFF);
        }
    }
}
