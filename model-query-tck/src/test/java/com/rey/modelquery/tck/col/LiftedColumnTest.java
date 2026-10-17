package com.rey.modelquery.tck.col;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.fch.LabelOrders;
import com.rey.modelquery.tck.fch.OrderLines;
import com.rey.modelquery.tck.fch.QLabelOrders;
import com.rey.modelquery.tck.fch.QOrderLines;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.src.OrderSource;
import com.rey.modelquery.tck.src.QOrderSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** Lifted joins retain their outer ON clause, join type and row-shaping effect (D-150). */
class LiftedColumnTest {

    record Row(Long id, Object value) {}

    static final class Inner {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final ColumnField<Row, OrderEntity, Long> ID =
            ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER = TableField.join(ROOT, "referrer", INNER);
    private static final TableField<OrderEntity, CustomerEntity> VIP =
            TableField.<OrderEntity, CustomerEntity>join(ROOT, "referrer", LEFT).as("vipReferrer")
                    .on((from, cb) -> cb.isTrue(from.get("vip")));
    private static final ColumnField<Row, CustomerEntity, String> NAME =
            ColumnField.of(Row.class, REFERRER, "name", String.class);
    private static final ColumnField<Row, CustomerEntity, String> VIP_NAME =
            ColumnField.of(Row.class, VIP, "name", String.class);
    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Inner, CustomerEntity, Long> CUSTOMER_ID =
            ColumnField.of(Inner.class, CUSTOMERS, "id", Long.class);
    private static final ColumnField<Inner, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(Inner.class, CUSTOMERS, "name", String.class);
    private static final SubSelect<Inner, Long> CUSTOMERS_100 =
            SubSelect.of(CUSTOMER_ID).where(f -> f.lte(CUSTOMER_ID, 100L));
    private static final ExpressionField<Row, Long> COUNT = count(NAME);
    private static final String MATCH = "exists (select 1 from customers c where c.id <= 100 and c.name = r.name)";

    @TckTest
    void ac_flt_28_exists_not_exists_and_null_preserve_outer_on(TckDatabase db) throws SQLException {
        var expected = ids(db, "select o.id from orders o left join customers r on r.id=o.referrer_id and r.vip=true "
                + "where o.id<=12 and " + MATCH + " order by o.id");
        var absent = ids(db, "select o.id from orders o left join customers r on r.id=o.referrer_id and r.vip=true "
                + "where o.id<=12 and not " + MATCH + " order by o.id");
        var missing = ids(db, "select o.id from orders o left join customers r on r.id=o.referrer_id "
                + "where o.id<=12 and not " + MATCH + " order by o.id");
        assertThat(missing).contains(1L);
        assertThat(expected).isNotEmpty();
        assertThat(absent).isNotEmpty();
        SqlSnapshots.assertMatches(db, "flt-28-lifted-on-null", ds -> withExecutor(ds, OrderEntity.class, executor -> {
            var exists = selecting(ID).where(f -> f.lte(ID, 12L).exists(CUSTOMERS_100,
                    (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(VIP_NAME)))).build();
            var notExists = selecting(ID).where(f -> f.lte(ID, 12L).notExists(CUSTOMERS_100,
                    (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(VIP_NAME)))).build();
            var nullValue = selecting(ID).where(f -> f.lte(ID, 12L).exists(CUSTOMERS_100,
                    (s, outer) -> s.isNull(outer.column(VIP_NAME)))).build();
            var innerNotExists = selecting(ID).where(f -> f.lte(ID, 12L).notExists(CUSTOMERS_100,
                    (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(NAME)))).build();
            assertThat(executor.list(exists, Limit.unlimited())).extracting(Row::id).isEqualTo(expected);
            assertThat(executor.list(notExists, Limit.unlimited())).extracting(Row::id).isEqualTo(absent);
            assertThat(executor.list(nullValue, Limit.unlimited())).extracting(Row::id).isEqualTo(absent);
            assertThat(executor.list(innerNotExists, Limit.unlimited())).extracting(Row::id).isEqualTo(missing);
        }));
    }

    @TckTest
    void ac_flt_28_nested_to_one_depth_two(TckDatabase db) throws SQLException {
        var items = TableField.root(OrderItemEntity.class);
        var orders = TableField.<OrderItemEntity, OrderEntity>join(items, "order", INNER);
        var referrer = TableField.<OrderEntity, CustomerEntity>join(orders, "referrer", LEFT);
        var id = ColumnField.of(Row.class, items, "id", Long.class);
        var name = ColumnField.of(Row.class, referrer, "name", String.class);
        var query = ModelQuery.builder(items, row -> new Row(row.get(id), null)).select(SelectSet.of(id))
                .where(f -> f.lte(id, 12L).exists(SubSelect.of(CUSTOMER_ID),
                        (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(name))))
                .orderBy(id.asc()).build();
        var expected = ids(db, "select i.id from order_items i join orders o on o.id=i.order_id "
                + "left join customers r on r.id=o.referrer_id where i.id<=12 "
                + "and exists (select 1 from customers c where c.name=r.name) order by i.id");
        assertThat(expected).isNotEmpty();
        SqlSnapshots.assertMatches(db, "flt-28-lifted-depth-two", ds -> withExecutor(ds, OrderItemEntity.class,
                executor -> assertThat(executor.list(query, Limit.unlimited()))
                        .extracting(Row::id).isEqualTo(expected)));
    }

    @TckTest
    void ac_flt_28_through_child_is_the_to_one_paths_base(TckDatabase db) throws SQLException {
        var referrer = TableField.<OrderEntity, CustomerEntity>join(ROOT, "referrer", LEFT);
        var name = ColumnField.of(OrderLines.class, referrer, "name", String.class);
        var plan = FetchPlan.of(QLabelOrders.ALL).child(QLabelOrders.ORDERS, FetchPlan.of(QOrderLines.ALL),
                c -> c.where(f -> f.lte(QOrderLines.ID, 12L).exists(CUSTOMERS_100,
                        (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(name))))
                        .orderBy(QOrderLines.ID.asc()));
        var query = QLabelOrders.query().orderBy(QLabelOrders.ID.asc()).fetch(plan).build();
        Map<Long, List<Long>> expected = new LinkedHashMap<>();
        try (var connection = db.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("select ol.label_id,o.id from order_labels ol "
                        + "join orders o on o.id=ol.order_id left join customers r on r.id=o.referrer_id "
                        + "where o.id<=12 and " + MATCH + " order by ol.label_id,o.id")) {
            while (rows.next()) {
                expected.computeIfAbsent(rows.getLong(1), ignored -> new ArrayList<>()).add(rows.getLong(2));
            }
        }
        assertThat(expected).isNotEmpty();
        Map<Long, List<Long>> actual = new LinkedHashMap<>();
        SqlSnapshots.assertMatches(db, "flt-28-lifted-through", ds -> withExecutor(ds, LabelEntity.class, executor -> {
            for (LabelOrders label : executor.list(query, Limit.unlimited())) {
                if (!label.orders().isEmpty()) {
                    actual.put(label.id(), label.orders().stream().map(OrderLines::id).toList());
                }
            }
        }));
        assertThat(actual).isEqualTo(expected);
    }

    @TckTest
    void ac_flt_28_or_and_not_make_lifted_inner_paths_left(TckDatabase db) throws SQLException {
        var either = ids(db, "select o.id from orders o left join customers r on r.id=o.referrer_id "
                + "where o.id<=12 and (" + MATCH + " or o.id=1) order by o.id");
        var absent = ids(db, "select o.id from orders o left join customers r on r.id=o.referrer_id "
                + "where o.id<=12 and not " + MATCH + " order by o.id");
        SqlSnapshots.assertMatches(db, "flt-28-lifted-or-not", ds -> withExecutor(ds, OrderEntity.class, executor -> {
            var or = selecting(ID).where(f -> f.lte(ID, 12L).or(g -> g.exists(CUSTOMERS_100,
                    (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(NAME))), g -> g.eq(ID, 1L))).build();
            var not = selecting(ID).where(f -> f.lte(ID, 12L).not(g -> g.exists(CUSTOMERS_100,
                    (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(NAME))))).build();
            var innerOr = selecting(ID).where(f -> f.lte(ID, 12L).exists(CUSTOMERS_100,
                    (s, outer) -> s.or(g -> g.compare(CUSTOMER_NAME, Op.EQ, outer.column(NAME)),
                            g -> g.eq(CUSTOMER_ID, 1L)))).build();
            assertThat(executor.list(or, Limit.unlimited())).extracting(Row::id).isEqualTo(either);
            assertThat(executor.list(not, Limit.unlimited())).extracting(Row::id).isEqualTo(absent);
            assertThat(executor.list(innerOr, Limit.unlimited())).hasSize(12);
        }));
    }

    @TckTest
    void ac_flt_28_nested_path_exists_preserves_enclosing_left_joining(TckDatabase db) {
        var orders = TableField.<CustomerEntity, OrderEntity>join(CUSTOMERS, "orders", INNER);
        var items = TableField.<OrderEntity, OrderItemEntity>join(orders, "items", INNER);
        var status = ColumnField.of(Inner.class, orders, "status", String.class);
        var product = ColumnField.of(Inner.class, items, "productCode", String.class);
        SqlSnapshots.assertMatches(db, "flt-28-lifted-nested-or", ds -> withExecutor(ds, OrderEntity.class,
                executor -> {
                    var first = selecting(ID).where(f -> f.lte(ID, 12L).exists(CUSTOMERS_100, (s, outer) -> s.or(
                            g -> g.exists(orders, h -> h.compare(status, Op.EQ, outer.column(NAME))),
                            g -> g.eq(CUSTOMER_ID, 1L)))).build();
                    var nested = selecting(ID).where(f -> f.lte(ID, 12L).exists(CUSTOMERS_100, (s, outer) -> s.or(
                            g -> g.exists(orders, h -> h.exists(items,
                                    i -> i.compare(product, Op.EQ, outer.column(NAME)))),
                            g -> g.eq(CUSTOMER_ID, 1L)))).build();
                    var not = selecting(ID).where(f -> f.lte(ID, 12L).exists(CUSTOMERS_100, (s, outer) -> s.not(
                            g -> g.exists(orders, h -> h.compare(status, Op.EQ, outer.column(NAME)))))).build();
                    var count = Correlated.count(Row.class, CUSTOMERS_100, (s, outer) -> s.or(
                            g -> g.exists(orders, h -> h.compare(status, Op.EQ, outer.column(NAME))),
                            g -> g.eq(CUSTOMER_ID, 1L)));
                    var expected = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
                    assertThat(executor.list(first, Limit.unlimited())).extracting(Row::id).isEqualTo(expected);
                    assertThat(executor.list(nested, Limit.unlimited())).extracting(Row::id).isEqualTo(expected);
                    assertThat(executor.list(not, Limit.unlimited())).extracting(Row::id).isEqualTo(expected);
                    assertThat(executor.list(selecting(count).build(), Limit.unlimited())).hasSize(12)
                            .allSatisfy(row -> assertThat(row.value()).isEqualTo(1L));
                }));
    }

    @TckTest
    void ac_flt_15_through_child_to_many_below_base_fails_before_parent_sql(TckDatabase db) {
        var items = TableField.<OrderEntity, OrderItemEntity>join(ROOT, "items", INNER);
        var product = ColumnField.of(OrderLines.class, items, "productCode", String.class);
        var plan = FetchPlan.of(QLabelOrders.ALL).child(QLabelOrders.ORDERS, FetchPlan.of(QOrderLines.ALL),
                c -> c.where(f -> f.exists(CUSTOMERS_100,
                        (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(product)))));
        var query = QLabelOrders.query().fetch(plan).build();
        assertRefused(db, LabelEntity.class, query, "to-many");
    }

    @TckTest
    void ac_flt_29_correlated_values_reuse_the_selected_join_and_its_on(TckDatabase db) throws SQLException {
        var count = count(VIP_NAME);
        var exists = Correlated.exists(Row.class, CUSTOMERS_100,
                (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(VIP_NAME)));
        var expected = ids(db, "select o.id from orders o left join customers r on r.id=o.referrer_id and r.vip=true "
                + "where o.id<=12 and " + MATCH + " order by o.id");
        var sql = SqlSnapshots.assertMatches(db, "flt-29-lifted-values", ds -> withExecutor(ds, OrderEntity.class,
                executor -> {
                    var query = selecting(count).select(SelectSet.of(ID, VIP_NAME, count, exists)).build();
                    var rows = executor.list(query, Limit.unlimited());
                    assertThat(rows).hasSize(12).allSatisfy(row ->
                            assertThat(row.value()).isEqualTo(expected.contains(row.id()) ? 1L : 0L));
                    var booleans = executor.list(selecting(exists).build(), Limit.unlimited());
                    assertThat(booleans).allSatisfy(row ->
                            assertThat(row.value()).isEqualTo(expected.contains(row.id())));
                }));
        assertThat(sql).allSatisfy(statement -> assertThat(statement.split("left join customers", -1)).hasSize(2));
    }

    @TckTest
    void ac_flt_29_ac_col_41_count_keeps_inner_lifted_joins_and_left_or_not(TckDatabase db) throws SQLException {
        long inner = ids(db, "select o.id from orders o join customers r on r.id=o.referrer_id where o.id<=12").size();
        long matches = ids(db, "select o.id from orders o join customers r on r.id=o.referrer_id "
                + "where o.id<=12 and " + MATCH).size();
        long either = ids(db, "select o.id from orders o left join customers r on r.id=o.referrer_id "
                + "where o.id<=12 and (" + MATCH + " or o.id=1)").size();
        var sql = SqlSnapshots.assertMatches(db, "flt-29-lifted-count-statement", ds -> withExecutor(ds,
                OrderEntity.class, executor -> {
                    assertThat(executor.count(selecting(COUNT).build())).isEqualTo(inner);
                    assertThat(executor.count(selecting(ID).where(f -> f.lte(ID, 12L).eq(COUNT, 1L)).build()))
                            .isEqualTo(matches);
                    assertThat(executor.count(selecting(ID).where(f -> f.lte(ID, 12L)
                            .or(g -> g.eq(COUNT, 1L), g -> g.eq(ID, 1L))).build())).isEqualTo(either);
                    assertThat(executor.count(selecting(ID).where(f -> f.lte(ID, 12L)
                            .not(g -> g.eq(COUNT, 1L))).build())).isEqualTo(12 - matches);
                }));
        assertThat(sql.get(0)).contains("join customers").doesNotContain("left join customers", "(select count(");
        assertThat(sql.get(1)).contains("join customers").doesNotContain("left join customers");
        assertThat(sql.subList(2, 4)).allSatisfy(statement ->
                assertThat(statement).contains("left join customers", "(select count("));
    }

    @TckTest
    void ac_flt_29_source_outer_root_can_be_correlated(TckDatabase db) {
        var root = TableField.source(OrderSource.class);
        var id = ColumnField.over(Row.class, root, QOrderSource.ID);
        var count = Correlated.count(Row.class, SubSelect.of(CUSTOMER_ID),
                (s, outer) -> s.compare(CUSTOMER_ID, Op.EQ, outer.column(id)));
        var source = QOrderSource.source().where(f -> f.lte(QOrderSource.ID, 12L)).build();
        var query = ModelQuery.builder(root, row -> new Row(row.get(id), row.get(count)), source)
                .select(SelectSet.of(id, count)).primaryKey(PrimaryKey.of(id)).orderBy(id.asc()).build();
        SqlSnapshots.assertMatches(db, "flt-29-source-outer", ds -> withExecutor(ds, OrderSource.class, executor -> {
            assertThat(executor.list(query, Limit.unlimited())).hasSize(12)
                    .allSatisfy(row -> assertThat(row.value()).isEqualTo(1L));
            assertThat(executor.count(query)).isEqualTo(12);
        }));
    }

    @TckTest
    void ac_flt_15_to_many_lifts_and_source_sub_selects_fail_before_sql(TckDatabase db) {
        var items = TableField.<OrderEntity, OrderItemEntity>join(ROOT, "items", INNER);
        var order = TableField.<OrderItemEntity, OrderEntity>join(items, "order", INNER);
        var customer = TableField.<OrderEntity, CustomerEntity>join(order, "referrer", LEFT);
        var name = ColumnField.of(Row.class, customer, "name", String.class);
        assertRefused(db, selecting(ID).where(f -> f.exists(CUSTOMERS_100,
                (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(name)))).build(), "to-many");
        assertRefused(db, selecting(ID).where(f -> f.notExists(CUSTOMERS_100,
                (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(name)))).build(), "to-many");
        assertRefused(db, selecting(count(name)).build(), "to-many");
        var orders = TableField.<CustomerEntity, OrderEntity>join(REFERRER, "orders", INNER);
        var status = ColumnField.of(Row.class, orders, "status", String.class);
        assertRefused(db, selecting(ID).where(f -> f.exists(CUSTOMERS_100,
                (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(status)))).build(), "to-many");
        var sourceId = ColumnField.over(Inner.class, TableField.source(OrderSource.class), QOrderSource.ID);
        var sourceRows = SubSelect.of(sourceId);
        assertRefused(db, selecting(ID).where(f -> f.in(ID, sourceRows)).build(), "root is a source");
        assertRefused(db, selecting(ID).where(f -> f.exists(sourceRows,
                (s, outer) -> s.compare(sourceId, Op.EQ, outer.column(ID)))).build(), "root is a source");
        var sourceCount = Correlated.count(Row.class, sourceRows,
                (s, outer) -> s.compare(sourceId, Op.EQ, outer.column(ID)));
        assertRefused(db, selecting(sourceCount).build(), "root is a source");
    }

    private static ExpressionField<Row, Long> count(ColumnField<Row, CustomerEntity, String> name) {
        return Correlated.count(Row.class, CUSTOMERS_100,
                (s, outer) -> s.compare(CUSTOMER_NAME, Op.EQ, outer.column(name)));
    }

    private static ModelQuery.Builder<OrderEntity, Long, Row> selecting(SelectField<Row, ?> value) {
        return ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(value))).select(SelectSet.of(ID, value))
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.lte(ID, 12L)).orderBy(ID.asc());
    }

    private static List<Long> ids(TckDatabase db, String sql) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (var connection = db.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
        }
        return ids;
    }

    private static <E, M> void assertRefused(TckDatabase db, Class<E> root, ModelQuery<E, ?, M> query, String message) {
        List<Throwable> errors = new ArrayList<>();
        var sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, root,
                executor -> errors.add(catchThrowable(() -> executor.list(query, Limit.unlimited())))));
        assertThat(sql).isEmpty();
        assertThat(errors).hasSize(1).allSatisfy(error -> assertThat(error)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        failure -> assertThat(failure.code()).isEqualTo(MqCode.MQ1311))
                .hasMessageContaining(message));
    }

    private static void assertRefused(TckDatabase db, ModelQuery<OrderEntity, ?, Row> query, String message) {
        assertRefused(db, OrderEntity.class, query, message);
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (var sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
