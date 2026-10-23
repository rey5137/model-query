package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** An aggregate's {@code filter}: its recording, equality and refusals, before any Criteria query (R-AGG-17). */
class FilteredAggregateTest {

    static final class Order {}

    static final class Summary {}

    static final class Inner {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<Summary, Order, BigDecimal> AMOUNT =
            ColumnField.of(Summary.class, ROOT, "amount", BigDecimal.class);
    private static final ColumnField<Summary, Order, String> KIND =
            ColumnField.of(Summary.class, ROOT, "kind", String.class);
    private static final ColumnField<Summary, Order, Long> ID = ColumnField.of(Summary.class, ROOT, "id", Long.class);
    private static final ColumnField<Inner, Order, Long> INNER_ID = ColumnField.of(Inner.class, ROOT, "id", Long.class);
    private static final ExpressionField<Summary, Long> COUNT = Correlated.count(Summary.class,
            SubSelect.of(INNER_ID), (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID)));

    private static void assertMq1412(Runnable definition) {
        assertThatThrownBy(definition::run).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1412));
    }

    @Test
    void ac_agg_22_two_filtered_fields_are_equal_when_their_conditions_are() {
        var refunds = Agg.sum(AMOUNT).filter(f -> f.eq(KIND, "refund"));
        var again = Agg.sum(AMOUNT).filter(f -> f.eq(KIND, "refund"));
        var sales = Agg.sum(AMOUNT).filter(f -> f.eq(KIND, "sale"));

        assertThat(refunds).isEqualTo(again).hasSameHashCodeAs(again);
        assertThat(refunds).isNotEqualTo(sales).isNotEqualTo(Agg.sum(AMOUNT));
        assertThat(Agg.sum(AMOUNT)).isNotEqualTo(refunds);
        assertThat(refunds.name()).isEqualTo("sum(amount)");
    }

    @Test
    void ac_agg_22_as_and_named_keep_the_filter() {
        var refunds = Agg.sum(AMOUNT).filter(f -> f.eq(KIND, "refund"));
        var sales = Agg.sum(AMOUNT).filter(f -> f.eq(KIND, "sale"));

        assertThat(refunds.as("refunds")).isEqualTo(Agg.sum(AMOUNT).filter(f -> f.eq(KIND, "refund")).as("refunds"))
                .isNotEqualTo(sales.as("refunds"));
        assertThat(refunds.named("r")).isEqualTo(refunds);
        assertThat(Agg.sum(AMOUNT).as("refunds").filter(f -> f.eq(KIND, "refund"))).isEqualTo(refunds.as("refunds"));
    }

    @Test
    void ac_agg_21_filter_on_agg_of_is_mq1412() {
        var custom = Agg.<Summary, Long>of("n", Long.class, (ctx, cb) -> cb.count(cb.literal(1)));
        assertMq1412(() -> custom.filter(f -> f.eq(KIND, "refund")));
    }

    @Test
    void ac_agg_21_a_second_filter_is_mq1412() {
        var refunds = Agg.sum(AMOUNT).filter(f -> f.eq(KIND, "refund"));
        assertMq1412(() -> refunds.filter(f -> f.eq(KIND, "sale")));
    }

    @Test
    void ac_agg_21_a_condition_that_leaves_no_filter_is_mq1412() {
        assertMq1412(() -> Agg.sum(AMOUNT).filter(f -> f));
    }

    @Test
    void ac_agg_21_a_correlated_value_in_the_condition_is_mq1412() {
        assertMq1412(() -> Agg.sum(AMOUNT).filter(f -> f.eq(COUNT, 1L)));
    }

    @Test
    void ac_vnd_30_every_render_option_wither_carries_the_aggregate_renderer_and_gate() {
        AggregateRenderer renderer = new AggregateRenderer() {};
        AggregateGate gate = AggregateGate.of("H2", Set.of(AggregateFeature.FIRST_BY));
        RenderOptions options = RenderOptions.portable().withAggregateRenderer(renderer).withAggregateGate(gate);

        RenderOptions after = options.withInlinesValues(true)
                .withOperatorGate(OperatorGate.of("H2", Set.of(), Set.of()));
        assertThat(after.aggregateRenderer()).containsSame(renderer);
        assertThat(after.aggregateGate()).containsSame(gate);
        assertThat(RenderOptions.portable().withRenderersOf(options).aggregateRenderer()).containsSame(renderer);
        assertThat(RenderOptions.portable().withRenderersOf(options).aggregateGate()).containsSame(gate);
        assertThat(gate.vendorServes(AggregateFeature.FIRST_BY)).isTrue();
        assertThat(AggregateGate.of("MYSQL", Set.of()).vendorServes(AggregateFeature.FIRST_BY)).isFalse();
    }
}
