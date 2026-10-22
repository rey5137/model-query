package com.rey.modelquery.tck.exe;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.InlineValueHandling;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * A filter through a to-many join that nothing else in the statement keeps renders as one {@code EXISTS} over a second
 * root, correlated by entity equality, so each root comes once (R-EXE-16): AC-EXE-22's reads and pages, AC-EXE-23's
 * boolean shapes and AC-EXE-24's grouped sums, against the rows {@code Filters.exists} with the same predicate returns.
 */
class SemiJoinTest {

    record OrderRow(Long id, String status) {}

    record ItemRow(Long orderId, String product) {}

    record NodeRow(Integer tenant, Long id) {}

    record StatusTotal(String status, BigDecimal total) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);

    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderItemEntity, String> ITEM_CODE =
            ColumnField.of(OrderRow.class, ITEMS, "productCode", String.class);
    private static final ColumnField<OrderRow, OrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(OrderRow.class, ITEMS, "quantity", Integer.class);
    private static final ColumnField<OrderRow, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(OrderRow.class, CUSTOMER, "name", String.class);

    private static final ColumnField<ItemRow, OrderEntity, Long> ITEM_ORDER_ID =
            ColumnField.of(ItemRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(ItemRow.class, ITEMS, "productCode", String.class);

    private static final ColumnField<StatusTotal, OrderEntity, String> GROUP_STATUS =
            ColumnField.of(StatusTotal.class, ORDERS, "status", String.class);
    private static final ColumnField<StatusTotal, OrderEntity, Long> GROUP_ORDER_ID =
            ColumnField.of(StatusTotal.class, ORDERS, "id", Long.class);
    private static final ColumnField<StatusTotal, OrderItemEntity, String> GROUP_ITEM_CODE =
            ColumnField.of(StatusTotal.class, ITEMS, "productCode", String.class);
    private static final AggregateField<StatusTotal, BigDecimal> GROUP_TOTAL =
            Agg.sum(ColumnField.of(StatusTotal.class, ORDERS, "total", BigDecimal.class));

    private static final TableField<EmbeddedNodeEntity, EmbeddedNodeEntity> NODES =
            TableField.root(EmbeddedNodeEntity.class);
    private static final TableField<EmbeddedNodeEntity, EmbeddedNodeEntity> CHILDREN =
            TableField.join(NODES, "children", INNER);
    private static final ColumnField<NodeRow, EmbeddedNodeEntity, Integer> NODE_TENANT =
            ColumnField.of(NodeRow.class, NODES, "key.tenantId", Integer.class);
    private static final ColumnField<NodeRow, EmbeddedNodeEntity, Long> NODE_ID =
            ColumnField.of(NodeRow.class, NODES, "key.id", Long.class);
    private static final ColumnField<NodeRow, EmbeddedNodeEntity, String> CHILD_NAME =
            ColumnField.of(NodeRow.class, CHILDREN, "name", String.class);

    /** Orders up to this id: each holds four items of one product, so a join on the product repeats it four times. */
    private static final long LAST_ORDER = 200;

    /** The orders up to {@link #LAST_ORDER} that {@code where} keeps, by id. */
    private static ModelQuery.Builder<OrderEntity, Long, OrderRow> orderRows(UnaryOperator<Filters<OrderRow>> where) {
        return ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS)))
                .select(SelectSet.of(ID, STATUS))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(ID.asc())
                .where(f -> where.apply(f.lt(ID, LAST_ORDER + 1)));
    }

    private static ModelQuery<OrderEntity, Long, OrderRow> orders(UnaryOperator<Filters<OrderRow>> where) {
        return orderRows(where).build();
    }

    // ---- AC-EXE-22

    @TckTest
    void ac_exe_22_list_and_count_return_each_root_once_through_one_exists(TckDatabase db) {
        var p001 = orders(f -> f.eq(ITEM_CODE, "P001"));
        var reads = new ArrayList<Object>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-22-semi-join", ds -> withExecutor(ds, executor -> {
            reads.add(executor.list(p001, Limit.unlimited()));
            reads.add(executor.count(p001));
            reads.add(executor.one(orders(f -> f.eq(ID, 1L).eq(ITEM_CODE, "P001"))));
        }));
        assertThat(sql).hasSize(3).allSatisfy(SemiJoinTest::assertSemiJoin);
        assertThat(sql.get(1)).doesNotContain("distinct");

        @SuppressWarnings("unchecked")
        List<OrderRow> listed = (List<OrderRow>) reads.get(0);
        List<Long> expected = ids(db, "select o.id from OrderEntity o where o.id <= " + LAST_ORDER
                + " and exists (select 1 from OrderItemEntity i where i.order = o and i.productCode = 'P001')"
                + " order by o.id");
        assertThat(listed).extracting(OrderRow::id).containsExactlyElementsOf(expected);
        assertThat(expected).isNotEmpty().doesNotHaveDuplicates();
        assertThat(reads.get(1)).isEqualTo((long) listed.size());
        assertThat(reads.get(2)).isEqualTo(Optional.of(new OrderRow(1L, listed.get(0).status())));
        // The join this replaces does repeat the roots.
        assertThat(ids(db, "select o.id from OrderEntity o join o.items i where o.id <= " + LAST_ORDER
                + " and i.productCode = 'P001'")).hasSizeGreaterThan(expected.size());
    }

    @TckTest
    void ac_exe_22_a_locked_list_and_one_run_since_the_statement_has_no_join(TckDatabase db) {
        var locked = orders(f -> f.eq(ITEM_CODE, "P001")).withLock(RowLock.exclusive());
        var lockedOne = orders(f -> f.eq(ID, 1L).eq(ITEM_CODE, "P001")).withLock(RowLock.exclusive());
        // A term through a to-one join is no root term, so it moves into the sub-query beside the to-many one (D-181).
        var besideToOne = orders(f -> f.eq(ITEM_CODE, "P001").isNotNull(CUSTOMER_NAME)).withLock(RowLock.exclusive());
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sessions = JoinTestSupport.sessionFactory(ds)) {
                sessions.inTransaction(em -> {
                    ModelQueryExecutor<OrderEntity> executor =
                            ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                    assertThat(executor.list(locked, Limit.unlimited())).extracting(OrderRow::id)
                            .doesNotHaveDuplicates().isNotEmpty();
                    assertThat(executor.one(lockedOne)).map(OrderRow::id).contains(1L);
                    assertThat(executor.list(besideToOne, Limit.unlimited())).extracting(OrderRow::id)
                            .doesNotHaveDuplicates().isNotEmpty();
                });
            }
        });
        assertThat(sql).hasSize(3).allSatisfy(SemiJoinTest::assertSemiJoin);
        // The root term stays on the statement, before the exists; the to-one join is made inside it only.
        assertThat(sql.get(2).toLowerCase(Locale.ROOT)).containsPattern("where \\w+\\.id<\\? and exists")
                .containsPattern("exists ?\\(select 1 from orders \\w+ join .*customers");
    }

    @TckTest
    void ac_exe_22_an_embedded_id_correlates_by_entity_equality(TckDatabase db) {
        var withChildren = ModelQuery.builder(NODES, row -> new NodeRow(row.get(NODE_TENANT), row.get(NODE_ID)))
                .select(SelectSet.of(NODE_TENANT, NODE_ID))
                .primaryKey(PrimaryKey.composite(NODE_TENANT, NODE_ID))
                .orderBy(NODE_ID.asc())
                .where(f -> f.in(CHILD_NAME, List.of("node-4", "node-5", "node-6")))
                .build();
        int tenant = TckFixture.COMPOSITE_TREE_TENANT;
        var reads = new ArrayList<Object>();
        var pages = new ArrayList<Slice<NodeRow>>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-22-embedded-id", ds -> {
            try (SessionFactory sessions = JoinTestSupport.sessionFactory(ds, EmbeddedNodeEntity.class)) {
                sessions.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, EmbeddedNodeEntity.class,
                            ModelQueryConfig.defaults());
                    reads.add(executor.list(withChildren, Limit.unlimited()));
                    reads.add(executor.count(withChildren));
                    reads.add(executor.one(withChildren, List.<Object>of(tenant, 2L)));
                    pages.add(executor.page(withChildren, PageSpec.of(0, 1), CountMode.COUNT));
                });
                sessions.inTransaction(em -> reads.add(ModelQueryExecutor.create(em, EmbeddedNodeEntity.class,
                        ModelQueryConfig.defaults()).list(withChildren.withLock(RowLock.exclusive()),
                        Limit.unlimited())));
            }
        });
        // list, count, one by key, the page and its count, the locked list.
        assertThat(sql).hasSize(6).allSatisfy(SemiJoinTest::assertSemiJoin);
        // Node 2 has children 4 and 5, node 3 has child 6: the join would return node 2 twice.
        List<NodeRow> both = List.of(new NodeRow(tenant, 2L), new NodeRow(tenant, 3L));
        assertThat(reads).containsExactly(both, 2L, Optional.of(both.get(0)), both);
        assertThat(pages).singleElement().satisfies(page -> {
            assertThat(page.content()).containsExactly(both.get(0));
            assertThat(page.total()).hasValue(2);
            assertThat(page.hasNext()).isTrue();
        });
    }

    @TckTest
    void ac_exe_22_ac_flt_35_under_inline_value_handling_a_semi_join_binds_its_filter_values(TckDatabase db) {
        var semiJoin = orders(f -> f.eq(ITEM_CODE, "P001").gt(ITEM_QUANTITY, 7));
        var defaults = new ArrayList<OrderRow>();
        withExecutor(db, executor -> defaults.addAll(executor.list(semiJoin, Limit.unlimited())));
        assertThat(defaults).isNotEmpty();
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            inline.watch("P001", 7, LAST_ORDER + 1);
            inline.factory().inSession(em -> assertThat(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults()).list(semiJoin, Limit.unlimited())).isEqualTo(defaults));
            assertThat(inline.statements()).singleElement()
                    .satisfies(statement -> assertThat(statement).contains("exists").contains("?"));
        }
    }

    @TckTest
    void ac_exe_22_offset_pages_keyset_pages_and_export_pages_hold_each_root_once_and_come_back_full(TckDatabase db) {
        // The join this replaces read each root four times, so a page of 7 rows held two roots, and a keyset or
        // export page came back short once its repeats were dropped (R-PAG-02).
        int size = 7;
        List<String> products = List.of("P001", "P002", "P003", "P004", "P005", "P006");
        var holding = orderRows(f -> f.in(ITEM_CODE, products));
        var offset = holding.build();
        var keyset = holding.keyset().build();
        List<Long> expected = ids(db, "select o.id from OrderEntity o where o.id <= " + LAST_ORDER
                + " and exists (select 1 from OrderItemEntity i where i.order = o and i.productCode in ('"
                + String.join("', '", products) + "')) order by o.id");
        List<Long> paged = new ArrayList<>();
        List<Long> walked = new ArrayList<>();
        List<Long> exported = new ArrayList<>();
        List<Long> keysetExported = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        List<Integer> walkSizes = new ArrayList<>();
        List<Integer> exportSizes = new ArrayList<>();
        List<Integer> keysetExportSizes = new ArrayList<>();
        withExecutor(db, executor -> {
            Slice<OrderRow> page;
            int number = 0;
            do {
                page = executor.page(offset, PageSpec.of(number++, size), CountMode.COUNT);
                assertThat(page.total()).hasValue(expected.size());
                page.content().forEach(row -> paged.add(row.id()));
                pageSizes.add(page.content().size());
            } while (page.hasNext());
            KeysetSlice<OrderRow> keysetPage = executor.page(keyset, KeysetSpec.first(size));
            while (true) {
                keysetPage.content().forEach(row -> walked.add(row.id()));
                walkSizes.add(keysetPage.content().size());
                if (!keysetPage.hasNext()) {
                    break;
                }
                keysetPage = executor.page(keyset, KeysetSpec.after(keysetPage.nextCursor().orElseThrow(), size));
            }
            executor.export(offset, ExportOptions.of(size), sizing(exportSizes), row -> exported.add(row.id()));
            executor.export(keyset, ExportOptions.of(size), sizing(keysetExportSizes),
                    row -> keysetExported.add(row.id()));
        });
        assertThat(expected).hasSizeGreaterThan(2 * size);
        assertThat(List.of(paged, walked, exported, keysetExported))
                .allSatisfy(ids -> assertThat(ids).isEqualTo(expected));
        // Every page but the last is full.
        assertThat(List.of(pageSizes, walkSizes, exportSizes, keysetExportSizes))
                .allSatisfy(sizes -> assertThat(sizes.subList(0, sizes.size() - 1)).containsOnly(size));
    }

    // ---- AC-EXE-23

    @TckTest
    void ac_exe_23_or_same_child_not_and_nested_exists_return_the_rows_filters_exists_returns(TckDatabase db) {
        List<UnaryOperator<Filters<OrderRow>>> joined = List.of(
                f -> f.or(g -> g.eq(ITEM_CODE, "P001"), g -> g.eq(STATUS, "NEW")),
                f -> f.eq(ITEM_CODE, "P001").eq(ITEM_QUANTITY, 2),
                f -> f.not(g -> g.eq(ITEM_CODE, "P001")),
                f -> f.eq(ITEM_CODE, "P002").exists(ITEMS, g -> g.gt(ITEM_QUANTITY, 5)));
        List<UnaryOperator<Filters<OrderRow>>> existing = List.of(
                f -> f.or(g -> g.exists(ITEMS, h -> h.eq(ITEM_CODE, "P001")), g -> g.eq(STATUS, "NEW")),
                f -> f.exists(ITEMS, g -> g.eq(ITEM_CODE, "P001").eq(ITEM_QUANTITY, 2)),
                f -> f.exists(ITEMS, g -> g.not(h -> h.eq(ITEM_CODE, "P001"))),
                f -> f.exists(ITEMS, g -> g.eq(ITEM_CODE, "P002")).exists(ITEMS, g -> g.gt(ITEM_QUANTITY, 5)));
        var rows = new ArrayList<List<OrderRow>>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-23-boolean-shapes", ds -> withExecutor(ds,
                executor -> joined.forEach(where -> rows.add(executor.list(orders(where), Limit.unlimited())))));
        assertThat(sql).hasSize(joined.size()).allSatisfy(SemiJoinTest::assertOuterSemiJoin);
        // Only the nested exists(...) adds one, inside the semi-join's sub-query.
        assertThat(sql).extracting(statement -> statement.split("exists", -1).length - 1).containsExactly(1, 1, 1, 2);
        withExecutor(db, executor -> {
            for (int i = 0; i < existing.size(); i++) {
                List<OrderRow> expected = executor.list(orders(existing.get(i)), Limit.unlimited());
                assertThat(expected).as("shape %s", i).isNotEmpty();
                assertThat(rows.get(i)).as("shape %s", i).containsExactlyElementsOf(expected);
            }
        });
    }

    @TckTest
    void ac_exe_23_a_statement_selecting_through_the_to_many_join_renders_and_returns_as_before(TckDatabase db) {
        var itemRows = ModelQuery.builder(ORDERS, row -> new ItemRow(row.get(ITEM_ORDER_ID), row.get(ITEM_PRODUCT)))
                .select(SelectSet.of(ITEM_ORDER_ID, ITEM_PRODUCT))
                .where(f -> f.eq(ITEM_PRODUCT, "P001").lt(ITEM_ORDER_ID, LAST_ORDER + 1))
                .build();
        var rows = new ArrayList<ItemRow>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds,
                executor -> rows.addAll(executor.list(itemRows, Limit.unlimited()))));
        assertThat(sql).singleElement().satisfies(statement -> assertThat(statement.toLowerCase(Locale.ROOT))
                .contains(" join ").doesNotContain("exists"));
        // One row per matching item, as the join returns them.
        assertThat(rows).hasSize(ids(db, "select o.id from OrderEntity o join o.items i where o.id <= "
                + LAST_ORDER + " and i.productCode = 'P001'").size());
    }

    // ---- AC-EXE-24

    @TckTest
    void ac_exe_24_a_grouped_sum_over_a_filter_only_join_sums_each_root_once(TckDatabase db) {
        var byStatus = ModelQuery.builder(ORDERS, row -> new StatusTotal(row.get(GROUP_STATUS), row.get(GROUP_TOTAL)))
                .select(SelectSet.of(GROUP_STATUS, GROUP_TOTAL))
                .groupBy(GROUP_STATUS)
                .orderBy(GROUP_STATUS.asc())
                .where(f -> f.lte(GROUP_ORDER_ID, LAST_ORDER).eq(GROUP_ITEM_CODE, "P001"))
                .build();
        var sums = new ArrayList<StatusTotal>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds,
                executor -> sums.addAll(executor.list(byStatus, Limit.unlimited()))));
        assertThat(sql).singleElement().satisfies(SemiJoinTest::assertSemiJoin);
        String roots = "from OrderEntity o where o.id <= " + LAST_ORDER
                + " and exists (select 1 from OrderItemEntity i where i.order = o and i.productCode = 'P001')";
        List<String> expected = sums(db, "select o.status, sum(o.total) " + roots + " group by o.status"
                + " order by o.status");
        assertThat(expected).isNotEmpty();
        assertThat(sums).extracting(sum -> sum.status() + "=" + plain(sum.total()))
                .containsExactlyElementsOf(expected);
        // The join this replaces sums each root once per matching item.
        assertThat(sums(db, "select o.status, sum(o.total) from OrderEntity o join o.items i where o.id <= "
                + LAST_ORDER + " and i.productCode = 'P001' group by o.status order by o.status"))
                .doesNotContainAnyElementsOf(expected);
    }

    @TckTest
    void ac_exe_24_ac_exe_20_a_grouped_filter_only_join_counts_its_groups_the_same_in_every_count_mode(
            TckDatabase db) {
        var byStatus = ModelQuery.builder(ORDERS, row -> new StatusTotal(row.get(GROUP_STATUS), row.get(GROUP_TOTAL)))
                .select(SelectSet.of(GROUP_STATUS, GROUP_TOTAL))
                .groupBy(GROUP_STATUS)
                .orderBy(GROUP_STATUS.asc())
                .where(f -> f.lte(GROUP_ORDER_ID, LAST_ORDER).eq(GROUP_ITEM_CODE, "P001"))
                .build();
        // A group count is the same over a plain join, so the count statement itself must carry the semi-join.
        var counts = new ArrayList<Long>();
        List<String> countSql = SqlSnapshots.capture(db, ds -> withExecutor(ds,
                executor -> counts.add(executor.count(byStatus))));
        assertThat(countSql).singleElement().satisfies(SemiJoinTest::assertSemiJoin);
        withExecutor(db, executor -> {
            int groups = executor.list(byStatus, Limit.unlimited()).size();
            assertThat(groups).isPositive();
            // The count's derived table groups the same semi-joined rows the list does.
            assertThat(counts).singleElement().isEqualTo((long) groups);
            Slice<StatusTotal> counted = executor.page(byStatus, PageSpec.of(0, 50), CountMode.COUNT);
            Slice<StatusTotal> inline = executor.page(byStatus, PageSpec.of(0, 50), CountMode.INLINE_COUNT);
            assertThat(counted.total()).hasValue(groups);
            assertThat(counted.content()).hasSize(groups);
            assertThat(inline).isEqualTo(counted);
        });
    }

    /** {@code jpql}'s status and sum pairs, as {@code status=sum}. */
    private static List<String> sums(TckDatabase db, String jpql) {
        var sums = new ArrayList<String>();
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(db)) {
            sessions.inSession(em -> em.createQuery(jpql, Object[].class).getResultList()
                    .forEach(row -> sums.add(row[0] + "=" + plain((BigDecimal) row[1]))));
        }
        return sums;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** A page transformer that records each page's size in {@code sizes} and passes the page on. */
    private static UnaryOperator<List<OrderRow>> sizing(List<Integer> sizes) {
        return page -> {
            sizes.add(page.size());
            return page;
        };
    }

    /** One {@code exists} in {@code statement}, and no join outside it. */
    private static void assertSemiJoin(String statement) {
        assertOuterSemiJoin(statement);
        String sql = statement.toLowerCase(Locale.ROOT);
        assertThat(sql.indexOf("exists", sql.indexOf("exists") + 1)).as("one exists in %s", statement).isNegative();
    }

    /** An {@code exists} in {@code statement}, and no join before the first. */
    private static void assertOuterSemiJoin(String statement) {
        String sql = statement.toLowerCase(Locale.ROOT);
        int exists = sql.indexOf("exists");
        assertThat(exists).as("an exists in %s", statement).isPositive();
        assertThat(sql.substring(0, exists)).as("no outer join in %s", statement).doesNotContain(" join ");
    }

    private static List<Long> ids(TckDatabase db, String jpql) {
        var ids = new ArrayList<Long>();
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(db)) {
            sessions.inSession(em -> ids.addAll(em.createQuery(jpql, Long.class).getResultList()));
        }
        return ids;
    }

    private static void withExecutor(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(db)) {
            sessions.inSession(em ->
                    work.accept(ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(ds)) {
            sessions.inSession(em ->
                    work.accept(ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }
}
