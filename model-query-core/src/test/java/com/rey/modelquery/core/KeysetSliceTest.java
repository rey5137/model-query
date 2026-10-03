package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** {@code KeysetSlice} and {@code KeysetSpec} as final classes with derived flags (engine/21 R-PAG-16, R-PAG-21). */
class KeysetSliceTest {

    // AC-PAG-16

    @Test
    void ac_pag_16_the_flags_are_derived_from_the_cursors_and_content_is_copied_unmodifiably() {
        List<String> content = new ArrayList<>(List.of("a", "b"));
        KeysetSlice<String> page = KeysetSlice.of(content, 2, Optional.of("prev"), Optional.of("next"));
        content.add("c"); // the slice copied its content

        assertThat(page.content()).containsExactly("a", "b");
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.previousCursor()).contains("prev");
        assertThat(page.nextCursor()).contains("next");
        assertThat(page.hasPrevious()).isTrue();
        assertThat(page.hasNext()).isTrue();
        assertThatThrownBy(() -> page.content().add("d")).isInstanceOf(UnsupportedOperationException.class);

        KeysetSlice<String> first = KeysetSlice.of(List.of("a"), 10, Optional.empty(), Optional.of("next"));
        assertThat(first.hasPrevious()).isFalse();
        assertThat(first.previousCursor()).isEmpty();
        assertThat(first.hasNext()).isTrue();
        assertThat(first.size()).isEqualTo(10);

        KeysetSlice<String> empty = KeysetSlice.of(List.of(), 10, Optional.empty(), Optional.empty());
        assertThat(empty.hasNext()).isFalse();
        assertThat(empty.hasPrevious()).isFalse();
        assertThat(empty.nextCursor()).isEmpty();
        assertThat(empty.previousCursor()).isEmpty();
    }

    // AC-PAG-16

    @Test
    void ac_pag_16_a_keyset_spec_takes_a_size_in_1_to_max_minus_one_and_a_direction() {
        assertThat(KeysetSpec.first(1).direction()).isEqualTo(KeysetSpec.Direction.FIRST);
        assertThat(KeysetSpec.first(1).hasCursor()).isFalse();
        assertThat(KeysetSpec.first(Integer.MAX_VALUE - 1).size()).isEqualTo(Integer.MAX_VALUE - 1);
        assertMq2001(() -> KeysetSpec.first(0));
        assertMq2001(() -> KeysetSpec.first(-1));
        assertMq2001(() -> KeysetSpec.first(Integer.MAX_VALUE));
        assertMq2001(() -> KeysetSpec.after("anything", Integer.MAX_VALUE));
        assertMq2001(() -> KeysetSpec.before("anything", 0));
    }

    private static void assertMq2001(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2001));
    }
}
