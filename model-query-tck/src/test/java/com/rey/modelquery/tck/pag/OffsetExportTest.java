package com.rey.modelquery.tck.pag;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfig;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/** Offset {@code export}: stable order, page-boundary dedupe, the primary-key check, the to-many refusal (engine/21). */
class OffsetExportTest {

    record ItemRow(Long id, String product) {}

    record OrderRow(Long id, String status) {}

    record SortRow(Long id, Integer sortInt) {}

    /** A mutable model with a derived field, the {@code afterMap} case. */
    static final class Labelled {
        Long id;
        String status;
        String label;
    }

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemRow.class, ITEMS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(ItemRow.class, ITEMS, "productCode", String.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(ItemRow.class, ITEMS, "quantity", Integer.class);

    /** Every order item: 20 000 rows. */
    private static final ModelQuery.Builder<OrderItemEntity, Long, ItemRow> ITEM_ROWS = ModelQuery
            .builder(ITEMS, row -> new ItemRow(row.get(ITEM_ID), row.get(ITEM_PRODUCT)))
            .columns(ColumnSet.of(ITEM_ID, ITEM_PRODUCT))
            .primaryKey(PrimaryKey.of(ITEM_ID));

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

    // ---- AC-PAG-01

    @TckTest
    void ac_pag_01_offset_export_over_duplicated_sort_keys_yields_every_row_exactly_once(TckDatabase db) {
        // 50 product codes over 20 000 items: every sort key is shared by 400 rows, so only the appended primary key
        // makes the order unique. Neither page size is a multiple of 400, so ties straddle every boundary.
        var byProduct = ITEM_ROWS.orderBy(ITEM_PRODUCT.asc()).build();
        for (int pageSize : new int[] {333, 1_000}) {
            List<ItemRow> rows = new ArrayList<>();
            withExecutor(db, OrderItemEntity.class, executor -> assertThat(
                    executor.export(byProduct, ExportOptions.of(pageSize), page -> page, rows::add))
                    .isEqualTo(TckFixture.ORDER_ITEMS));
            assertEveryItemOnce(rows.stream().map(ItemRow::id).toList());
            assertThat(rows).extracting(ItemRow::product).as("page size %d keeps the caller's order", pageSize)
                    .isSorted();
        }
    }

    @TckTest
    void ac_pag_01_the_export_appends_the_primary_key_to_the_callers_order(TckDatabase db) {
        // 400 items of product P001 over nine quantities, three pages of 150: the SQL shows the tie-breaker.
        var p001 = ModelQuery.builder(ITEMS, row -> new ItemRow(row.get(ITEM_ID), row.get(ITEM_PRODUCT)))
                .columns(ColumnSet.of(ITEM_ID, ITEM_PRODUCT))
                .primaryKey(PrimaryKey.of(ITEM_ID))
                .where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ITEM_QUANTITY.desc())
                .build();
        List<Long> ids = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-01-stable-order", ds -> withExecutor(ds,
                OrderItemEntity.class, executor -> executor.export(p001, ExportOptions.of(150), page -> page,
                        row -> ids.add(row.id()))));
        assertThat(sql).hasSize(3);
        assertThat(ids).hasSize(TckFixture.ORDER_ITEMS / 50).doesNotHaveDuplicates();
    }

    @TckTest
    void ac_pag_01_a_row_shifted_across_a_page_boundary_is_exported_once(TckDatabase db) {
        // After each page, a row is inserted that sorts before every other one. Every later row moves one place on,
        // so the next page starts with the last row of the page just read: without the boundary dedupe, 39 rows
        // would be exported twice (R-PAG-02). The export runs in a transaction that sees its own inserts on every
        // vendor, and rolls back to leave the fixture as seeded.
        var byProduct = ITEM_ROWS.orderBy(ITEM_PRODUCT.asc()).build();
        List<Long> ids = new ArrayList<>();
        AtomicInteger inserted = new AtomicInteger();
        long[] passed = new long[1];
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    ModelQueryExecutor<OrderItemEntity> executor =
                            ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults());
                    passed[0] = executor.export(byProduct, ExportOptions.of(500), page -> {
                        em.createNativeQuery("INSERT INTO order_items (id, order_id, product_code, quantity, "
                                        + "unit_price) VALUES (:id, 1, 'A000', 1, 1.00)")
                                .setParameter("id", 1_000_000L + inserted.incrementAndGet())
                                .executeUpdate();
                        return page;
                    }, row -> ids.add(row.id()));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
        // The 40 inserted rows push 40 rows past the 40th page, into a 41st.
        assertThat(inserted).hasValue(TckFixture.ORDER_ITEMS / 500 + 1);
        assertThat(passed[0]).isEqualTo(TckFixture.ORDER_ITEMS);
        assertEveryItemOnce(ids);
    }

    // ---- AC-PAG-02

    @TckTest
    void ac_pag_02_export_memory_stays_bounded_by_one_page_over_20_000_rows(TckDatabase db) throws Exception {
        // Two checks that no structure grows with the row count. The models built ahead of the sink never exceed a
        // page, so the engine does not buffer the result. And once two pages later, the key objects of a page are
        // unreachable: the engine keeps the previous page's keys only, not a set of every key seen (R-PAG-02). Its
        // keys are the very Long objects the mapper read from the same row; ids up to 127 are skipped, since the JDK
        // caches those boxes.
        int pageSize = 500;
        AtomicLong mapped = new AtomicLong();
        AtomicLong sunk = new AtomicLong();
        AtomicLong maxAhead = new AtomicLong();
        List<List<WeakReference<Object>>> pageKeys = new ArrayList<>();
        List<Integer> checkedAt = new ArrayList<>();
        var q = ModelQuery.builder(ITEMS, row -> {
                    mapped.incrementAndGet();
                    return new ItemRow(row.get(ITEM_ID), row.get(ITEM_PRODUCT));
                })
                .columns(ColumnSet.of(ITEM_ID, ITEM_PRODUCT))
                .primaryKey(PrimaryKey.of(ITEM_ID))
                .orderBy(ITEM_PRODUCT.asc())
                .build();
        withExecutor(db, OrderItemEntity.class, executor -> {
            long passed = executor.export(q, ExportOptions.of(pageSize), page -> {
                List<WeakReference<Object>> keys = new ArrayList<>();
                page.stream().filter(row -> row.id() > 127).forEach(row -> keys.add(new WeakReference<>(row.id())));
                pageKeys.add(keys);
                int current = pageKeys.size() - 1;
                if (current >= 2 && current % 10 == 0) {
                    List<WeakReference<Object>> older = new ArrayList<>();
                    pageKeys.subList(0, current - 1).forEach(older::addAll);
                    assertThat(collected(older)).as("keys of pages 0..%d, read on page %d", current - 2, current)
                            .isTrue();
                    checkedAt.add(current);
                }
                return page;
            }, row -> {
                sunk.incrementAndGet();
                maxAhead.accumulateAndGet(mapped.get() - sunk.get(), Math::max);
            });
            assertThat(passed).isEqualTo(TckFixture.ORDER_ITEMS);
        });
        assertThat(checkedAt).containsExactly(10, 20, 30);
        assertThat(mapped).hasValue(TckFixture.ORDER_ITEMS);
        assertThat(maxAhead.get()).as("models built ahead of the sink").isLessThan(pageSize);
    }

    /** Whether every reference is cleared, after asking for a collection a few times. */
    private static boolean collected(List<WeakReference<Object>> refs) {
        for (int attempt = 0; attempt < 10; attempt++) {
            System.gc();
            if (refs.stream().allMatch(ref -> ref.get() == null)) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ---- AC-PAG-03, AC-QRY-03

    /** The column set names the product only; the mapper reads the key the engine added. */
    private static final ModelQuery<OrderItemEntity, Long, ItemRow> WITHOUT_KEY_COLUMN = ModelQuery
            .builder(ITEMS, row -> new ItemRow(row.get(ITEM_ID), row.get(ITEM_PRODUCT)))
            .columns(ColumnSet.of(ITEM_PRODUCT))
            .primaryKey(PrimaryKey.of(ITEM_ID))
            .orderBy(ITEM_PRODUCT.desc())
            .build();

    @TckTest
    void ac_pag_03_an_export_whose_column_set_omits_the_primary_key_still_succeeds(TckDatabase db) {
        assertThat(WITHOUT_KEY_COLUMN.columns().columns()).doesNotContain(ITEM_ID);
        List<Long> ids = new ArrayList<>();
        withExecutor(db, OrderItemEntity.class, executor -> executor.export(WITHOUT_KEY_COLUMN,
                ExportOptions.of(700), page -> page, row -> ids.add(row.id())));
        assertEveryItemOnce(ids);
    }

    @TckTest
    void ac_qry_03_a_column_set_omitting_the_primary_key_pages_and_exports_every_row(TckDatabase db) {
        List<ItemRow> paged = new ArrayList<>();
        List<ItemRow> exported = new ArrayList<>();
        withExecutor(db, OrderItemEntity.class, executor -> {
            Slice<ItemRow> slice;
            int page = 0;
            do {
                slice = executor.page(WITHOUT_KEY_COLUMN, PageSpec.of(page++, 700), CountMode.NO_COUNT);
                paged.addAll(slice.content());
            } while (slice.hasNext());
            executor.export(WITHOUT_KEY_COLUMN, ExportOptions.of(700), p -> p, exported::add);
        });
        assertEveryItemOnce(paged.stream().map(ItemRow::id).toList());
        assertThat(exported).isEqualTo(paged);
    }

    // ---- AC-PAG-04

    private static final TableField<NullableSortEntity, NullableSortEntity> SORT_ROWS =
            TableField.root(NullableSortEntity.class);
    private static final ColumnField<SortRow, NullableSortEntity, Long> SORT_ID =
            ColumnField.of(SortRow.class, SORT_ROWS, "id", Long.class);
    private static final ColumnField<SortRow, NullableSortEntity, Integer> SORT_INT =
            ColumnField.of(SortRow.class, SORT_ROWS, "sortInt", Integer.class);

    @TckTest
    void ac_pag_04_a_primary_key_mapped_to_null_throws_mq2201_naming_the_model(TckDatabase db) {
        // sortInt is null on every fifth row, so the misdeclared key is null on row 5 of the first page.
        var nullableKey = ModelQuery.builder(SORT_ROWS, row -> new SortRow(row.get(SORT_ID), row.get(SORT_INT)))
                .columns(ColumnSet.of(SORT_ID))
                .primaryKey(PrimaryKey.of(SORT_INT))
                .orderBy(SORT_ID.asc())
                .build();
        List<SortRow> sunk = new ArrayList<>();
        withExecutor(db, NullableSortEntity.class, executor -> assertThatThrownBy(
                () -> executor.export(nullableKey, ExportOptions.of(100), page -> page, sunk::add))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2201))
                .hasMessageStartingWith(MqCode.MQ2201.code() + ": SortRow:")
                .hasMessageContaining("sortInt"));
        assertThat(sunk).isEmpty();
    }

    @TckTest
    void offset_export_of_a_query_without_a_primary_key_throws_mq2203_without_querying(TckDatabase db) {
        var noKey = ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ORDER_ID), row.get(ORDER_STATUS)))
                .columns(ColumnSet.of(ORDER_ID, ORDER_STATUS))
                .build();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-no-primary-key", ds -> withExecutor(ds,
                OrderEntity.class, executor -> assertThatThrownBy(
                        () -> executor.export(noKey, ExportOptions.of(100), page -> page, row -> {}))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2203))
                        .hasMessageContaining("OrderRow")));
        assertThat(sql).isEmpty();
    }

    // ---- AC-PAG-12

    @TckTest
    void ac_pag_12_offset_export_selecting_through_a_to_many_join_throws_mq2204_naming_the_join(TckDatabase db) {
        var selected = ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ORDER_ID), row.get(ORDER_ITEM_PRODUCT)))
                .columns(ColumnSet.of(ORDER_ID, ORDER_ITEM_PRODUCT))
                .primaryKey(PrimaryKey.of(ORDER_ID))
                .build();
        // Ordering keys are selected too (R-QRY-04, D-29), and ordering through the join repeats the key as well.
        var ordered = ORDER_ROWS.orderBy(ORDER_ITEM_PRODUCT.asc()).build();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-12-to-many-refused", ds -> withExecutor(ds,
                OrderEntity.class, executor -> {
                    for (ModelQuery<OrderEntity, Long, OrderRow> q : List.of(selected, ordered)) {
                        assertThatThrownBy(() -> executor.export(q, ExportOptions.of(100), page -> page, row -> {}))
                                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2204))
                                .hasMessageStartingWith(MqCode.MQ2204.code() + ": OrderRow:")
                                .hasMessageContaining("OrderEntity.items");
                    }
                }));
        assertThat(sql).as("refused before any query runs").isEmpty();
    }

    @TckTest
    void ac_pag_12_the_same_export_with_the_to_many_join_only_in_a_predicate_succeeds(TckDatabase db) {
        // Each order holding P001 holds it four times, so the join repeats each root four times in a row. Page sizes
        // that split those runs across a boundary and within a page all export each order once.
        var withItem = ORDER_ROWS.where(f -> f.eq(ORDER_ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ORDER_STATUS.asc())
                .build();
        List<Long> expected = new ArrayList<>();
        long[] joinedRows = new long[1];
        inSession(db, em -> {
            expected.addAll(em.createQuery("select distinct o.id from OrderEntity o join o.items i"
                    + " where i.productCode = 'P001'", Long.class).getResultList());
            joinedRows[0] = em.createQuery("select count(o) from OrderEntity o join o.items i"
                    + " where i.productCode = 'P001'", Long.class).getSingleResult();
        });
        assertThat(joinedRows[0]).as("the join does repeat the roots").isGreaterThan(expected.size());
        for (int pageSize : new int[] {3, 4, 7, 1_000}) {
            List<OrderRow> rows = new ArrayList<>();
            withExecutor(db, OrderEntity.class,
                    executor -> executor.export(withItem, ExportOptions.of(pageSize), page -> page, rows::add));
            assertThat(rows).extracting(OrderRow::id).as("page size %d", pageSize)
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(rows).extracting(OrderRow::status).isSorted();
        }
    }

    // ---- AC-QRY-04

    private static final ColumnField<Labelled, OrderEntity, Long> L_ID =
            ColumnField.of(Labelled.class, ORDERS, "id", Long.class);
    private static final ColumnField<Labelled, OrderEntity, String> L_STATUS =
            ColumnField.of(Labelled.class, ORDERS, "status", String.class);
    private static final ColumnField<Labelled, OrderEntity, BigDecimal> L_TOTAL =
            ColumnField.of(Labelled.class, ORDERS, "total", BigDecimal.class);

    @TckTest
    void ac_qry_04_after_map_runs_once_per_row_and_its_effect_survives_export(TckDatabase db) {
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> sawAll = new ArrayList<>();
        RowMapper<Labelled> mapper = RowMapper.setters(Labelled::new)
                .bind(L_ID, (m, v) -> m.id = v)
                .bind(L_STATUS, (m, v) -> m.status = v);
        var q = ModelQuery.builder(ORDERS, mapper)
                .columns(ColumnSet.of(L_ID, L_STATUS, L_TOTAL))
                .primaryKey(PrimaryKey.of(L_ID))
                .orderBy(L_STATUS.asc())
                .afterMap((m, row) -> {
                    calls.incrementAndGet();
                    sawAll.add(row.isSelected(L_ID) && row.isSelected(L_STATUS) && row.isSelected(L_TOTAL));
                    m.label = row.get(L_STATUS) + "/" + row.get(L_TOTAL);
                })
                .build();
        List<Labelled> exported = new ArrayList<>();
        withExecutor(db, OrderEntity.class, executor -> executor.export(q, ExportOptions.of(700), page -> {
            assertThat(page).allSatisfy(m -> assertThat(m.label).isNotNull());
            return page;
        }, exported::add));
        assertThat(exported).hasSize(TckFixture.ORDERS);
        assertThat(calls).hasValue(TckFixture.ORDERS);
        assertThat(sawAll).hasSize(TckFixture.ORDERS).containsOnly(true);
        assertThat(exported).allSatisfy(m -> assertThat(m.label).startsWith(m.status + "/"));
        assertThat(exported).extracting(m -> m.id).doesNotHaveDuplicates();
    }

    // ---- AC-QRY-06

    @TckTest
    void ac_qry_06_the_executor_checks_the_phases_once_on_first_execution(TckDatabase db) {
        QueryCustomizer onlyModel = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL) {
                query.where(cb.equal(query.getRoots().iterator().next().get("status"), "PAID"));
            }
        };
        var first = ORDER_ROWS.customize(onlyModel).build();
        var second = ORDER_ROWS.customize(onlyModel).build();
        List<String> warnings = modelQueryWarnings(() -> withExecutor(db, OrderEntity.class, executor -> {
            executor.list(first, Limit.of(1));
            executor.count(first);
            executor.export(first, new ExportOptions(10, Limit.of(10)), page -> page, row -> {});
            withExecutor(db, OrderEntity.class, other -> other.list(first, Limit.of(1)));
            executor.list(second, Limit.of(1));
        }));
        assertThat(warnings).as("once per ModelQuery, whichever executor runs it").hasSize(2)
                .allMatch(w -> w.contains("[MODEL]") && w.contains("PRIMARY_KEY"));
    }

    // ---- limits and options

    @Test
    void ac_exe_05_an_export_page_size_that_is_not_positive_throws_mq2001() {
        for (int size : new int[] {0, -1}) {
            assertThatThrownBy(() -> ExportOptions.of(size))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2001))
                    .hasMessageStartingWith(MqCode.MQ2001.code() + ":");
            assertThatThrownBy(() -> new ExportOptions(size, Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2001));
        }
    }

    @TckTest
    void export_stops_at_the_limit_and_a_zero_limit_runs_no_query(TckDatabase db) {
        var byId = ITEM_ROWS.orderBy(ITEM_ID.asc()).build();
        List<Long> none = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-zero-limit", ds -> withExecutor(ds,
                OrderItemEntity.class, executor -> assertThat(executor.export(byId,
                        new ExportOptions(100, Limit.of(0)), page -> page, row -> none.add(row.id()))).isZero()));
        assertThat(sql).isEmpty();
        assertThat(none).isEmpty();

        List<Long> ids = new ArrayList<>();
        withExecutor(db, OrderItemEntity.class, executor -> assertThat(executor.export(byId,
                new ExportOptions(500, Limit.of(1_234)), page -> page, row -> ids.add(row.id()))).isEqualTo(1_234));
        assertThat(ids).containsExactlyElementsOf(LongStream.rangeClosed(1, 1_234).boxed().toList());
    }

    @TckTest
    void export_returns_the_items_passed_to_sink_and_skips_an_empty_page(TckDatabase db) {
        // The transformer drops every page but the first and expands that one: the sink count is what it returned.
        var byId = ITEM_ROWS.orderBy(ITEM_ID.asc()).build();
        List<Integer> pageSizes = new ArrayList<>();
        List<String> sunk = new ArrayList<>();
        withExecutor(db, OrderItemEntity.class, executor -> assertThat(executor.export(byId,
                ExportOptions.of(4_000), page -> {
                    pageSizes.add(page.size());
                    return page.get(0).id() == 1L ? List.of("a", "b", "c") : List.of();
                }, sunk::add)).isEqualTo(3));
        assertThat(pageSizes).containsExactly(4_000, 4_000, 4_000, 4_000, 4_000);
        assertThat(sunk).containsExactly("a", "b", "c");
    }

    // ---- support

    /** Every order item id from 1 to 20 000, each exactly once. */
    private static void assertEveryItemOnce(List<Long> ids) {
        assertThat(ids).hasSize(TckFixture.ORDER_ITEMS).doesNotHaveDuplicates()
                .allMatch(id -> id >= 1 && id <= TckFixture.ORDER_ITEMS);
    }

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

    /** The warnings {@code ModelQuery} logs while {@code work} runs. */
    private static List<String> modelQueryWarnings(Runnable work) {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger(ModelQuery.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(new SimpleFormatter().formatMessage(r));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            work.run();
        } finally {
            logger.removeHandler(handler);
        }
        return warnings;
    }
}
