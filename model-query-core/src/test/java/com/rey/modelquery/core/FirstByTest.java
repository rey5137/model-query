package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code Agg.firstBy}: its recording, equality and the renderer defaults, before any Criteria query (R-AGG-16). */
class FirstByTest {

    static final class Order {}

    static final class Summary {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<Summary, Order, BigDecimal> AMOUNT =
            ColumnField.of(Summary.class, ROOT, "amount", BigDecimal.class);
    private static final ColumnField<Summary, Order, String> KIND =
            ColumnField.of(Summary.class, ROOT, "kind", String.class);
    private static final ColumnField<Summary, Order, Long> QTY = ColumnField.of(Summary.class, ROOT, "qty", Long.class);

    @Test
    void ac_agg_18_the_result_is_the_values_own_type_and_the_name_is_the_function_and_value() {
        var first = Agg.firstBy(KIND, QTY.desc());

        assertThat(first.type()).isEqualTo(String.class);
        assertThat(first.name()).isEqualTo("firstBy(kind)");
    }

    @Test
    void ac_agg_18_two_first_bys_are_equal_when_their_orders_are() {
        var first = Agg.firstBy(KIND, QTY.desc().nullsLast());

        assertThat(first).isEqualTo(Agg.firstBy(KIND, QTY.desc().nullsLast())).hasSameHashCodeAs(
                Agg.firstBy(KIND, QTY.desc().nullsLast()));
        assertThat(first).isNotEqualTo(Agg.firstBy(KIND, QTY.asc().nullsLast()))
                .isNotEqualTo(Agg.firstBy(KIND, QTY.desc().nullsFirst()))
                .isNotEqualTo(Agg.firstBy(KIND, QTY.desc().nullsLast(), AMOUNT.asc()))
                .isNotEqualTo(Agg.firstBy(KIND, QTY.desc()));
    }

    @Test
    void ac_agg_18_a_converted_value_is_not_the_raw_column_of_the_same_attribute() {
        ColumnField<Summary, Order, Integer> coded = ColumnField.of(Summary.class, ROOT, "kind", Integer.class,
                String.class, new ColumnConverter<Integer, String>() {
                    @Override
                    public Integer toModel(String attribute) {
                        return attribute.length();
                    }

                    @Override
                    public String toAttribute(Integer model) {
                        return "x".repeat(model);
                    }
                });
        ColumnField<Summary, Order, Integer> raw = ColumnField.of(Summary.class, ROOT, "kind", Integer.class);

        assertThat(Agg.firstBy(coded, QTY.asc())).isNotEqualTo(Agg.firstBy(raw, QTY.asc()));
    }

    @Test
    void ac_agg_18_as_named_and_filter_keep_the_orders() {
        var first = Agg.firstBy(KIND, QTY.desc());
        var filtered = first.filter(f -> f.eq(KIND, "sale"));

        assertThat(filtered).isEqualTo(Agg.firstBy(KIND, QTY.desc()).filter(f -> f.eq(KIND, "sale")));
        assertThat(filtered).isNotEqualTo(first)
                .isNotEqualTo(Agg.firstBy(KIND, QTY.asc()).filter(f -> f.eq(KIND, "sale")));
        assertThat(first.as("top")).isEqualTo(Agg.firstBy(KIND, QTY.desc()).as("top"))
                .isNotEqualTo(Agg.firstBy(KIND, QTY.asc()).as("top"));
        assertThat(first.named("p")).isEqualTo(first);
        assertThat(first.orders()).hasSize(1);
    }

    @Test
    void ac_agg_18_an_aggregate_order_key_is_mq1412() {
        assertThatThrownBy(() -> Agg.firstBy(KIND, Agg.sumAsLong(QTY).desc()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1412));
    }

    @Test
    void ac_agg_19_the_default_renderer_refuses_first_by_and_its_filtered_forms_with_mq4014() {
        var renderer = AggregateRenderer.DEFAULT;

        for (Runnable refused : new Runnable[] {
                () -> renderer.firstBy(null, null, List.of()),
                () -> renderer.firstBy(null, null, List.of(), null),
                () -> renderer.filtered(null, AggregateField.Kind.FIRST_BY, null, null)}) {
            assertThatThrownBy(refused::run).isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
        }
    }
}
