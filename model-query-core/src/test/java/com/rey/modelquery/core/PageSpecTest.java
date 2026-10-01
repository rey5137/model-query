package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalInt;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** {@code PageSpec} and {@code ExportOptions} as final classes built by factories (spec engine/20 R-EXE-06, D-88). */
class PageSpecTest {

    @Test
    void ac_exe_05_a_page_spec_is_built_from_a_page_number_or_an_offset_and_compares_by_value() {
        PageSpec page = PageSpec.of(2, 20);
        assertThat(page.offset()).isEqualTo(40);
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(page.pageNumber()).isEqualTo(2);
        assertThat(page).isEqualTo(PageSpec.ofOffset(40, 20)).hasSameHashCodeAs(PageSpec.ofOffset(40, 20))
                .isNotEqualTo(PageSpec.ofOffset(2, 20)).hasToString("PageSpec[offset=40, pageSize=20]");
        // An offset need not be a multiple of the page size; its page number is the page it falls in.
        PageSpec offset = PageSpec.ofOffset(45, 20);
        assertThat(offset.offset()).isEqualTo(45);
        assertThat(offset.pageNumber()).isEqualTo(2);
    }

    @Test
    void ac_exe_05_a_page_spec_or_export_options_refuses_a_non_positive_size_and_a_negative_offset() {
        assertMq(() -> PageSpec.of(0, 0), MqCode.MQ2001);
        assertMq(() -> PageSpec.ofOffset(0, -1), MqCode.MQ2001);
        assertMq(() -> PageSpec.ofOffset(-1, 10), MqCode.MQ2002);
        assertMq(() -> PageSpec.of(-1, 10), MqCode.MQ2002);
        assertMq(() -> PageSpec.of(Integer.MAX_VALUE, 2), MqCode.MQ2002);
        assertMq(() -> ExportOptions.of(0), MqCode.MQ2001);
    }

    @Test
    void ac_exe_05_export_options_keep_their_factories_and_compare_by_value() {
        assertThat(ExportOptions.defaults().pageSize()).isEqualTo(OptionalInt.empty());
        assertThat(ExportOptions.defaults().limit()).isEqualTo(Limit.unlimited());
        ExportOptions capped = ExportOptions.of(500).withLimit(Limit.of(3));
        assertThat(capped.pageSize()).isEqualTo(OptionalInt.of(500));
        assertThat(capped.limit()).isEqualTo(Limit.of(3));
        assertThat(capped).isEqualTo(ExportOptions.of(500).withLimit(Limit.of(3)))
                .hasSameHashCodeAs(ExportOptions.of(500).withLimit(Limit.of(3)))
                .isNotEqualTo(ExportOptions.of(500));
        assertThat(ExportOptions.defaults().withLimit(Limit.of(0)).limit()).isEqualTo(Limit.of(0));
    }

    private static void assertMq(ThrowingCallable call, MqCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
