package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** {@code Agg.avgExact}, {@code Agg.round} and {@code Expr.round}: definition-time refusals and equality (R-AGG-18). */
class ExactAverageTest {

    static final class Order {}

    static final class Summary {}

    static final class CentsConverter implements ColumnConverter<Long, Integer> {
        @Override
        public Long toModel(Integer attribute) {
            return attribute * 100L;
        }

        @Override
        public Integer toAttribute(Long model) {
            return (int) (model / 100);
        }
    }

    static final class MoneyConverter implements OrderedColumnConverter<BigDecimal, BigDecimal> {
        @Override
        public BigDecimal toModel(BigDecimal attribute) {
            return attribute.movePointLeft(2);
        }

        @Override
        public BigDecimal toAttribute(BigDecimal model) {
            return model.movePointRight(2);
        }
    }

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<Summary, Order, BigDecimal> AMOUNT =
            ColumnField.of(Summary.class, ROOT, "amount", BigDecimal.class);
    private static final ColumnField<Summary, Order, Double> RATE =
            ColumnField.of(Summary.class, ROOT, "rate", Double.class);
    private static final ColumnField<Summary, Order, Float> WEIGHT =
            ColumnField.of(Summary.class, ROOT, "weight", Float.class);
    private static final ColumnField<Summary, Order, Short> SMALL =
            ColumnField.of(Summary.class, ROOT, "small", Short.class);
    private static final ColumnField<Summary, Order, Integer> COUNT =
            ColumnField.of(Summary.class, ROOT, "count", Integer.class);
    private static final ColumnField<Summary, Order, Long> BIG = ColumnField.of(Summary.class, ROOT, "big", Long.class);
    private static final ColumnField<Summary, Order, Long> CENTS =
            ColumnField.of(Summary.class, ROOT, "cents", Long.class, Integer.class, new CentsConverter());

    private static final OrderedColumnField<Summary, Order, BigDecimal> MONEY = ColumnField.of(Summary.class, ROOT,
            "money", BigDecimal.class, BigDecimal.class, new MoneyConverter());

    private static void assertCode(MqCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void ac_agg_24_integer_and_decimal_inputs_are_accepted() {
        assertThat(Agg.avgExact(AMOUNT).type()).isEqualTo(BigDecimal.class);
        assertThat(Agg.avgExact(BIG).name()).isEqualTo("avgExact(big)");
        assertThatCode(() -> {
            Agg.avgExact(COUNT);
            Agg.avgExact(SMALL);
            Agg.avgExact(Expr.negate(COUNT));
        }).doesNotThrowAnyException();
    }

    @Test
    void ac_agg_24_a_double_or_float_input_is_mq1411() {
        assertCode(MqCode.MQ1411, () -> Agg.avgExact(RATE));
        assertCode(MqCode.MQ1411, () -> Agg.avgExact(WEIGHT));
        assertCode(MqCode.MQ1411, () -> Agg.avgExact(Expr.negate(RATE)));
    }

    @Test
    void ac_agg_24_a_converted_column_is_mq1408() {
        assertCode(MqCode.MQ1408, () -> Agg.avgExact(CENTS));
    }

    @Test
    void ac_agg_25_the_scale_must_be_0_to_30() {
        var average = Agg.avgExact(AMOUNT);
        assertCode(MqCode.MQ1411, () -> Agg.round(average, -1));
        assertCode(MqCode.MQ1411, () -> Agg.round(average, 31));
        assertCode(MqCode.MQ1411, () -> Expr.round(AMOUNT, -1));
        assertCode(MqCode.MQ1411, () -> Expr.round(AMOUNT, 31));
        assertThat(Agg.round(average, 0)).isNotNull();
        assertThat(Agg.round(average, 30)).isNotNull();
        assertThat(Expr.round(AMOUNT, 0)).isNotNull();
        assertThat(Expr.round(AMOUNT, 30)).isNotNull();
    }

    @Test
    void ac_agg_25_equal_rounds_are_equal_fields() {
        var two = Agg.round(Agg.avgExact(AMOUNT), 2);

        var same = Agg.round(Agg.avgExact(AMOUNT), 2);

        assertThat(two).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(two).isNotEqualTo(Agg.round(Agg.avgExact(AMOUNT), 3)).isNotEqualTo(Agg.avgExact(AMOUNT));
        assertThat(two).isNotEqualTo(Agg.round(Agg.avgExact(AMOUNT).filter(f -> f.eq(AMOUNT, BigDecimal.ONE)), 2));
        assertThat(two.name()).isEqualTo("round(avgExact(amount), 2)");
        assertThat(two.as("avg2").name()).isEqualTo("avg2");
        assertThat(Expr.round(AMOUNT, 2)).isEqualTo(Expr.round(AMOUNT, 2)).isNotEqualTo(Expr.round(AMOUNT, 1));
    }

    @Test
    void ac_agg_25_a_rounded_aggregate_cannot_be_filtered() {
        var rounded = Agg.round(Agg.avgExact(AMOUNT), 2);
        assertCode(MqCode.MQ1412, () -> rounded.filter(f -> f.eq(AMOUNT, BigDecimal.ONE)));
    }

    @Test
    void ac_agg_23_a_filtered_exact_average_keeps_its_filter_in_equality() {
        var filtered = Agg.avgExact(AMOUNT).filter(f -> f.eq(AMOUNT, BigDecimal.ONE));

        assertThat(filtered).isNotEqualTo(Agg.avgExact(AMOUNT))
                .isEqualTo(Agg.avgExact(AMOUNT).filter(f -> f.eq(AMOUNT, BigDecimal.ONE)));
    }

    @Test
    void ac_agg_25_round_refuses_a_min_or_max_over_a_converted_column() {
        assertCode(MqCode.MQ1408, () -> Agg.round(Agg.min(MONEY), 2));
        assertCode(MqCode.MQ1408, () -> Agg.round(Agg.max(MONEY), 2));
    }

    @Test
    void ac_agg_25_round_takes_a_plain_min_max_count_distinct_and_filtered_aggregate() {
        assertThatCode(() -> {
            Agg.round(Agg.min(Expr.negate(AMOUNT)), 2);
            Agg.round(Agg.max(Expr.negate(AMOUNT)), 2);
            Agg.round(Agg.avgExact(AMOUNT).filter(f -> f.eq(AMOUNT, BigDecimal.ONE)), 2);
            Agg.round(Agg.round(Agg.avgExact(AMOUNT), 4), 2);
        }).doesNotThrowAnyException();
    }

    @Test
    void ac_agg_25_equal_rounds_of_conflicting_agg_of_keys_are_mq1103() {
        AggregateField<Summary, BigDecimal> first = Agg.of("x", BigDecimal.class, (ctx, cb) -> null);
        AggregateField<Summary, BigDecimal> second = Agg.of("x", BigDecimal.class, (ctx, cb) -> null);
        assertThat(AggregateField.conflict(first, second)).isTrue();
        assertThat(AggregateField.conflict(Agg.round(first, 2), Agg.round(second, 2))).isTrue();
        assertThat(AggregateField.conflict(Agg.round(Agg.round(first, 3), 2), Agg.round(Agg.round(second, 3), 2)))
                .isTrue();
        assertThat(AggregateField.conflict(Agg.round(first, 2), Agg.round(first, 2))).isFalse();
        assertThat(AggregateField.conflict(Agg.round(first, 2), Agg.round(second, 1))).isFalse();
    }
}
