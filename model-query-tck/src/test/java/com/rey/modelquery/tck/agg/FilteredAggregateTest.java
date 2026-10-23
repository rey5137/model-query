package com.rey.modelquery.tck.agg;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * A filtered aggregate on every target: each kind equals the hand-written {@code Expr.cases} form, a filter through an
 * optional to-one drops no row, {@code MQ1412} refuses what a filter cannot hold, and a filtered aggregate works in
 * {@code having} (spec api/13 R-AGG-17, AC-AGG-20 to AC-AGG-22). The engine's CASE form, which runs with no aggregate
 * renderer, is tested in the jpa module's {@code AggregateFoldTest}; the refusals of {@code Agg.of}, a repeated
 * filter and a correlated value in core's {@code FilteredAggregateTest}.
 */
class FilteredAggregateTest {

    /** The one-cell row of the single-aggregate queries. */
    record Box(Object value) {}

    /** One status and the rows it counts, the row of the {@code having} query. */
    record Group(String status, Long paid) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER = TableField.join(ORDERS, "referrer", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);

    private static final OrderedColumnField<Box, OrderEntity, Long> ID =
            ColumnField.of(Box.class, ORDERS, "id", Long.class);
    private static final OrderedColumnField<Box, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(Box.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<Box, OrderEntity, String> STATUS =
            ColumnField.of(Box.class, ORDERS, "status", String.class);
    private static final ColumnField<Box, CustomerEntity, String> REFERRER_COUNTRY =
            ColumnField.of(Box.class, REFERRER, "country", String.class);
    private static final ColumnField<Box, OrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(Box.class, ITEMS, "quantity", Integer.class);

    // ---- AC-AGG-20

    @TckTest
    void ac_agg_20_each_filtered_aggregate_equals_its_hand_written_case_form(TckDatabase db) {
        record Pair(AggregateField<Box, ?> filtered, AggregateField<Box, ?> hand) {}
        UnaryOperator<Filters<Box>> paid = f -> f.eq(STATUS, "PAID");
        var totalWhenPaid = Expr.cases(Box.class, BigDecimal.class).when(paid, TOTAL).orNull();
        var idWhenPaid = Expr.cases(Box.class, Long.class).when(paid, ID).orNull();
        var one = Expr.cases(Box.class, Integer.class).when(paid, 1).orNull();
        List<Pair> pairs = List.of(
                new Pair(Agg.count(ID).filter(paid), Agg.count(one)),
                new Pair(Agg.<Box>count(ORDERS).filter(paid), Agg.count(one)),
                new Pair(Agg.countDistinct(ID).filter(paid), Agg.countDistinct(idWhenPaid)),
                new Pair(Agg.sum(TOTAL).filter(paid), Agg.sum(totalWhenPaid)),
                new Pair(Agg.avg(TOTAL).filter(paid), Agg.avg(totalWhenPaid)),
                new Pair(Agg.min(TOTAL).filter(paid), Agg.min(totalWhenPaid)),
                new Pair(Agg.max(TOTAL).filter(paid), Agg.max(totalWhenPaid)),
                new Pair(Agg.sumAsLong(ID).filter(paid), Agg.sumAsLong(idWhenPaid)));

        SqlSnapshots.assertMatches(db, "agg-20-filtered-kinds", ds -> executor(ds, executor ->
                pairs.forEach(p -> value(executor, p.filtered()))));
        executor(db, executor -> pairs.forEach(p -> {
            Object filtered = value(executor, p.filtered());
            assertThat(filtered).as(p.filtered().toString()).isNotNull();
            Object hand = value(executor, p.hand());
            assertThat(normalise(filtered)).as(p.filtered().toString()).isEqualTo(normalise(hand));
            // R-AGG-03: a provider's filtered sum must not widen the unfiltered type (Hibernate types it Number).
            assertThat(filtered.getClass()).as(p.filtered().toString()).isEqualTo(p.filtered().type())
                    .isEqualTo(hand.getClass());
        }));
        // A filter is not a no-op: the unfiltered sum counts the rows the filter drops.
        executor(db, executor -> assertThat((BigDecimal) value(executor, Agg.sum(TOTAL).filter(paid)))
                .isLessThan((BigDecimal) value(executor, Agg.sum(TOTAL))));
    }

    @TckTest
    void ac_agg_20_a_filter_through_an_optional_to_one_resolves_left_and_drops_no_row(TckDatabase db) {
        var inReferrerCountry = Agg.count(ID).filter(f -> f.eq(REFERRER_COUNTRY, "US"));
        var everyRow = Agg.count(ID);
        var sumInCountry = Agg.sum(TOTAL).filter(f -> f.eq(REFERRER_COUNTRY, "US"));

        SqlSnapshots.assertMatches(db, "agg-20-filter-left-join", ds -> executor(ds, executor -> {
            value(executor, inReferrerCountry);
            value(executor, sumInCountry);
        }));
        executor(db, executor -> {
            // Two thirds of the orders have no referrer: an inner join would count only the third that has one.
            assertThat(value(executor, everyRow)).isEqualTo((long) TckFixture.ORDERS);
            long matching = (Long) value(executor, inReferrerCountry);
            assertThat(matching).isPositive().isLessThan(TckFixture.ORDERS / 3);
            assertThat(value(executor, everyRow)).as("the same statement keeps every order").isEqualTo(
                    (long) TckFixture.ORDERS);
            // Both aggregates in one statement: the filtered one must not narrow the unfiltered count.
            var both = ModelQuery.builder(ORDERS,
                    row -> new Box(List.of(row.get(everyRow), row.get(inReferrerCountry))))
                    .select(SelectSet.of(everyRow, inReferrerCountry)).build();
            assertThat(executor.list(both, Limit.unlimited()).get(0).value())
                    .isEqualTo(List.of((long) TckFixture.ORDERS, matching));
            assertThat(value(executor, sumInCountry)).isInstanceOf(BigDecimal.class);
        });
    }

    // ---- AC-AGG-21

    @TckTest
    void ac_agg_21_a_filter_through_a_to_many_path_throws_mq1412(TckDatabase db) {
        var quantityCount = Agg.count(ID).filter(f -> f.gt(ITEM_QUANTITY, 4));
        executor(db, executor -> assertThatThrownBy(() -> value(executor, quantityCount))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1412))
                .hasMessageContaining("to-many"));
    }

    @TckTest
    void ac_agg_21_a_to_many_join_the_aggregate_already_reads_is_the_aggregated_rows_own(TckDatabase db) {
        var largeItems = Agg.sumAsLong(ITEM_QUANTITY).filter(f -> f.gt(ITEM_QUANTITY, 4));
        var itemCount = Agg.sumAsLong(ITEM_QUANTITY);
        executor(db, executor -> {
            long all = ((Number) value(executor, itemCount)).longValue();
            long large = ((Number) value(executor, largeItems)).longValue();
            var expected = oracle(db, "select sum(quantity) from order_items where quantity > 4");
            assertThat(large).isEqualTo(((Number) expected.get(0)[0]).longValue()).isLessThan(all);
        });
    }

    @TckTest
    void ac_agg_21_exists_over_a_to_many_inside_a_filter_joins_nothing_in_the_outer_statement(TckDatabase db) {
        var withLargeItem = Agg.count(ID).filter(f -> f.exists(ITEMS, i -> i.gt(ITEM_QUANTITY, 4)));
        executor(db, executor -> {
            var expected = oracle(db, "select count(*) from orders o where exists "
                    + "(select 1 from order_items i where i.order_id = o.id and i.quantity > 4)");
            assertThat(((Number) value(executor, withLargeItem)).longValue())
                    .isEqualTo(((Number) expected.get(0)[0]).longValue());
        });
    }

    // ---- AC-AGG-22

    @TckTest
    void ac_agg_22_a_filtered_aggregate_in_having_keeps_the_groups_over_the_threshold(TckDatabase db) {
        var status = ColumnField.of(Group.class, ORDERS, "status", String.class);
        var id = ColumnField.of(Group.class, ORDERS, "id", Long.class);
        var total = ColumnField.of(Group.class, ORDERS, "total", BigDecimal.class);
        var paid = Agg.count(id).filter(f -> f.gt(total, new BigDecimal("500.00")));
        long threshold = 623;
        var grouped = ModelQuery.builder(ORDERS, row -> new Group(row.get(status), row.get(paid)))
                .select(SelectSet.of(status, paid)).groupBy(status).having(h -> h.gt(paid, threshold))
                .orderBy(status.asc()).build();

        List<String> sql = SqlSnapshots.assertMatches(db, "agg-22-filtered-having", ds ->
                executor(ds, executor -> executor.list(grouped, Limit.unlimited())));
        assertThat(sql).isNotEmpty();
        executor(db, executor -> {
            List<Group> groups = executor.list(grouped, Limit.unlimited());
            var expected = oracle(db, "select status, count(case when total > 500.00 then 1 end) from orders "
                    + "group by status having count(case when total > 500.00 then 1 end) > " + threshold
                    + " order by status");
            assertThat(groups).isNotEmpty().extracting(Group::status).containsExactlyElementsOf(
                    expected.stream().map(r -> (String) r[0]).toList());
            assertThat(groups).extracting(Group::paid).containsExactlyElementsOf(
                    expected.stream().map(r -> ((Number) r[1]).longValue()).toList());
            assertThat(groups).hasSizeLessThan(4);
        });
    }

    @org.junit.jupiter.api.Test
    void ac_agg_22_equal_filtered_fields_are_one_selection_and_different_conditions_are_two() {
        var paid = Agg.sum(TOTAL).filter(f -> f.eq(STATUS, "PAID"));
        var again = Agg.sum(TOTAL).filter(f -> f.eq(STATUS, "PAID"));
        var shipped = Agg.sum(TOTAL).filter(f -> f.eq(STATUS, "SHIPPED"));
        assertThat(SelectSet.of(paid, again).fields()).hasSize(1);
        assertThat(SelectSet.of(paid, shipped).fields()).hasSize(2);
        assertThat(SelectSet.of(paid, Agg.sum(TOTAL)).fields()).hasSize(2);
    }

    // ---- support

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object value(ModelQueryExecutor<OrderEntity> executor, AggregateField<Box, ?> field) {
        var query = ModelQuery.builder(ORDERS, row -> new Box(row.get((AggregateField) field)))
                .select(SelectSet.of((AggregateField) field)).build();
        return ((List<Box>) executor.list(query, Limit.unlimited())).get(0).value();
    }

    /** Numbers compare by value, whatever scale or width the database returned. */
    private static Object normalise(Object value) {
        return value instanceof BigDecimal d ? d.stripTrailingZeros()
                : value instanceof Double d ? BigDecimal.valueOf(d).setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros() : value;
    }

    private static void executor(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static void executor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    /** Every row of {@code sql}, each as its column values. */
    private static List<Object[]> oracle(TckDatabase db, String sql) {
        var result = new java.util.ArrayList<Object[]>();
        try (var connection = db.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            int width = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                Object[] row = new Object[width];
                for (int i = 0; i < width; i++) {
                    row[i] = rows.getObject(i + 1);
                }
                result.add(row);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return result;
    }
}
