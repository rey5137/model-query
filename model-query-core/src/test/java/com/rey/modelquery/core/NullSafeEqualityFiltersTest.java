package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Recording the null-safe equality filters, before any Criteria query exists (spec api/12 R-FLT-29). */
class NullSafeEqualityFiltersTest {

    static final class Order {}

    static final class OrderView {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<OrderView, Order, String> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", String.class);
    private static final ColumnField<OrderView, Order, String> NOTE =
            ColumnField.of(OrderView.class, ROOT, "note", String.class);
    private static final ColumnField<OrderView, Order, String> CODE = ColumnField.of(OrderView.class, ROOT, "code",
            String.class, Integer.class, new CodeConverter());

    static final class CodeConverter implements ColumnConverter<String, Integer> {
        @Override
        public Integer toAttribute(String value) {
            return Integer.valueOf(value);
        }

        @Override
        public String toModel(Integer attribute) {
            return String.valueOf(attribute);
        }
    }

    @Test
    void ac_flt_37_a_null_value_is_the_value_null_and_throws_nothing() {
        String none = null;
        assertThat(FilterGroup.<OrderView>collect(f -> f.notDistinctFrom(STATUS, none).distinctFrom(STATUS, none)))
                .hasSize(2);
    }

    @Test
    void ac_flt_39_an_empty_optional_skips_the_filter_and_a_null_optional_is_a_programming_error() {
        assertThat(FilterGroup.<OrderView>collect(f -> f
                .notDistinctFrom(STATUS, Optional.<String>empty()).distinctFrom(STATUS, Optional.<String>empty())))
                .isEmpty();
        assertThat(FilterGroup.<OrderView>collect(f -> f.notDistinctFrom(STATUS, Optional.of("a")))).hasSize(1);
        Optional<String> missing = null;
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.distinctFrom(STATUS, missing)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ac_flt_02_the_other_value_forms_still_throw_mq1301_on_null() {
        String none = null;
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.eq(STATUS, none)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301));
    }

    @Test
    void ac_flt_38_two_columns_of_one_attribute_type_record_and_a_converter_mismatch_throws_mq1001() {
        assertThatCode(() -> FilterGroup.<OrderView>collect(f -> f.notDistinctFromColumn(STATUS, NOTE)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.notDistinctFromColumn(CODE, STATUS)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1001))
                .hasMessageContaining("notDistinctFrom").hasMessageContaining("Integer");
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.distinctFromColumn(STATUS, CODE)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1001))
                .hasMessageContaining("distinctFrom");
    }
}
