package com.rey.modelquery.tck.pag;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** Grouped {@code export}: group keys close the order and dedupe pages; no primary key is needed (engine/21 §5). */
class GroupedExportTest {

    /** One group of order items; a column the query does not select stays {@code null}. */
    record Group(Long orderId, String product, Integer quantity, Long count) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final TableField<OrderItemEntity, OrderEntity> ITEM_ORDER = TableField.join(ITEMS, "order", INNER);

    private static final ColumnField<Group, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(Group.class, ITEMS, "id", Long.class);
    private static final ColumnField<Group, OrderEntity, Long> ORDER_ID =
            ColumnField.of(Group.class, ITEM_ORDER, "id", Long.class);
    private static final ColumnField<Group, OrderItemEntity, String> PRODUCT =
            ColumnField.of(Group.class, ITEMS, "productCode", String.class);
    private static final ColumnField<Group, OrderItemEntity, Integer> QUANTITY =
            ColumnField.of(Group.class, ITEMS, "quantity", Integer.class);
    private static final AggregateField<Group, Long> COUNT = Agg.count(ITEMS);

    private static final ModelQuery.Builder<OrderItemEntity, Object, Group> GROUPS =
            ModelQuery.builder(ITEMS, GroupedExportTest::group);

    /** Every order holds exactly four items, so ordering its 5 000 groups by their count ties them all. */
    private static final ModelQuery<OrderItemEntity, Object, Group> PER_ORDER_BY_COUNT = GROUPS
            .columns(ColumnSet.of(ORDER_ID, COUNT))
            .groupBy(ORDER_ID)
            .orderBy(COUNT.desc())
            .build();

    /** 450 groups, nine per product: the product order ties nine groups, and only the quantity breaks the tie. */
    private static final ModelQuery.Builder<OrderItemEntity, Object, Group> PER_PRODUCT_AND_QUANTITY = GROUPS
            .columns(ColumnSet.of(PRODUCT, QUANTITY, COUNT))
            .groupBy(PRODUCT, QUANTITY)
            .orderBy(PRODUCT.asc());

    private static final int PRODUCT_QUANTITY_GROUPS = 450;

    private static Group group(Row row) {
        return new Group(row.get(ORDER_ID), row.get(PRODUCT), row.get(QUANTITY), row.get(COUNT));
    }

    // ---- AC-PAG-10

    @TckTest
    void ac_pag_10_grouped_offset_export_over_20_000_rows_visits_every_group_exactly_once(TckDatabase db) {
        // Every group shares its order key with the other 4 999, so the appended group key alone makes the order
        // unique, and the groups come in its ascending order. 1 000 divides the group count and 333 does not.
        for (int pageSize : new int[] {333, 1_000}) {
            List<Group> groups = new ArrayList<>();
            withExecutor(db, OrderItemEntity.class, executor -> assertThat(
                    executor.export(PER_ORDER_BY_COUNT, ExportOptions.of(pageSize), page -> page, groups::add))
                    .isEqualTo(TckFixture.ORDERS));
            assertThat(groups).extracting(Group::orderId).as("page size %d", pageSize)
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, TckFixture.ORDERS).boxed().toList());
            assertThat(groups).extracting(Group::count).containsOnly(4L);
            assertThat(groups.stream().mapToLong(Group::count).sum()).isEqualTo(TckFixture.ORDER_ITEMS);
        }
    }

    @TckTest
    void ac_pag_10_the_export_appends_the_group_keys_missing_from_the_callers_order(TckDatabase db) {
        // Five pages of 100 over 450 groups; the SQL shows quantity appended after the caller's product order.
        List<Group> expected = new ArrayList<>();
        inSession(db, em -> em.createQuery("select i.productCode, i.quantity, count(i) from OrderItemEntity i"
                        + " group by i.productCode, i.quantity", Object[].class).getResultList()
                .forEach(r -> expected.add(new Group(null, (String) r[0], (Integer) r[1], (Long) r[2]))));
        assertThat(expected).hasSize(PRODUCT_QUANTITY_GROUPS);
        List<Group> groups = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-10-grouped-stable-order", ds -> withExecutor(ds,
                OrderItemEntity.class, executor -> executor.export(PER_PRODUCT_AND_QUANTITY.build(),
                        ExportOptions.of(100), page -> page, groups::add)));
        assertThat(sql).hasSize(5);
        assertThat(groups).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(groups).isSortedAccordingTo(
                Comparator.comparing(Group::product).thenComparing(Group::quantity));
    }

    @TckTest
    void ac_pag_10_a_group_shifted_across_a_page_boundary_is_exported_once(TckDatabase db) {
        // After each page, an item of a new group that sorts before every other one is inserted. Every later group
        // moves one place on, so the next page starts with the last group of the page just read: without the
        // group-key dedupe, 9 groups would be exported twice (R-PAG-11). The export runs in a transaction that sees
        // its own inserts on every vendor, and rolls back to leave the fixture as seeded.
        var q = PER_PRODUCT_AND_QUANTITY.build();
        List<Group> groups = new ArrayList<>();
        AtomicInteger inserted = new AtomicInteger();
        long[] passed = new long[1];
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    ModelQueryExecutor<OrderItemEntity> executor =
                            ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults());
                    passed[0] = executor.export(q, ExportOptions.of(50), page -> {
                        int n = inserted.incrementAndGet();
                        em.createNativeQuery("INSERT INTO order_items (id, order_id, product_code, quantity, "
                                        + "unit_price) VALUES (:id, 1, 'A000', :quantity, 1.00)")
                                .setParameter("id", 1_000_000L + n)
                                .setParameter("quantity", n)
                                .executeUpdate();
                        return page;
                    }, groups::add);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
        // The 9 inserted groups push 9 groups past the ninth page, into a tenth.
        assertThat(inserted).hasValue(PRODUCT_QUANTITY_GROUPS / 50 + 1);
        assertThat(passed[0]).isEqualTo(PRODUCT_QUANTITY_GROUPS);
        assertThat(groups).hasSize(PRODUCT_QUANTITY_GROUPS).doesNotHaveDuplicates()
                .allMatch(g -> g.product().startsWith("P"));
    }

    // ---- AC-PAG-11

    @TckTest
    void ac_pag_11_a_grouped_export_returns_the_count_passed_to_sink_when_page_transformer_filters(TckDatabase db) {
        // The transformer keeps one group in ten: 5 000 groups are read, 500 reach the sink.
        List<Integer> pageSizes = new ArrayList<>();
        List<Long> sunk = new ArrayList<>();
        withExecutor(db, OrderItemEntity.class, executor -> assertThat(executor.export(PER_ORDER_BY_COUNT,
                ExportOptions.of(1_000), page -> {
                    pageSizes.add(page.size());
                    return page.stream().map(Group::orderId).filter(id -> id % 10 == 0).toList();
                }, sunk::add)).isEqualTo(TckFixture.ORDERS / 10));
        assertThat(pageSizes).containsExactly(1_000, 1_000, 1_000, 1_000, 1_000);
        assertThat(sunk).hasSize(TckFixture.ORDERS / 10).doesNotHaveDuplicates().allMatch(id -> id % 10 == 0);
    }

    // ---- AC-PAG-12

    /** A group of orders, keyed and summed through the orders' to-many {@code items} join. */
    record OrderGroup(Long orderId, String product, Long quantity) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS = TableField.join(ORDERS, "items", INNER);
    private static final ColumnField<OrderGroup, OrderEntity, Long> O_ID =
            ColumnField.of(OrderGroup.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderGroup, OrderItemEntity, String> O_ITEM_PRODUCT =
            ColumnField.of(OrderGroup.class, ORDER_ITEMS, "productCode", String.class);
    private static final ColumnField<OrderGroup, OrderItemEntity, Integer> O_ITEM_QUANTITY =
            ColumnField.of(OrderGroup.class, ORDER_ITEMS, "quantity", Integer.class);
    private static final AggregateField<OrderGroup, Long> O_QUANTITY = Agg.sumAsLong(O_ITEM_QUANTITY);

    @TckTest
    void ac_pag_12_a_grouped_export_through_a_to_many_join_visits_every_group_exactly_once(TckDatabase db) {
        // Summed through the join per order (5 000 groups, pages of 700), and keyed through it per product (50
        // groups, pages of 7): a group-key tuple is unique per result row even through a to-many join, so neither is
        // refused, and the appended group key orders groups whose sums tie (R-PAG-11, R-PAG-13).
        var perOrder = ModelQuery.builder(ORDERS, GroupedExportTest::orderGroup)
                .columns(ColumnSet.of(O_ID, O_QUANTITY))
                .groupBy(O_ID)
                .orderBy(O_QUANTITY.desc())
                .build();
        var perProduct = ModelQuery.builder(ORDERS, GroupedExportTest::orderGroup)
                .columns(ColumnSet.of(O_ITEM_PRODUCT, O_QUANTITY))
                .groupBy(O_ITEM_PRODUCT)
                .orderBy(O_QUANTITY.desc())
                .build();
        List<OrderGroup> expectedPerOrder = new ArrayList<>();
        List<OrderGroup> expectedPerProduct = new ArrayList<>();
        inSession(db, em -> {
            em.createQuery("select o.id, sum(i.quantity) from OrderEntity o join o.items i group by o.id",
                    Object[].class).getResultList().forEach(r -> expectedPerOrder.add(
                            new OrderGroup((Long) r[0], null, ((Number) r[1]).longValue())));
            em.createQuery("select i.productCode, sum(i.quantity) from OrderEntity o join o.items i"
                    + " group by i.productCode", Object[].class).getResultList().forEach(r -> expectedPerProduct.add(
                            new OrderGroup(null, (String) r[0], ((Number) r[1]).longValue())));
        });
        assertThat(expectedPerOrder).hasSize(TckFixture.ORDERS);
        assertThat(expectedPerProduct).hasSize(50);
        List<OrderGroup> orders = new ArrayList<>();
        List<OrderGroup> products = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-12-grouped-to-many", ds -> withExecutor(ds,
                OrderEntity.class, executor -> {
                    assertThat(executor.export(perOrder, ExportOptions.of(700), page -> page, orders::add))
                            .isEqualTo(TckFixture.ORDERS);
                    assertThat(executor.export(perProduct, ExportOptions.of(7), page -> page, products::add))
                            .isEqualTo(50);
                }));
        assertThat(sql).hasSize(16);
        assertThat(orders).containsExactlyInAnyOrderElementsOf(expectedPerOrder);
        assertThat(products).containsExactlyInAnyOrderElementsOf(expectedPerProduct);
        Comparator<OrderGroup> bySumDesc = Comparator.comparing(OrderGroup::quantity).reversed();
        assertThat(orders).isSortedAccordingTo(bySumDesc.thenComparing(OrderGroup::orderId));
        assertThat(products).isSortedAccordingTo(bySumDesc.thenComparing(OrderGroup::product));
    }

    private static OrderGroup orderGroup(Row row) {
        return new OrderGroup(row.get(O_ID), row.get(O_ITEM_PRODUCT), row.get(O_QUANTITY));
    }

    // ---- AC-AGG-09 (the build-time half is AggregatesTest)

    @TckTest
    void ac_agg_09_a_grouped_query_with_no_primary_key_exports_and_one_that_is_set_is_ignored(TckDatabase db) {
        // 50 groups in pages of 20: both exports run the same three statements, neither selecting the item id.
        var perProduct = GROUPS.columns(ColumnSet.of(PRODUCT, COUNT)).groupBy(PRODUCT).orderBy(COUNT.desc());
        var keyless = perProduct.build();
        var keyed = perProduct.primaryKey(PrimaryKey.of(ITEM_ID)).build();
        List<List<Group>> exports = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "agg-09-grouped-export-without-primary-key",
                ds -> withExecutor(ds, OrderItemEntity.class, executor -> {
                    for (ModelQuery<OrderItemEntity, ?, Group> q : List.of(keyless, keyed)) {
                        List<Group> groups = new ArrayList<>();
                        assertThat(executor.export(q, ExportOptions.of(20), page -> page, groups::add))
                                .isEqualTo(50);
                        exports.add(groups);
                    }
                }));
        assertThat(sql).hasSize(6);
        assertThat(sql.subList(0, 3)).isEqualTo(sql.subList(3, 6));
        assertThat(exports.get(0)).extracting(Group::product).doesNotHaveDuplicates().hasSize(50)
                .allMatch(product -> product.matches("P0[0-4][0-9]"));
        assertThat(exports.get(0)).extracting(Group::count).containsOnly((long) TckFixture.ORDER_ITEMS / 50);
        assertThat(exports.get(1)).isEqualTo(exports.get(0));
    }

    @TckTest
    void ac_agg_09_a_whole_table_aggregate_exports_its_one_group(TckDatabase db) {
        // A page of one is full, so a second, empty page ends the export; the one group is not repeated.
        var total = GROUPS.columns(ColumnSet.of(COUNT)).build();
        List<Group> groups = new ArrayList<>();
        withExecutor(db, OrderItemEntity.class, executor -> assertThat(
                executor.export(total, ExportOptions.of(1), page -> page, groups::add)).isEqualTo(1));
        assertThat(groups).containsExactly(new Group(null, null, null, (long) TckFixture.ORDER_ITEMS));
    }

    // ---- support

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        inSession(db, em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }
}
