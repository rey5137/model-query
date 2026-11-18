package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** AC-INS-19: an engine seam whose type loaded without its hook fails the hand-off, naming the seam (D-241). */
class AcInsSeamTest {

    private static final String TYPE = "com.rey.modelquery.core.RowSelection$ValueRow";
    private static final String SEAM = "Row.alias(SelectField)";

    @Test
    void ac_ins_19_all_loaded_engine_seams_are_hooked() {
        assertThat(ReadTransformer.unappliedSeams()).isNull();
    }

    @Test
    void ac_ins_19_seam_type_seen_without_its_hook_fails_the_hand_off_naming_the_seam() {
        boolean wasSeen = !ReadTransformer.seamTypesSeen().add(TYPE);
        boolean wasHooked = ReadTransformer.seamsHooked().remove(SEAM);
        try {
            assertThat(ReadTransformer.unappliedSeams()).isEqualTo(SEAM);
            assertThatThrownBy(() -> new AuditObserver().handedOff(null, Optional.empty(), List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SEAM);
        } finally {
            if (wasHooked) {
                ReadTransformer.seamsHooked().add(SEAM);
            }
            if (!wasSeen) {
                ReadTransformer.seamTypesSeen().remove(TYPE);
            }
        }
        assertThat(ReadTransformer.unappliedSeams()).isNull();
    }
}
