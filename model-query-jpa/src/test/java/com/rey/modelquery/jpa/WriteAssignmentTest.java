package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** {@link WriteAssignment}'s construction checks (spec api/14 R-WRT-49, AC-WRT-39). */
class WriteAssignmentTest {

    static class Audited {}

    private static final Supplier<Timestamp> NOW = () -> new Timestamp(0L);

    @Test
    void ac_wrt_39_of_reads_back_what_it_was_given() {
        var assignment = WriteAssignment.of(Audited.class, "audit.updatedAt", Timestamp.class,
                WriteKind.INSERT_AND_UPDATE, NOW);

        assertThat(assignment.entity()).isEqualTo(Audited.class);
        assertThat(assignment.attribute()).isEqualTo("audit.updatedAt");
        assertThat(assignment.type()).isEqualTo(Timestamp.class);
        assertThat(assignment.kind()).isEqualTo(WriteKind.INSERT_AND_UPDATE);
        assertThat(assignment.value()).isSameAs(NOW);
        assertThat(assignment).hasToString("WriteAssignment[Audited.audit.updatedAt: Timestamp, INSERT_AND_UPDATE]");
        assertThat(WriteAssignment.of(Audited.class, "version_2", int.class, WriteKind.UPDATE, () -> 1).type())
                .isEqualTo(int.class);
    }

    @Test
    void ac_wrt_39_of_rejects_a_null_argument() {
        assertThatThrownBy(() -> WriteAssignment.of(null, "updatedAt", Timestamp.class, WriteKind.UPDATE, NOW))
                .isInstanceOf(NullPointerException.class).hasMessage("entity");
        assertThatThrownBy(() -> WriteAssignment.of(Audited.class, null, Timestamp.class, WriteKind.UPDATE, NOW))
                .isInstanceOf(NullPointerException.class).hasMessage("attribute");
        assertThatThrownBy(() -> WriteAssignment.of(Audited.class, "updatedAt", null, WriteKind.UPDATE, NOW))
                .isInstanceOf(NullPointerException.class).hasMessage("type");
        assertThatThrownBy(() -> WriteAssignment.of(Audited.class, "updatedAt", Timestamp.class, null, NOW))
                .isInstanceOf(NullPointerException.class).hasMessage("kind");
        assertThatThrownBy(() -> WriteAssignment.of(Audited.class, "updatedAt", Timestamp.class, WriteKind.UPDATE,
                null)).isInstanceOf(NullPointerException.class).hasMessage("value");
    }

    @Test
    void ac_wrt_39_of_rejects_a_blank_or_malformed_path() {
        for (String path : new String[] {"", " ", "updated At", ".updatedAt", "audit.", "audit..updatedAt",
                "1st", "audit.-x", "audit/updatedAt"}) {
            assertThatThrownBy(() -> WriteAssignment.of(Audited.class, path, Timestamp.class, WriteKind.UPDATE, NOW))
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Audited: \"" + path + "\" is not an attribute path; name the attribute, "
                            + "dot-separated through embeddables");
        }
    }

    @Test
    void ac_wrt_39_of_rejects_a_primitive_or_array_entity_class() {
        assertThatThrownBy(() -> WriteAssignment.of(int.class, "updatedAt", Timestamp.class, WriteKind.UPDATE, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("int is not an entity class");
        assertThatThrownBy(() -> WriteAssignment.of(Audited[].class, "updatedAt", Timestamp.class, WriteKind.UPDATE,
                NOW)).isInstanceOf(IllegalArgumentException.class).hasMessageEndingWith(" is not an entity class");
    }
}
