package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** {@code Limit.of} over a nullable bound (spec engine/20 R-EXE-01). */
class LimitTest {

    @Test
    void ac_exe_01_a_null_limit_is_unlimited_and_a_number_is_a_bound() {
        assertThat(Limit.of(null)).isSameAs(Limit.unlimited());
        assertThat(Limit.of((Integer) null).maxRows()).isEqualTo(OptionalInt.empty());
        assertThat(Limit.of(0).maxRows()).isEqualTo(OptionalInt.of(0));
        assertThat(Limit.of(Integer.valueOf(7))).isEqualTo(Limit.of(7)).hasToString("Limit.of(7)");
        assertThatThrownBy(() -> Limit.of(-1)).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2001));
    }
}
