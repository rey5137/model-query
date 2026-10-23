package com.rey.modelquery.tck.agg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.SampleValueEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.hibernate.SessionFactory;

/**
 * The exact average and the rounding on every target: {@code sum(x) / count(x)} read as a {@code BigDecimal} for
 * decimal and integer inputs, a filter on it, and {@code round} half away from zero, as an aggregate and per row (spec
 * api/13 R-AGG-18, api/10 R-COL-37, AC-AGG-23 to AC-AGG-25). A scale of an unrounded average follows the database, so
 * every comparison is numeric.
 */
class ExactAverageTest {

    /** The one-cell row of the single-field queries. */
    record Box(Object value) {}

    /** A group's key and its rounded average. */
    record Group(Integer key, BigDecimal average) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Box, OrderEntity, Long> ORDER_ID =
            ColumnField.of(Box.class, ORDERS, "id", Long.class);
    private static final ColumnField<Box, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(Box.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<Box, OrderEntity, String> STATUS =
            ColumnField.of(Box.class, ORDERS, "status", String.class);

    private static final TableField<SampleValueEntity, SampleValueEntity> SAMPLES =
            TableField.root(SampleValueEntity.class);
    private static final ColumnField<Box, SampleValueEntity, Long> ID =
            ColumnField.of(Box.class, SAMPLES, "id", Long.class);
    private static final ColumnField<Box, SampleValueEntity, Short> SMALL =
            ColumnField.of(Box.class, SAMPLES, "smallValue", Short.class);
    private static final ColumnField<Box, SampleValueEntity, Integer> INT =
            ColumnField.of(Box.class, SAMPLES, "intValue", Integer.class);
    private static final ColumnField<Box, SampleValueEntity, Long> BIG =
            ColumnField.of(Box.class, SAMPLES, "bigValue", Long.class);
    private static final ColumnField<Box, SampleValueEntity, BigDecimal> DEC =
            ColumnField.of(Box.class, SAMPLES, "decValue", BigDecimal.class);
    private static final ColumnField<Group, SampleValueEntity, Integer> GROUP_KEY =
            ColumnField.of(Group.class, SAMPLES, "intValue", Integer.class);
    private static final ColumnField<Group, SampleValueEntity, BigDecimal> GROUP_DEC =
            ColumnField.of(Group.class, SAMPLES, "decValue", BigDecimal.class);

    // ---- AC-AGG-23

    @TckTest
    void ac_agg_23_a_decimal_average_is_exact_and_not_a_double(TckDatabase db) {
        var average = Agg.avgExact(TOTAL);
        BigDecimal expected = mean(db, "select total from orders");

        Object actual = onOrders(db, executor -> value(executor, ORDERS, average));

        assertThat(actual).isInstanceOf(BigDecimal.class);
        // The mean has more decimals than the numeric(12,2) column, so a column-scale result would be wrong.
        assertThat(expected.setScale(2, RoundingMode.HALF_UP)).isNotEqualByComparingTo(expected.setScale(4,
                RoundingMode.HALF_UP));
        assertThat(((BigDecimal) actual).setScale(4, RoundingMode.HALF_UP))
                .isEqualByComparingTo(expected.setScale(4, RoundingMode.HALF_UP));
    }

    @TckTest
    void ac_agg_23_no_rows_is_null(TckDatabase db) {
        var none = Agg.avgExact(TOTAL).filter(f -> f.eq(ORDER_ID, -1L));
        Object actual = onOrders(db, executor -> value(executor, ORDERS, none));
        assertThat(actual).isNull();
    }

    @TckTest
    void ac_agg_23_an_unfiltered_average_over_no_rows_is_null(TckDatabase db) {
        var average = Agg.avgExact(TOTAL);
        var query = ModelQuery.builder(ORDERS, row -> new Box(row.get(average))).select(SelectSet.of(average))
                .where(f -> f.eq(ORDER_ID, -1L)).build();
        List<Box> rows = onOrders(db, executor -> executor.list(query, Limit.unlimited()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).value()).isNull();
    }

    @TckTest
    void ac_agg_23_a_filter_averages_only_the_rows_it_matches_and_stays_exact(TckDatabase db) {
        var paid = Agg.avgExact(TOTAL).filter(f -> f.eq(STATUS, "PAID"));
        BigDecimal expected = mean(db, "select total from orders where status = 'PAID'");

        Object actual = onOrders(db, executor -> value(executor, ORDERS, paid));

        assertThat(((BigDecimal) actual).setScale(4, RoundingMode.HALF_UP))
                .isEqualByComparingTo(expected.setScale(4, RoundingMode.HALF_UP));
        assertThat((BigDecimal) actual).isNotEqualByComparingTo(
                (BigDecimal) onOrders(db, executor -> value(executor, ORDERS, Agg.avgExact(TOTAL))));
    }

    // ---- AC-AGG-24

    @TckTest
    void ac_agg_24_smallint_integer_and_bigint_averages_are_exact(TckDatabase db) {
        // 1, 2, 2 and 1: integer division would give 1, a double 1.5 with no scale to read.
        for (AggregateField<Box, BigDecimal> average : List.of(Agg.avgExact(SMALL), Agg.avgExact(INT),
                Agg.avgExact(BIG))) {
            Object actual = onSamples(db, executor -> value(executor, SAMPLES, average));
            assertThat(actual).as(average.toString()).isInstanceOf(BigDecimal.class);
            assertThat((BigDecimal) actual).as(average.toString()).isEqualByComparingTo("1.5");
        }
        assertThat((BigDecimal) onSamples(db, executor -> value(executor, SAMPLES,
                Agg.avgExact(Expr.negate(INT))))).isEqualByComparingTo("-1.5");
    }

    @TckTest
    void ac_agg_24_a_bigint_column_of_many_rows_averages_to_its_half(TckDatabase db) {
        // Order ids 1 to 5000: 2500.5, which a bigint division reads as 2500.
        assertThat((BigDecimal) onOrders(db, executor -> value(executor, ORDERS, Agg.avgExact(ORDER_ID))))
                .isEqualByComparingTo("2500.5");
    }

    @TckTest
    void ac_agg_24_a_double_or_float_input_and_a_scale_outside_0_to_30_are_mq1411(TckDatabase db) {
        var rate = ColumnField.of(Box.class, SAMPLES, "rate", Double.class);
        var weight = ColumnField.of(Box.class, SAMPLES, "weight", Float.class);
        var exact = Agg.avgExact(DEC);
        for (Runnable refused : List.<Runnable>of(() -> Agg.avgExact(rate), () -> Agg.avgExact(weight),
                () -> Agg.round(exact, -1), () -> Agg.round(exact, 31), () -> Expr.round(DEC, -1),
                () -> Expr.round(DEC, 31))) {
            assertThatThrownBy(refused::run).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1411));
        }
        assertThat(Agg.round(exact, 30)).isNotNull();
        assertThat(Agg.round(exact, 0)).isNotNull();
    }

    // ---- AC-AGG-25

    @TckTest
    void ac_agg_25_an_aggregate_rounds_half_away_from_zero(TckDatabase db) {
        // dec_value of rows 1 to 4: 2.345, -2.345, 2.500 and -2.500.
        List<String> twoDecimals = List.of("2.35", "-2.35", "2.50", "-2.50");
        List<String> noDecimals = List.of("2", "-2", "3", "-3");
        onSamples(db, executor -> {
            for (int i = 0; i < 4; i++) {
                long id = i + 1;
                var average = Agg.avgExact(DEC).filter(f -> f.eq(ID, id));
                assertThat((BigDecimal) value(executor, SAMPLES, Agg.round(average, 2)))
                        .as("round(%d, 2)", id).isEqualByComparingTo(twoDecimals.get(i));
                assertThat((BigDecimal) value(executor, SAMPLES, Agg.round(average, 0)))
                        .as("round(%d, 0)", id).isEqualByComparingTo(noDecimals.get(i));
            }
            return null;
        });
    }

    @TckTest
    void ac_agg_25_a_row_level_round_rounds_half_away_from_zero(TckDatabase db) {
        ExpressionField<Box, BigDecimal> twoDecimals = Expr.round(DEC, 2);
        ExpressionField<Box, BigDecimal> noDecimals = Expr.round(DEC, 0);
        assertThat(column(db, twoDecimals)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2.35"), new BigDecimal("-2.35"), new BigDecimal("2.50"),
                        new BigDecimal("-2.50"));
        assertThat(column(db, noDecimals)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2"), new BigDecimal("-2"), new BigDecimal("3"),
                        new BigDecimal("-3"));
    }

    @TckTest
    void ac_agg_25_a_rounded_aggregate_is_an_aggregate_for_group_having_and_order(TckDatabase db) {
        // Group 1 holds 2.345 and -2.500 (mean -0.0775), group 2 holds -2.345 and 2.500 (mean 0.0775).
        var rounded = Agg.round(Agg.avgExact(GROUP_DEC), 2);
        var query = ModelQuery.builder(SAMPLES, row -> new Group(row.get(GROUP_KEY), row.get(rounded)))
                .select(SelectSet.of(GROUP_KEY, rounded)).groupBy(GROUP_KEY)
                .having(h -> h.gt(rounded, BigDecimal.ZERO)).orderBy(rounded.desc()).build();

        List<Group> groups = onSamples(db, executor -> executor.list(query, Limit.unlimited()));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).key()).isEqualTo(2);
        assertThat(groups.get(0).average()).isEqualByComparingTo("0.08");
        var all = ModelQuery.builder(SAMPLES, row -> new Group(row.get(GROUP_KEY), row.get(rounded)))
                .select(SelectSet.of(GROUP_KEY, rounded)).groupBy(GROUP_KEY).orderBy(rounded.asc()).build();
        List<Group> ordered = onSamples(db, executor -> executor.list(all, Limit.unlimited()));
        assertThat(ordered).extracting(Group::average)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("-0.08"), new BigDecimal("0.08"));
    }

    @TckTest
    void ac_agg_25_equal_rounds_are_one_selection_and_one_column(TckDatabase db) {
        var first = Agg.round(Agg.avgExact(DEC), 2);
        var second = Agg.round(Agg.avgExact(DEC), 2);
        assertThat(SelectSet.of(first, second).fields()).hasSize(1);
        assertThat(SelectSet.of(first, Agg.round(Agg.avgExact(DEC), 1)).fields()).hasSize(2);
        var query = ModelQuery.builder(SAMPLES, row -> new Box(row.get(first) + "/" + row.get(second)))
                .select(SelectSet.of(first, second)).build();
        List<Box> rows = onSamples(db, executor -> executor.list(query, Limit.unlimited()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).value().toString().split("/")).hasSize(2);
    }

    // ---- support

    /** The mean of the single {@code total} column {@code sql} selects, in Java's exact arithmetic. */
    private static BigDecimal mean(TckDatabase db, String sql) {
        List<BigDecimal> values = new java.util.ArrayList<>();
        try (var connection = db.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getBigDecimal(1));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL128);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Object value(ModelQueryExecutor<T> executor, TableField<T, T> root,
            AggregateField<Box, ?> field) {
        var query = ModelQuery.builder(root, row -> new Box(row.get((AggregateField) field)))
                .select(SelectSet.of((AggregateField) field)).build();
        return ((List<Box>) executor.list(query, Limit.unlimited())).get(0).value();
    }

    /** The field's value on each sample row, in id order. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<BigDecimal> column(TckDatabase db, SelectField<Box, ?> field) {
        var query = ModelQuery.builder(SAMPLES, row -> new Box(row.get((SelectField) field)))
                .select(SelectSet.of((SelectField) field)).orderBy(ID.asc()).build();
        List<Box> rows = onSamples(db, executor -> executor.list(query, Limit.unlimited()));
        return rows.stream().map(box -> (BigDecimal) box.value()).toList();
    }

    private static <R> R onOrders(TckDatabase db,
            java.util.function.Function<ModelQueryExecutor<OrderEntity>, R> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            return sf.fromSession(em -> work.apply(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static <R> R onSamples(TckDatabase db,
            java.util.function.Function<ModelQueryExecutor<SampleValueEntity>, R> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            return sf.fromSession(em -> work.apply(
                    ModelQueryExecutor.create(em, SampleValueEntity.class, ModelQueryConfig.defaults())));
        }
    }
}
