package com.rey.modelquery.tck.pag;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** Keyset {@code export}: the tie-breaker, selected order keys, NULL keys (engine/21 §2, vendor/41 §5). */
class KeysetExportTest {

    record ItemRow(Long id, String product, Integer quantity) {}

    record OrderRow(Long id, String status) {}

    record SortRow(Long id, Integer sortInt, String sortText, LocalDateTime sortTs) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemRow.class, ITEMS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(ItemRow.class, ITEMS, "productCode", String.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(ItemRow.class, ITEMS, "quantity", Integer.class);

    /** Every order item, keyset-paged: 20 000 rows. */
    private static final ModelQuery.Builder<OrderItemEntity, Long, ItemRow> ITEM_ROWS = ModelQuery
            .builder(ITEMS, row -> new ItemRow(row.get(ITEM_ID), row.get(ITEM_PRODUCT), row.get(ITEM_QUANTITY)))
            .select(SelectSet.of(ITEM_ID, ITEM_PRODUCT, ITEM_QUANTITY))
            .primaryKey(PrimaryKey.of(ITEM_ID))
            .keyset();

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> ORDER_CUSTOMER =
            TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(ORDERS, "items", INNER);
    private static final ColumnField<OrderRow, OrderEntity, Long> ORDER_ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> ORDER_STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> ORDER_TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);
    /** Filter-only: the model has no field for it (R-COL-07). */
    private static final ColumnField<OrderRow, CustomerEntity, String> ORDER_COUNTRY =
            ColumnField.of(OrderRow.class, ORDER_CUSTOMER, "country", String.class);
    private static final ColumnField<OrderRow, OrderItemEntity, String> ORDER_ITEM_PRODUCT =
            ColumnField.of(OrderRow.class, ORDER_ITEMS, "productCode", String.class);

    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ORDER_ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ORDER_ID), row.get(ORDER_STATUS)))
            .select(SelectSet.of(ORDER_ID, ORDER_STATUS))
            .primaryKey(PrimaryKey.of(ORDER_ID))
            .keyset();

    private static final TableField<NullableSortEntity, NullableSortEntity> SORT_ROWS =
            TableField.root(NullableSortEntity.class);
    private static final ColumnField<SortRow, NullableSortEntity, Long> SORT_ID =
            ColumnField.of(SortRow.class, SORT_ROWS, "id", Long.class);
    private static final ColumnField<SortRow, NullableSortEntity, Integer> SORT_INT =
            ColumnField.of(SortRow.class, SORT_ROWS, "sortInt", Integer.class);
    private static final ColumnField<SortRow, NullableSortEntity, String> SORT_TEXT =
            ColumnField.of(SortRow.class, SORT_ROWS, "sortText", String.class);
    private static final ColumnField<SortRow, NullableSortEntity, LocalDateTime> SORT_TS =
            ColumnField.of(SortRow.class, SORT_ROWS, "sortTs", LocalDateTime.class);

    /** Every nullable-sort row, keyset-paged: 3 000 rows. */
    private static final ModelQuery.Builder<NullableSortEntity, Long, SortRow> SORT_ROWS_QUERY = ModelQuery
            .builder(SORT_ROWS, row -> new SortRow(row.get(SORT_ID), row.get(SORT_INT), row.get(SORT_TEXT),
                    row.get(SORT_TS)))
            .select(SelectSet.of(SORT_ID, SORT_INT, SORT_TEXT, SORT_TS))
            .primaryKey(PrimaryKey.of(SORT_ID))
            .keyset();

    // ---- AC-PAG-05

    @TckTest
    void ac_pag_05_keyset_export_over_ties_yields_every_row_exactly_once(TckDatabase db) {
        // 50 product codes over 20 000 items: every sort key is shared by 400 rows, so only the appended primary key
        // tells a cursor apart from the rows tied with it. Neither page size is a multiple of 400, so ties straddle
        // every boundary. The key follows the direction of the last order column (R-PAG-04).
        for (boolean ascending : List.of(true, false)) {
            var byProduct = ITEM_ROWS.orderBy(ascending ? ITEM_PRODUCT.asc() : ITEM_PRODUCT.desc()).build();
            Comparator<ItemRow> order = Comparator.comparing(ItemRow::product).thenComparing(ItemRow::id);
            for (int pageSize : new int[] {333, 1_000}) {
                List<ItemRow> rows = new ArrayList<>();
                withExecutor(db, OrderItemEntity.class, executor -> assertThat(
                        executor.export(byProduct, ExportOptions.of(pageSize), page -> page, rows::add))
                        .isEqualTo(TckFixture.ORDER_ITEMS));
                assertThat(rows).extracting(ItemRow::id).as("%s, page size %d", ascending ? "asc" : "desc", pageSize)
                        .hasSize(TckFixture.ORDER_ITEMS).doesNotHaveDuplicates()
                        .allMatch(id -> id >= 1 && id <= TckFixture.ORDER_ITEMS);
                assertThat(rows).isSortedAccordingTo(ascending ? order : order.reversed());
            }
        }
    }

    @TckTest
    void ac_pag_05_each_keyset_page_starts_after_the_last_row_of_the_page_before(TckDatabase db) {
        // 400 items of product P001 over nine quantities, pages of 150: the SQL shows the first page unrestricted and
        // the later ones restricted to the rows after the cursor, the primary key descending like the quantity.
        var p001 = ITEM_ROWS.where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ITEM_QUANTITY.desc())
                .build();
        List<ItemRow> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-05-keyset-tie-breaker", ds -> withExecutor(ds,
                OrderItemEntity.class, executor -> executor.export(p001, ExportOptions.of(150), page -> page,
                        rows::add)));
        assertThat(sql).hasSize(3);
        assertThat(rows).hasSize(TckFixture.ORDER_ITEMS / 50).extracting(ItemRow::id).doesNotHaveDuplicates();
        assertThat(rows).isSortedAccordingTo(
                Comparator.comparing(ItemRow::quantity).thenComparing(ItemRow::id).reversed());
    }

    @TckTest
    void ac_pag_05_keyset_export_stops_at_the_limit_and_a_zero_limit_runs_no_query(TckDatabase db) {
        var byId = ITEM_ROWS.orderBy(ITEM_ID.asc()).build();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-05-keyset-zero-limit", ds -> withExecutor(ds,
                OrderItemEntity.class, executor -> assertThat(executor.export(byId,
                        ExportOptions.of(100).withLimit(Limit.of(0)), page -> page, row -> {})).isZero()));
        assertThat(sql).isEmpty();

        List<Long> ids = new ArrayList<>();
        withExecutor(db, OrderItemEntity.class, executor -> assertThat(executor.export(byId,
                ExportOptions.of(500).withLimit(Limit.of(1_234)), page -> page, row -> ids.add(row.id())))
                .isEqualTo(1_234));
        assertThat(ids).containsExactlyElementsOf(LongStream.rangeClosed(1, 1_234).boxed().toList());
    }

    // ---- AC-QA-03

    @TckTest
    void ac_qa_03_keyset_export_without_the_tie_breaker_would_lose_rows_tied_across_a_page_boundary(TckDatabase db) {
        // R-QA-07: every row of P001 shares one order value and the pages of 7 never hold them all, so a cursor built
        // from the order column alone (no appended primary key) skips or repeats rows. The whole export is checked,
        // as a multiset of primary keys, in both directions of the last order column.
        List<Long> expected = new ArrayList<>();
        inSession(db, em -> expected.addAll(em.createQuery("select i.id from OrderItemEntity i"
                + " where i.productCode = 'P001'", Long.class).getResultList()));
        assertThat(expected).hasSize(TckFixture.ORDER_ITEMS / 50);
        for (boolean ascending : List.of(true, false)) {
            var tied = ITEM_ROWS.where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001")))
                    .orderBy(ascending ? ITEM_PRODUCT.asc() : ITEM_PRODUCT.desc())
                    .build();
            List<Long> visited = new ArrayList<>();
            withExecutor(db, OrderItemEntity.class, executor -> assertThat(
                    executor.export(tied, ExportOptions.of(7), page -> page, row -> visited.add(row.id())))
                    .isEqualTo(expected.size()));
            assertThat(visited).as(ascending ? "asc" : "desc").containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    // ---- AC-PAG-06

    @TckTest
    void ac_pag_06_keyset_ordering_by_a_filter_only_or_unselected_column_works(TckDatabase db) {
        // The country is filter-only and the total is outside the column set: the engine selects both, so the cursor
        // reads them from the row (R-PAG-04, D-29). Eight countries over 5 000 orders tie heavily.
        var byCountry = ORDER_ROWS.orderBy(ORDER_COUNTRY.asc()).build();
        var byTotal = ORDER_ROWS.orderBy(ORDER_TOTAL.desc()).build();
        assertThat(byCountry.select().fields()).doesNotContain(ORDER_COUNTRY, ORDER_TOTAL);
        List<Long> expectedByCountry = new ArrayList<>();
        List<Long> expectedByTotal = new ArrayList<>();
        inSession(db, em -> {
            expectedByCountry.addAll(em.createQuery("select o.id from OrderEntity o join o.customer c"
                    + " order by c.country, o.id", Long.class).getResultList());
            expectedByTotal.addAll(em.createQuery("select o.id from OrderEntity o order by o.total desc, o.id desc",
                    Long.class).getResultList());
        });
        for (int pageSize : new int[] {97, 1_000}) {
            List<Long> country = new ArrayList<>();
            List<Long> total = new ArrayList<>();
            withExecutor(db, OrderEntity.class, executor -> {
                executor.export(byCountry, ExportOptions.of(pageSize), page -> page, row -> country.add(row.id()));
                executor.export(byTotal, ExportOptions.of(pageSize), page -> page, row -> total.add(row.id()));
            });
            assertThat(country).as("by country, page size %d", pageSize).hasSize(TckFixture.ORDERS)
                    .containsExactlyElementsOf(expectedByCountry);
            assertThat(total).as("by total, page size %d", pageSize).hasSize(TckFixture.ORDERS)
                    .containsExactlyElementsOf(expectedByTotal);
        }
    }

    // ---- AC-PAG-07

    @TckTest
    void ac_pag_07_a_null_keyset_column_throws_mq2202_by_default(TckDatabase db) {
        // Whichever end the database sorts NULLs to, the export reaches them and refuses, instead of ending early
        // without them (INV-5). Second-column NULLs sit inside the groups of the first column.
        List<OrderField<SortRow, ?>[]> orders = List.of(
                orders(SORT_INT.asc()),
                orders(SORT_INT.desc()),
                orders(SORT_TEXT.asc().nullsFirst(), SORT_TS.asc()),
                orders(SORT_TEXT.desc().nullsLast(), SORT_TS.desc()));
        for (OrderField<SortRow, ?>[] order : orders) {
            String column = order[order.length - 1].column().name();
            var q = SORT_ROWS_QUERY.orderBy(order).build();
            withExecutor(db, NullableSortEntity.class, executor -> assertThatThrownBy(
                    () -> executor.export(q, ExportOptions.of(100), page -> page, row -> {}))
                    .as("order ending in %s", column)
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2202))
                    .hasMessageStartingWith(MqCode.MQ2202.code() + ": SortRow:")
                    .hasMessageContaining(column));
        }
        // The same order over the rows without NULLs pages through all of them.
        var nonNull = SORT_ROWS_QUERY.where(f -> f.isNotNull(SORT_INT)).orderBy(SORT_INT.asc()).build();
        List<SortRow> rows = new ArrayList<>();
        withExecutor(db, NullableSortEntity.class,
                executor -> executor.export(nonNull, ExportOptions.of(100), page -> page, rows::add));
        assertThat(rows).extracting(SortRow::id).hasSize(TckFixture.NULLABLE_SORT_ROWS * 4 / 5)
                .doesNotHaveDuplicates();
        assertThat(rows).isSortedAccordingTo(Comparator.comparing(SortRow::sortInt).thenComparing(SortRow::id));
    }

    @TckTest
    void ac_pag_07_every_explicit_null_precedence_combination_pages_every_row_once_in_order(TckDatabase db) {
        // ASC/DESC x FIRST/LAST on each nullable type, with cursors landing on NULL and non-NULL keys (R-PRF-10).
        // 600, 428 and 272 NULLs against pages of 97 put several cursors on each side.
        List<SortRow> all = jdbc(db);
        List<Column<?>> columns = List.of(
                new Column<>(SORT_INT, SortRow::sortInt),
                new Column<>(SORT_TEXT, SortRow::sortText),
                new Column<>(SORT_TS, SortRow::sortTs));
        for (Column<?> column : columns) {
            for (boolean ascending : List.of(true, false)) {
                for (NullPrecedence nulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
                    Sorted<?> key = column.sorted(ascending, nulls);
                    checkEveryRowOnce(db, all, List.of(key));
                }
            }
        }
    }

    @TckTest
    void ac_pag_07_explicit_null_precedence_holds_across_two_nullable_keyset_columns(TckDatabase db) {
        // Within each sort_text group, sort_ts holds one value and some NULLs, so the branches that hold the first
        // column equal to a NULL or non-NULL cursor value and compare the second are all taken (R-PRF-09).
        List<SortRow> all = jdbc(db);
        var text = new Column<>(SORT_TEXT, SortRow::sortText);
        var ts = new Column<>(SORT_TS, SortRow::sortTs);
        for (boolean textAscending : List.of(true, false)) {
            for (NullPrecedence textNulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
                for (boolean tsAscending : List.of(true, false)) {
                    for (NullPrecedence tsNulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
                        checkEveryRowOnce(db, all, List.of(text.sorted(textAscending, textNulls),
                                ts.sorted(tsAscending, tsNulls)));
                    }
                }
            }
        }
    }

    @TckTest
    void ac_pag_07_explicit_null_precedence_renders_the_null_branches(TckDatabase db) {
        // Ids 1..20, NULL on every fifth, pages of 4. Ascending NULLS LAST: non-NULL cursors add "or sort_int is
        // null", then the NULL cursor compares only the key among the NULLs. Descending NULLS FIRST: the NULL cursor
        // is followed by every non-NULL value, then non-NULL cursors compare plainly (R-PRF-09).
        var first20 = SORT_ROWS_QUERY.where(f -> f.lte(SORT_ID, 20L));
        var ascLast = first20.orderBy(SORT_INT.asc().nullsLast()).build();
        var descFirst = first20.orderBy(SORT_INT.desc().nullsFirst()).build();
        List<Long> ascIds = new ArrayList<>();
        List<Long> descIds = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-07-keyset-null-branches", ds -> withExecutor(ds,
                NullableSortEntity.class, executor -> {
                    executor.export(ascLast, ExportOptions.of(4), page -> page, row -> ascIds.add(row.id()));
                    executor.export(descFirst, ExportOptions.of(4), page -> page, row -> descIds.add(row.id()));
                }));
        assertThat(sql).hasSize(12);
        assertThat(ascIds).containsExactly(1L, 2L, 3L, 4L, 6L, 7L, 8L, 9L, 11L, 12L, 13L, 14L, 16L, 17L, 18L, 19L,
                5L, 10L, 15L, 20L);
        assertThat(descIds).containsExactly(20L, 15L, 10L, 5L, 19L, 18L, 17L, 16L, 14L, 13L, 12L, 11L, 9L, 8L, 7L,
                6L, 4L, 3L, 2L, 1L);
    }

    /** A nullable column and how a {@link SortRow} holds its value. */
    private record Column<C extends Comparable<? super C>>(ColumnField<SortRow, NullableSortEntity, C> field,
            Function<SortRow, C> value) {

        Sorted<C> sorted(boolean ascending, NullPrecedence nulls) {
            return new Sorted<>(this, ascending, nulls);
        }
    }

    /** One keyset column with its direction and explicit precedence, and the same order in Java. */
    private record Sorted<C extends Comparable<? super C>>(Column<C> column, boolean ascending, NullPrecedence nulls) {

        OrderField<SortRow, C> order() {
            return (ascending ? column.field().asc() : column.field().desc()).nulls(nulls);
        }

        Comparator<SortRow> comparator() {
            Comparator<C> values = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
            return Comparator.comparing(column.value(),
                    nulls == NullPrecedence.FIRST ? Comparator.nullsFirst(values) : Comparator.nullsLast(values));
        }

        @Override
        public String toString() {
            return column.field().name() + (ascending ? " asc" : " desc") + " nulls " + nulls;
        }
    }

    /**
     * Exports the nullable-sort rows keyset-paged by {@code keys} and asserts they arrive in exactly the order Java
     * sorts them, the id breaking ties in the direction of the last key; and that page cursors were NULL and not.
     */
    private static void checkEveryRowOnce(TckDatabase db, List<SortRow> all, List<Sorted<?>> keys) {
        Comparator<SortRow> order = null;
        for (Sorted<?> key : keys) {
            order = order == null ? key.comparator() : order.thenComparing(key.comparator());
        }
        Sorted<?> last = keys.get(keys.size() - 1);
        Comparator<SortRow> byId = Comparator.comparing(SortRow::id);
        order = order.thenComparing(last.ascending() ? byId : byId.reversed());
        List<Long> expected = all.stream().sorted(order).map(SortRow::id).toList();

        @SuppressWarnings("unchecked")
        OrderField<SortRow, ?>[] orderFields = keys.stream().map(Sorted::order).toArray(OrderField[]::new);
        var q = SORT_ROWS_QUERY.orderBy(orderFields).build();
        List<Long> ids = new ArrayList<>();
        List<Boolean> cursorIsNull = new ArrayList<>();
        withExecutor(db, NullableSortEntity.class, executor -> executor.export(q, ExportOptions.of(97), page -> {
            cursorIsNull.add(last.column().value().apply(page.get(page.size() - 1)) == null);
            return page;
        }, row -> ids.add(row.id())));
        assertThat(ids).as("%s", keys).containsExactlyElementsOf(expected);
        assertThat(cursorIsNull).as("cursors of %s", keys).contains(true, false);
    }

    @SafeVarargs
    private static OrderField<SortRow, ?>[] orders(OrderField<SortRow, ?>... orders) {
        return orders;
    }

    // ---- AC-PAG-12

    @TckTest
    void ac_pag_12_keyset_paging_selecting_through_a_to_many_join_throws_mq2204_naming_the_join(TckDatabase db) {
        var selected = ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ORDER_ID), row.get(ORDER_ITEM_PRODUCT)))
                .select(SelectSet.of(ORDER_ID, ORDER_ITEM_PRODUCT))
                .primaryKey(PrimaryKey.of(ORDER_ID))
                .keyset()
                .build();
        var ordered = ORDER_ROWS.orderBy(ORDER_ITEM_PRODUCT.asc()).build();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-12-keyset-to-many-refused", ds -> withExecutor(ds,
                OrderEntity.class, executor -> {
                    for (ModelQuery<OrderEntity, Long, OrderRow> q : List.of(selected, ordered)) {
                        assertThatThrownBy(() -> executor.export(q, ExportOptions.of(100), page -> page, row -> {}))
                                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2204))
                                .hasMessageStartingWith(MqCode.MQ2204.code() + ": OrderRow: keyset paging")
                                .hasMessageContaining("OrderEntity.items");
                    }
                }));
        assertThat(sql).as("refused before any query runs").isEmpty();
    }

    @TckTest
    void ac_pag_12_the_same_keyset_export_with_the_to_many_join_only_in_a_predicate_succeeds(TckDatabase db) {
        // Each order holding P001 holds it four times, so the join repeats each root four times in a row: within a
        // page the repeats are dropped, and the cursor is past all of them.
        var withItem = ORDER_ROWS.where(f -> f.eq(ORDER_ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ORDER_STATUS.asc())
                .build();
        List<Long> expected = new ArrayList<>();
        inSession(db, em -> expected.addAll(em.createQuery("select distinct o.id from OrderEntity o join o.items i"
                + " where i.productCode = 'P001'", Long.class).getResultList()));
        for (int pageSize : new int[] {3, 4, 7, 1_000}) {
            List<OrderRow> rows = new ArrayList<>();
            withExecutor(db, OrderEntity.class,
                    executor -> executor.export(withItem, ExportOptions.of(pageSize), page -> page, rows::add));
            assertThat(rows).extracting(OrderRow::id).as("page size %d", pageSize)
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(rows).isSortedAccordingTo(Comparator.comparing(OrderRow::status).thenComparing(OrderRow::id));
        }
    }

    // ---- AC-PAG-13

    @TckTest
    void ac_pag_13_a_keyset_page_repeating_a_key_of_the_page_before_throws_mq2205(TckDatabase db) {
        // The 400 items of P001 by quantity, in pages of 250: while page 1 is transformed, its first row's quantity
        // moves from 1 to 9, after the cursor, so page 2, which holds every row left, reads that row again. The
        // export runs in a transaction that sees its own update on every vendor, and rolls back to leave the fixture
        // as seeded (R-PAG-14).
        var p001 = ITEM_ROWS.where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001"))).orderBy(ITEM_QUANTITY.asc()).build();
        List<List<ItemRow>> pages = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    ModelQueryExecutor<OrderItemEntity> executor =
                            ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults());
                    assertThatThrownBy(() -> executor.export(p001, ExportOptions.of(250), page -> {
                        pages.add(page);
                        if (pages.size() == 1) {
                            assertThat(page.get(0).quantity()).isEqualTo(1);
                            em.createNativeQuery("UPDATE order_items SET quantity = 9 WHERE id = :id")
                                    .setParameter("id", page.get(0).id())
                                    .executeUpdate();
                        }
                        return page;
                    }, row -> {}))
                            .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ2205))
                            .hasMessageStartingWith(MqCode.MQ2205.code() + ": ItemRow:");
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
        assertThat(pages).as("page 2 never reaches pageTransformer").hasSize(1);
        assertThat(pages.get(0)).hasSize(250);
        // A key a predicate's to-many join repeats within a page is still dropped, not refused: each order holding
        // P001 holds it four times, so pages of 3 split the repeats of one order across a boundary (R-PAG-02).
        var withItem = ORDER_ROWS.where(f -> f.eq(ORDER_ITEM_PRODUCT, Optional.of("P001"))).build();
        List<Long> orders = new ArrayList<>();
        withExecutor(db, OrderEntity.class, executor -> executor.export(withItem, ExportOptions.of(3), page -> page,
                row -> orders.add(row.id())));
        assertThat(orders).isNotEmpty().doesNotHaveDuplicates();
    }

    // ---- support

    private static List<SortRow> jdbc(TckDatabase db) {
        List<SortRow> result = new ArrayList<>();
        try (Connection c = db.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT id, sort_int, sort_text, sort_ts FROM nullable_sort_rows")) {
            while (rs.next()) {
                int sortInt = rs.getInt(2);
                Integer boxedInt = rs.wasNull() ? null : sortInt;
                result.add(new SortRow(rs.getLong(1), boxedInt, rs.getString(3),
                        rs.getObject(4, LocalDateTime.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(result).hasSize(TckFixture.NULLABLE_SORT_ROWS);
        return result;
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
}
