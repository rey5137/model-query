package com.rey.modelquery.tck.pag;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfig;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CompositeKeyItemEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;

/** Primary-key-first deep paging: keys first, then the rows of those keys in batches (engine/21 §3). */
class PrimaryKeyFirstTest {

    record ItemRow(Long id, String product) {}

    record TenantItem(Integer tenantId, Integer itemNo, String label) {}

    record OrderRow(Long id, String status) {}

    record SortRow(Long id, Integer sortInt) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemRow.class, ITEMS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(ItemRow.class, ITEMS, "productCode", String.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(ItemRow.class, ITEMS, "quantity", Integer.class);

    /** Every order item, 20 000 rows, over 50 product codes: each sort key is shared by 400 rows. */
    private static final ModelQuery.Builder<OrderItemEntity, Long, ItemRow> BY_PRODUCT = ModelQuery
            .builder(ITEMS, row -> new ItemRow(row.get(ITEM_ID), row.get(ITEM_PRODUCT)))
            .columns(ColumnSet.of(ITEM_ID, ITEM_PRODUCT))
            .primaryKey(PrimaryKey.of(ITEM_ID))
            .orderBy(ITEM_PRODUCT.asc());

    private static final TableField<CompositeKeyItemEntity, CompositeKeyItemEntity> TENANT_ITEMS =
            TableField.root(CompositeKeyItemEntity.class);
    private static final ColumnField<TenantItem, CompositeKeyItemEntity, Integer> TENANT_ID =
            ColumnField.of(TenantItem.class, TENANT_ITEMS, "tenantId", Integer.class);
    private static final ColumnField<TenantItem, CompositeKeyItemEntity, Integer> ITEM_NO =
            ColumnField.of(TenantItem.class, TENANT_ITEMS, "itemNo", Integer.class);
    private static final ColumnField<TenantItem, CompositeKeyItemEntity, String> LABEL =
            ColumnField.of(TenantItem.class, TENANT_ITEMS, "label", String.class);

    /** Every composite-key item, 2 000 rows, over 25 labels: each sort key is shared by 80 rows. */
    private static final ModelQuery.Builder<CompositeKeyItemEntity, List<Object>, TenantItem> BY_LABEL = ModelQuery
            .builder(TENANT_ITEMS, row -> new TenantItem(row.get(TENANT_ID), row.get(ITEM_NO), row.get(LABEL)))
            .columns(ColumnSet.of(TENANT_ID, ITEM_NO, LABEL))
            .primaryKey(PrimaryKey.composite(TENANT_ID, ITEM_NO))
            .orderBy(LABEL.desc());

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(ORDERS, "items", INNER);
    private static final ColumnField<OrderRow, OrderEntity, Long> ORDER_ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> ORDER_STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderItemEntity, String> ORDER_ITEM_PRODUCT =
            ColumnField.of(OrderRow.class, ORDER_ITEMS, "productCode", String.class);

    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ORDER_ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ORDER_ID), row.get(ORDER_STATUS)))
            .columns(ColumnSet.of(ORDER_ID, ORDER_STATUS))
            .primaryKey(PrimaryKey.of(ORDER_ID));

    // ---- AC-PAG-08

    @TckTest
    void ac_pag_08_primary_key_first_pages_hold_the_same_rows_as_offset_pages_for_a_single_key(TckDatabase db) {
        // Pages of 700 over ties of 400: the threshold falls mid-fixture, so both kinds of page are compared.
        var plain = BY_PRODUCT.build();
        var twoStep = BY_PRODUCT.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(7_000)).build();
        List<ItemRow> rows = samePages(db, OrderItemEntity.class, plain, twoStep, 700);
        assertThat(rows).extracting(ItemRow::id).hasSize(TckFixture.ORDER_ITEMS).doesNotHaveDuplicates();
        assertThat(rows).isSortedAccordingTo(Comparator.comparing(ItemRow::product).thenComparing(ItemRow::id));
    }

    @TckTest
    void ac_pag_08_primary_key_first_pages_hold_the_same_rows_as_offset_pages_for_a_composite_key(TckDatabase db) {
        var plain = BY_LABEL.build();
        var twoStep = BY_LABEL.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(300)).build();
        List<TenantItem> rows = samePages(db, CompositeKeyItemEntity.class, plain, twoStep, 70);
        assertThat(rows).extracting(item -> List.of(item.tenantId(), item.itemNo()))
                .hasSize(TckFixture.COMPOSITE_KEY_ITEMS).doesNotHaveDuplicates();
        assertThat(rows).isSortedAccordingTo(Comparator.comparing(TenantItem::label).reversed()
                .thenComparing(TenantItem::tenantId).thenComparing(TenantItem::itemNo));
    }

    @TckTest
    void ac_pag_08_primary_key_first_export_visits_the_same_rows_as_offset_export(TckDatabase db) {
        List<ItemRow> plainItems = export(db, OrderItemEntity.class, BY_PRODUCT.build(), 900);
        List<ItemRow> twoStepItems = export(db, OrderItemEntity.class,
                BY_PRODUCT.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(4_000)).build(), 900);
        assertThat(twoStepItems).hasSize(TckFixture.ORDER_ITEMS).isEqualTo(plainItems);

        List<TenantItem> plainTenants = export(db, CompositeKeyItemEntity.class, BY_LABEL.build(), 90);
        List<TenantItem> twoStepTenants = export(db, CompositeKeyItemEntity.class,
                BY_LABEL.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build(), 90);
        assertThat(twoStepTenants).hasSize(TckFixture.COMPOSITE_KEY_ITEMS).isEqualTo(plainTenants);
    }

    @TckTest
    void ac_pag_08_a_to_many_join_only_in_a_predicate_repeats_rows_as_offset_paging_does(TckDatabase db) {
        // Each order holding P001 holds it four times, so the join repeats each root four times in a row. A page
        // repeats it as the one-step page does, whichever page the repeats fall on; export still visits it once.
        var withItem = ORDER_ROWS.where(f -> f.eq(ORDER_ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ORDER_STATUS.asc());
        var plain = withItem.build();
        var twoStep = withItem.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        List<OrderRow> paged = samePages(db, OrderEntity.class, plain, twoStep, 7);
        List<OrderRow> distinct = paged.stream().distinct().toList();
        assertThat(paged).as("the join does repeat the roots").hasSizeGreaterThan(distinct.size());
        List<OrderRow> exported = export(db, OrderEntity.class, twoStep, 7);
        assertThat(exported).isEqualTo(export(db, OrderEntity.class, plain, 7)).containsExactlyElementsOf(distinct);
    }

    @TckTest
    void ac_pag_08_primary_key_first_reads_keys_then_their_rows_past_the_threshold(TckDatabase db) {
        // P001 ordered by quantity: the page at offset 5 reads in one statement, the one at offset 15 in two.
        var p001 = BY_PRODUCT.where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ITEM_QUANTITY.desc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10))
                .build();
        List<List<ItemRow>> pages = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-08-primary-key-first", ds -> withExecutor(ds,
                OrderItemEntity.class, executor -> {
                    pages.add(executor.page(p001, PageSpec.of(1, 5), CountMode.NO_COUNT).content());
                    pages.add(executor.page(p001, PageSpec.of(3, 5), CountMode.NO_COUNT).content());
                }));
        assertThat(sql).hasSize(3);
        assertThat(pages).allSatisfy(page -> assertThat(page).hasSize(5));
    }

    @TckTest
    void ac_pag_08_a_composite_key_is_read_back_as_an_or_of_its_columns(TckDatabase db) {
        // The query's own predicate is an OR too (ne keeps NULLs, D-5), so step 2 must keep the two apart.
        var notFirst = BY_LABEL.where(f -> f.ne(LABEL, "label-00"));
        var twoStep = notFirst.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        List<TenantItem> page = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-08-primary-key-first-composite", ds -> withExecutor(ds,
                CompositeKeyItemEntity.class, executor ->
                        page.addAll(executor.page(twoStep, PageSpec.of(1, 3), CountMode.NO_COUNT).content())));
        assertThat(sql).hasSize(2);
        withExecutor(db, CompositeKeyItemEntity.class, executor -> assertThat(page).hasSize(3)
                .isEqualTo(executor.page(notFirst.build(), PageSpec.of(1, 3), CountMode.NO_COUNT).content()));
    }

    @TckTest
    void ac_pag_08_offset_export_switches_to_primary_key_first_past_the_threshold(TckDatabase db) {
        // 400 rows of P001 in pages of 150: offsets 0 and 150 read in one statement, offset 300 in two.
        var p001 = BY_PRODUCT.where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ITEM_QUANTITY.desc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(150))
                .build();
        List<Long> ids = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-08-primary-key-first-export", ds -> withExecutor(ds,
                OrderItemEntity.class, executor -> executor.export(p001, ExportOptions.of(150), page -> page,
                        row -> ids.add(row.id()))));
        assertThat(sql).hasSize(4);
        assertThat(ids).hasSize(TckFixture.ORDER_ITEMS / 50).doesNotHaveDuplicates();
    }

    // ---- AC-PAG-09

    /** A page of more keys than any Tier-1 database takes in one IN list (vendor/41 §2). */
    private static final int ABOVE_THE_IN_LIMIT = 10_050;

    @TckTest
    void ac_pag_09_a_step_two_batch_above_the_vendor_in_limit_is_split_and_stays_ordered(TckDatabase db) {
        var plain = BY_PRODUCT.orderBy(ITEM_PRODUCT.desc()).build();
        var twoStep = BY_PRODUCT.orderBy(ITEM_PRODUCT.desc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                .build();
        var deep = new PageSpec(4_321, ABOVE_THE_IN_LIMIT);
        List<Slice<ItemRow>> slices = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderItemEntity.class,
                executor -> slices.add(executor.page(twoStep, deep, CountMode.NO_COUNT))));
        // One statement reads the keys, and the page's keys (one more, for the hasNext probe) are read back in
        // more than one statement, none of which binds them all.
        assertThat(sql).hasSizeGreaterThan(2);
        assertThat(sql.subList(1, sql.size())).allSatisfy(statement -> assertThat(placeholders(statement))
                .isLessThan(ABOVE_THE_IN_LIMIT));
        Slice<ItemRow> slice = slices.get(0);
        withExecutor(db, OrderItemEntity.class, executor -> {
            Slice<ItemRow> expected = executor.page(plain, deep, CountMode.NO_COUNT);
            assertThat(slice.content()).hasSize(ABOVE_THE_IN_LIMIT).isEqualTo(expected.content());
            assertThat(slice.hasNext()).isEqualTo(expected.hasNext()).isTrue();
        });
        assertThat(slice.content()).isSortedAccordingTo(Comparator.comparing(ItemRow::product).reversed()
                .thenComparing(ItemRow::id));
    }

    @TckTest
    void ac_pag_09_a_step_two_batch_leaves_room_for_the_querys_own_bind_parameters(TckDatabase db) {
        // The query binds 60 000 values of its own, so 9 000 keys on top would pass PostgreSQL's 65 535 binds
        // (vendor/41 §2) though they fit one IN list: the page's keys are read back in two statements, not one.
        List<Long> ids = LongStream.rangeClosed(1, 60_000).boxed().toList();
        var listed = BY_PRODUCT.where(f -> f.in(ITEM_ID, ids));
        var twoStep = listed.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        var deep = new PageSpec(1_000, 8_999);
        List<Slice<ItemRow>> slices = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderItemEntity.class,
                executor -> slices.add(executor.page(twoStep, deep, CountMode.NO_COUNT))));
        assertThat(sql).as("the key statement and two step-2 statements").hasSize(3);
        withExecutor(db, OrderItemEntity.class, executor -> assertThat(slices.get(0).content()).hasSize(8_999)
                .isEqualTo(executor.page(listed.build(), deep, CountMode.NO_COUNT).content()));
    }

    private static long placeholders(String statement) {
        return statement.chars().filter(c -> c == '?').count();
    }

    // ---- AC-PAG-04, AC-PAG-12

    @TckTest
    void ac_pag_04_a_primary_key_first_page_whose_key_maps_to_null_throws_mq2201(TckDatabase db) {
        // sortInt is null on every fifth row, so the misdeclared key is null within the page.
        var sortRows = TableField.root(NullableSortEntity.class);
        var id = ColumnField.of(SortRow.class, sortRows, "id", Long.class);
        var sortInt = ColumnField.of(SortRow.class, sortRows, "sortInt", Integer.class);
        var nullableKey = ModelQuery.builder(sortRows, row -> new SortRow(row.get(id), row.get(sortInt)))
                .columns(ColumnSet.of(id))
                .primaryKey(PrimaryKey.of(sortInt))
                .orderBy(id.asc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                .build();
        withExecutor(db, NullableSortEntity.class, executor -> assertThatThrownBy(
                () -> executor.page(nullableKey, PageSpec.of(1, 10), CountMode.NO_COUNT))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2201))
                .hasMessageStartingWith(MqCode.MQ2201.code() + ": SortRow:")
                .hasMessageContaining("sortInt"));
    }

    @TckTest
    void ac_pag_12_primary_key_first_paging_selecting_through_a_to_many_join_throws_mq2204(TckDatabase db) {
        var selected = ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ORDER_ID), row.get(ORDER_ITEM_PRODUCT)))
                .columns(ColumnSet.of(ORDER_ID, ORDER_ITEM_PRODUCT))
                .primaryKey(PrimaryKey.of(ORDER_ID))
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100))
                .build();
        var ordered = ORDER_ROWS.orderBy(ORDER_ITEM_PRODUCT.asc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100))
                .build();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-12-primary-key-first-to-many-refused", ds ->
                withExecutor(ds, OrderEntity.class, executor -> {
                    for (ModelQuery<OrderEntity, Long, OrderRow> q : List.of(selected, ordered)) {
                        for (CountMode mode : List.of(CountMode.COUNT, CountMode.NO_COUNT)) {
                            assertThatThrownBy(() -> executor.page(q, PageSpec.of(2, 100), mode))
                                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2204))
                                    .hasMessageStartingWith(MqCode.MQ2204.code()
                                            + ": OrderRow: primary-key-first paging")
                                    .hasMessageContaining("OrderEntity.items");
                        }
                    }
                }));
        assertThat(sql).as("refused before any query runs, the count included").isEmpty();
        // A page within the threshold reads in one step, and page accepts the shape there (R-PAG-13).
        withExecutor(db, OrderEntity.class, executor -> assertThat(
                executor.page(selected, PageSpec.of(1, 100), CountMode.NO_COUNT).content()).hasSize(100));
    }

    // ---- AC-PAG-14

    @TckTest
    void ac_pag_14_primary_key_first_over_phases_that_disagree_throws_mq2206_before_any_query(TckDatabase db) {
        // Step 1 would page every order and step 2 read back only the PAID ones, so pages past the threshold would
        // come back short and an export would skip rows at the switch (R-PAG-15).
        QueryCustomizer onlyModel = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL) {
                query.where(cb.equal(query.getRoots().iterator().next().get("status"), "PAID"));
            }
        };
        var twoStep = ORDER_ROWS.customize(onlyModel).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100)).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
            // Within the threshold too: the check belongs to the definition, not to the page asked for.
            for (PageSpec page : List.of(PageSpec.of(0, 50), PageSpec.of(5, 50))) {
                assertMq2206(() -> executor.page(twoStep, page, CountMode.COUNT));
            }
            assertMq2206(() -> executor.export(twoStep, ExportOptions.of(50), rows -> rows, row -> {}));
        }));
        assertThat(sql).as("refused before any query runs, every time").isEmpty();
        // Without primaryKeyFirst(...) the same customizer only warns (R-QRY-09), and the export runs.
        var oneStep = ORDER_ROWS.customize(onlyModel).build();
        List<OrderRow> rows = new ArrayList<>();
        withExecutor(db, OrderEntity.class,
                executor -> executor.export(oneStep, ExportOptions.of(500), page -> page, rows::add));
        assertThat(rows).hasSize(TckFixture.ORDERS / 4).allMatch(row -> row.status().equals("PAID"));
    }

    private static void assertMq2206(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2206))
                .hasMessageStartingWith("MQ2206: OrderRow: the QueryCustomizer adds a predicate or an INNER join only "
                        + "in phase(s) [MODEL], not in [PRIMARY_KEY, MODEL_BY_KEYS]");
    }

    // ---- support

    /**
     * Pages {@code plain} and {@code twoStep} through the whole result, asserting each page of one equals the same
     * page of the other, and returns the rows of every page in order.
     */
    private static <E, M> List<M> samePages(TckDatabase db, Class<E> root, ModelQuery<E, ?, M> plain,
            ModelQuery<E, ?, M> twoStep, int pageSize) {
        List<M> rows = new ArrayList<>();
        withExecutor(db, root, executor -> {
            Slice<M> expected;
            int page = 0;
            do {
                PageSpec spec = PageSpec.of(page++, pageSize);
                expected = executor.page(plain, spec, CountMode.NO_COUNT);
                Slice<M> actual = executor.page(twoStep, spec, CountMode.NO_COUNT);
                assertThat(actual.content()).as("page %d", spec.pageNumber()).isEqualTo(expected.content());
                assertThat(actual.hasNext()).isEqualTo(expected.hasNext());
                rows.addAll(actual.content());
            } while (expected.hasNext());
        });
        return rows;
    }

    private static <E, M> List<M> export(TckDatabase db, Class<E> root, ModelQuery<E, ?, M> q, int pageSize) {
        List<M> rows = new ArrayList<>();
        withExecutor(db, root, executor -> executor.export(q, ExportOptions.of(pageSize), page -> page, rows::add));
        return rows;
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
}
