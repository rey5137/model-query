package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.EmbeddedKeyEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.LabelEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.PlainOrderItemEntity;
import com.rey.modelquery.tck.fch.LabelOrders;
import com.rey.modelquery.tck.fch.OrderLines;
import com.rey.modelquery.tck.fch.QLabelOrders;
import com.rey.modelquery.tck.fch.QOrderLines;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Sub-selects: {@code in}/{@code notIn} over a {@code SubSelect}, correlated {@code exists}/{@code notExists}, their
 * error cases and their effect on count, paging and export (spec api/12 R-FLT-12, R-FLT-15..17, D-112).
 */
class SubSelectTest {

    record O(Long id, String status, BigDecimal total, Long referrerId) {}

    record I(Long orderId, String productCode, Integer quantity) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<PlainOrderItemEntity, PlainOrderItemEntity> PLAIN_ITEMS =
            TableField.root(PlainOrderItemEntity.class);

    static final ColumnField<O, OrderEntity, Long> ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    static final ColumnField<O, OrderEntity, String> STATUS = ColumnField.of(O.class, ORDERS, "status",
            String.class);
    private static final ColumnField<O, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(O.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<O, OrderEntity, Long> REFERRER_ID =
            ColumnField.of(O.class, ORDERS, "referrerId", Long.class);
    private static final ColumnField<I, PlainOrderItemEntity, Long> ITEM_ORDER_ID =
            ColumnField.of(I.class, PLAIN_ITEMS, "orderId", Long.class);
    private static final ColumnField<I, PlainOrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(I.class, PLAIN_ITEMS, "productCode", String.class);
    private static final ColumnField<I, PlainOrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(I.class, PLAIN_ITEMS, "quantity", Integer.class);

    private static final ModelQuery.Builder<OrderEntity, Long, O> ORDER_QUERY =
            ModelQuery.builder(ORDERS, row -> new O(row.get(ID), row.get(STATUS), row.get(TOTAL), row.get(REFERRER_ID)))
                    .select(SelectSet.of(ID, STATUS, TOTAL, REFERRER_ID))
                    .primaryKey(PrimaryKey.of(ID))
                    .orderBy(ID.asc());

    /** The items of product {@code P007}, over {@link PlainOrderItemEntity}, which has no association to orders. */
    private static final SubSelect<I, Long> P007_ITEMS =
            SubSelect.of(ITEM_ORDER_ID).where(f -> f.eq(ITEM_PRODUCT, "P007"));
    /** A sub-select over the outer root's own entity (R-FLT-15). */
    private static final SubSelect<O, Long> PAID_ORDERS = SubSelect.of(ID).where(f -> f.eq(STATUS, "PAID"));
    /** Selects a column with NULLs in it, so {@code notIn} over it must add its own {@code IS NOT NULL} (R-FLT-16). */
    private static final SubSelect<O, Long> REFERRER_IDS = SubSelect.of(REFERRER_ID);
    /** A sub-select whose where matches no row: an empty IN list renders FALSE (R-FLT-02). */
    private static final SubSelect<I, Long> NO_PRODUCT =
            SubSelect.of(ITEM_ORDER_ID).where(f -> f.in(ITEM_PRODUCT, List.of()));

    // ---- AC-FLT-12

    @TckTest
    void ac_flt_12_in_over_a_sub_select_returns_the_rows_the_sub_select_filters(TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-12-in-sub-select", ds -> withExecutor(ds, OrderEntity.class,
                executor -> {
                    results.add(ids(executor, f -> f.in(ID, P007_ITEMS)));
                    results.add(ids(executor, f -> f.in(ID, NO_PRODUCT)));
                    results.add(ids(executor, f -> f.in(ID, PAID_ORDERS)));
                }));

        List<Long> p007 = longs(db, "select o.id from OrderEntity o where o.id in "
                + "(select i.order.id from OrderItemEntity i where i.productCode = 'P007') order by o.id");
        assertThat(p007).isNotEmpty().hasSizeLessThan(TckFixture.ORDERS);
        assertThat(results.get(0)).isEqualTo(p007);
        assertThat(results.get(1)).isEmpty();
        assertThat(results.get(2))
                .isEqualTo(longs(db, "select o.id from OrderEntity o where o.status = 'PAID' order by o.id"))
                .isNotEmpty()
                .hasSizeLessThan(TckFixture.ORDERS);
    }

    // ---- AC-FLT-13

    @TckTest
    void ac_flt_13_not_in_over_a_sub_select_keeps_null_rows_and_is_never_emptied(TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        long[] nullReferrers = new long[1];
        SqlSnapshots.assertMatches(db, "flt-13-not-in-sub-select", ds -> withExecutor(ds, OrderEntity.class,
                executor -> {
                    results.add(ids(executor, f -> f.notIn(REFERRER_ID, PAID_ORDERS)));
                    results.add(ids(executor, f -> f.notIn(ID, REFERRER_IDS)));
                    results.add(ids(executor, f -> f.notIn(ID, NO_PRODUCT)));
                    nullReferrers[0] = executor.list(ORDER_QUERY.where(f -> f.isNull(REFERRER_ID)).build(),
                            Limit.unlimited()).size();
                }));

        // Rows whose own column is NULL are kept, as R-FLT-04 requires.
        List<Long> expectedPaid = longs(db, "select o.id from OrderEntity o where o.referrerId is null or "
                + "o.referrerId not in (select p.id from OrderEntity p where p.status = 'PAID') order by o.id");
        assertThat(nullReferrers[0]).isEqualTo(TckFixture.ORDERS - TckFixture.ORDERS / 3);
        assertThat(results.get(0)).isEqualTo(expectedPaid).hasSizeGreaterThan((int) nullReferrers[0]);

        // The sub-select's own NULL never empties the result: without its IS NOT NULL guard this set would be empty.
        List<Long> expectedReferrers = longs(db, "select o.id from OrderEntity o where o.id not in "
                + "(select p.referrerId from OrderEntity p where p.referrerId is not null) order by o.id");
        assertThat(results.get(1)).isEqualTo(expectedReferrers).isNotEmpty()
                .hasSizeGreaterThan(TckFixture.ORDERS / 2);

        // An empty sub-select keeps R-FLT-02's meaning: notIn matches every row.
        assertThat(results.get(2)).hasSize(TckFixture.ORDERS).containsExactlyElementsOf(ids(db));
    }

    // ---- AC-FLT-14

    @TckTest
    void ac_flt_14_exists_and_not_exists_correlate_a_root_with_no_association(TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-14-exists-sub-select", ds -> withExecutor(ds, OrderEntity.class,
                executor -> {
                    results.add(ids(executor, f -> f.exists(P007_ITEMS,
                            (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))));
                    results.add(ids(executor, f -> f.notExists(P007_ITEMS,
                            (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))));
                    // An or mixing an inner condition and a lifted one matches through either branch.
                    results.add(ids(executor, f -> f.exists(P007_ITEMS, (s, outer) -> s
                            .compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID))
                            .or(a -> a.eq(ITEM_QUANTITY, 9),
                                    b -> b.lt(outer.column(TOTAL), new BigDecimal("100.00"))))));
                }));

        List<Long> hasP007 = longs(db, "select distinct o.id from OrderEntity o join o.items i "
                + "where i.productCode = 'P007' order by o.id");
        List<Long> mixed = longs(db, "select distinct o.id from OrderEntity o join o.items i "
                + "where i.productCode = 'P007' and (i.quantity = 9 or o.total < 100.00) order by o.id");
        assertThat(hasP007).isNotEmpty().hasSizeLessThan(TckFixture.ORDERS);
        assertThat(results.get(0)).isEqualTo(hasP007);
        assertThat(results.get(1)).hasSize(TckFixture.ORDERS - hasP007.size()).doesNotContainAnyElementsOf(hasP007);
        assertThat(mixed).isNotEmpty().isSubsetOf(hasP007).hasSizeLessThan(hasP007.size());
        assertThat(results.get(2)).isEqualTo(mixed);
    }

    @TckTest
    void ac_flt_14_a_through_child_query_correlates_to_its_join(TckDatabase db) {
        // The child's own query correlates the lifted column to its through join, not to the outer label root.
        // Label 5's orders have no P007 item, so its child list comes back empty and is left out of the map.
        var plan = FetchPlan.of(QLabelOrders.ALL).child(QLabelOrders.ORDERS, FetchPlan.of(QOrderLines.ALL),
                c -> c.where(f -> f.exists(P007_ITEMS,
                                (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(QOrderLines.ID))))
                        .orderBy(QOrderLines.ID.asc()));
        var q = QLabelOrders.query().where(f -> f.in(QLabelOrders.ID, List.of(4L, 10L, 5L)))
                .orderBy(QLabelOrders.ID.asc()).fetch(plan).build();
        Map<Long, List<Long>> expected = grouped(db, "SELECT ol.label_id, o.id FROM order_labels ol "
                + "JOIN orders o ON o.id = ol.order_id WHERE ol.label_id IN (4, 10, 5) AND o.id IN "
                + "(SELECT order_id FROM order_items WHERE product_code = 'P007') ORDER BY o.id");

        var actual = new LinkedHashMap<Long, List<Long>>();
        SqlSnapshots.assertMatches(db, "flt-14-through-child-exists", ds -> withExecutor(ds, LabelEntity.class,
                executor -> {
                    for (LabelOrders label : executor.list(q, Limit.unlimited())) {
                        if (!label.orders().isEmpty()) {
                            actual.put(label.id(), label.orders().stream().map(OrderLines::id).toList());
                        }
                    }
                }));
        assertThat(expected).isNotEmpty();
        assertThat(actual).isEqualTo(expected);
    }

    // ---- AC-FLT-15

    record KeyView(String regionCode) {}

    @TckTest
    void ac_flt_15_an_embeddable_valued_column_in_a_sub_select_in_throws_mq1312(TckDatabase db) {
        TableField<EmbeddedKeyEntity, EmbeddedKeyEntity> root = TableField.root(EmbeddedKeyEntity.class);
        ColumnField<KeyView, EmbeddedKeyEntity, String> regionCode =
                ColumnField.of(KeyView.class, root, "key.regionCode", String.class);
        ColumnField<KeyView, EmbeddedKeyEntity, EmbeddedKeyEntity.Key> key =
                ColumnField.of(KeyView.class, root, "key", EmbeddedKeyEntity.Key.class);
        var query = ModelQuery.builder(root, row -> new KeyView(row.get(regionCode)))
                .select(SelectSet.of(regionCode));
        var sub = SubSelect.of(key);

        withExecutor(db, EmbeddedKeyEntity.class, executor -> {
            assertThatThrownBy(() -> executor.list(query.where(f -> f.in(key, sub)).build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1312))
                    .hasMessageContaining("MQ1312");
            assertThatThrownBy(() -> executor.list(query.where(f -> f.notIn(key, sub)).build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1312))
                    .hasMessageContaining("MQ1312");
        });
    }

    @TckTest
    void ac_flt_15_a_lifted_embeddable_valued_column_in_a_correlated_in_throws_mq1312(TckDatabase db) {
        TableField<EmbeddedKeyEntity, EmbeddedKeyEntity> root = TableField.root(EmbeddedKeyEntity.class);
        ColumnField<KeyView, EmbeddedKeyEntity, String> regionCode =
                ColumnField.of(KeyView.class, root, "key.regionCode", String.class);
        // Declared String so it type-matches regionCode, but its attribute is the embeddable key, so it is
        // embeddable-valued; the sub-select's own String column is not, so only the lifted side can catch it.
        ColumnField<KeyView, EmbeddedKeyEntity, String> keyAsString =
                ColumnField.of(KeyView.class, root, "key", String.class);
        var query = ModelQuery.builder(root, row -> new KeyView(row.get(regionCode)))
                .select(SelectSet.of(regionCode));
        var inner = SubSelect.of(regionCode);

        withExecutor(db, EmbeddedKeyEntity.class, executor -> {
            // The lifted outer column reads the embeddable key of the enclosing query's row (R-FLT-16, INV-6).
            assertThatThrownBy(() -> executor.list(query.where(f -> f.exists(inner, (s, outer) -> s
                    .in(outer.column(keyAsString), inner))).build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1312))
                    .hasMessageContaining("MQ1312");
        });
    }

    // ---- AC-FLT-16

    @TckTest
    void ac_flt_16_count_keyset_primary_key_first_and_export_visit_every_row_once(TckDatabase db) {
        UnaryOperator<Filters<O>> exists = f -> f.exists(P007_ITEMS,
                (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)));
        var viaIn = ORDER_QUERY.where(f -> f.in(ID, P007_ITEMS)).build();
        var viaExists = ORDER_QUERY.where(exists).build();
        List<Long> expected = longs(db, "select distinct o.id from OrderEntity o join o.items i "
                + "where i.productCode = 'P007' order by o.id");
        assertThat(expected).isNotEmpty();

        long[] counted = new long[2];
        List<Long> listed = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "flt-16-sub-select-count-and-list", ds -> withExecutor(ds,
                OrderEntity.class, executor -> {
                    counted[0] = executor.count(viaIn);
                    counted[1] = executor.count(viaExists);
                    listed.addAll(ids(executor, viaIn));
                    listed.addAll(ids(executor, viaExists));
                }));
        assertThat(counted[0]).isEqualTo(expected.size());
        assertThat(counted[1]).isEqualTo(expected.size());
        assertThat(listed).hasSize(2 * expected.size());
        assertThat(listed.subList(0, expected.size())).isEqualTo(expected);
        assertThat(listed.subList(expected.size(), listed.size())).isEqualTo(expected);
        // The sub-select joins nothing on the outer query, so no statement needs DISTINCT (R-FLT-12).
        assertThat(sql).allSatisfy(statement -> assertThat(statement).doesNotContainIgnoringCase("distinct"));

        var keyset = ORDER_QUERY.where(exists).keyset().build();
        var keysetIn = ORDER_QUERY.where(f -> f.in(ID, P007_ITEMS)).keyset().build();
        var pkFirst = ORDER_QUERY.where(exists).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();

        withExecutor(db, OrderEntity.class, executor -> {
            assertThat(pages(executor, keyset)).isEqualTo(expected);
            assertThat(pages(executor, keysetIn)).isEqualTo(expected);
            assertThat(exported(executor, viaExists)).isEqualTo(expected);
            assertThat(exported(executor, pkFirst)).isEqualTo(expected);
        });
    }

    /** Reads {@code q} through keyset pages of 30 rows, joining the pages. */
    private static List<Long> pages(ModelQueryExecutor<OrderEntity> executor, ModelQuery<OrderEntity, Long, O> q) {
        return pages(executor, q, 30);
    }

    private static List<Long> pages(ModelQueryExecutor<OrderEntity> executor, ModelQuery<OrderEntity, Long, O> q,
            int size) {
        var ids = new ArrayList<Long>();
        KeysetSlice<O> slice = executor.page(q, KeysetSpec.first(size));
        slice.content().forEach(row -> ids.add(row.id()));
        while (slice.hasNext()) {
            slice = executor.page(q, KeysetSpec.after(slice.nextCursor().orElseThrow(), size));
            slice.content().forEach(row -> ids.add(row.id()));
        }
        return ids;
    }

    /** Exports {@code q} in pages of 25 rows, which must visit each matching row exactly once. */
    private static List<Long> exported(ModelQueryExecutor<OrderEntity> executor,
            ModelQuery<OrderEntity, Long, O> q) {
        var ids = new ArrayList<Long>();
        executor.export(q, ExportOptions.of(25), page -> page, row -> ids.add(row.id()));
        assertThat(ids).doesNotHaveDuplicates();
        return ids;
    }

    // ---- helpers

    private static List<Long> ids(ModelQueryExecutor<OrderEntity> executor, UnaryOperator<Filters<O>> where) {
        return ids(executor, ORDER_QUERY.where(where).build());
    }

    private static List<Long> ids(ModelQueryExecutor<OrderEntity> executor, ModelQuery<OrderEntity, Long, O> q) {
        return executor.list(q, Limit.unlimited()).stream().map(O::id).toList();
    }

    private static List<Long> ids(TckDatabase db) {
        return longs(db, "select o.id from OrderEntity o order by o.id");
    }

    private static List<Long> longs(TckDatabase db, String jpql) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            var ids = new ArrayList<Long>();
            sf.inSession(em -> ids.addAll(em.createQuery(jpql, Long.class).getResultList()));
            return ids;
        }
    }

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }

    /** The second column of {@code sql}'s rows grouped by the first, each group in the order the rows come. */
    private static Map<Long, List<Long>> grouped(TckDatabase db, String sql) {
        var result = new LinkedHashMap<Long, List<Long>>();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.computeIfAbsent(rows.getLong(1), key -> new ArrayList<>()).add(rows.getLong(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return result;
    }
}
