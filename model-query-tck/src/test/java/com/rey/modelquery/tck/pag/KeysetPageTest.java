package com.rey.modelquery.tck.pag;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetCursorCodec;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CompositeKeyItemEntity;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.KeysetTypeEntity;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.fch.CustomerOrders;
import com.rey.modelquery.tck.fch.Line;
import com.rey.modelquery.tck.fch.OrderLines;
import com.rey.modelquery.tck.fch.QCustomerOrders;
import com.rey.modelquery.tck.fch.QLine;
import com.rey.modelquery.tck.fch.QOrderLines;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.CRC32C;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** Keyset {@code page}: the cursors, {@code before}, the flags and the checks before any statement (engine/21 §2). */
class KeysetPageTest {

    record ItemRow(Long id, String product, Integer quantity) {}

    record OrderRow(Long id, String status) {}

    record CompositeRow(Integer tenantId, Integer itemNo, String label) {}

    record SortRow(Long id, Integer sortInt, String sortText, LocalDateTime sortTs) {}

    record CursorTypeRow(Long id, BigDecimal amount, Timestamp stamp, UUID token, byte[] payload) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemRow.class, ITEMS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(ItemRow.class, ITEMS, "productCode", String.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(ItemRow.class, ITEMS, "quantity", Integer.class);

    private static final ModelQuery.Builder<OrderItemEntity, Long, ItemRow> ITEM_ROWS = ModelQuery
            .builder(ITEMS, row -> new ItemRow(row.get(ITEM_ID), row.get(ITEM_PRODUCT), row.get(ITEM_QUANTITY)))
            .select(SelectSet.of(ITEM_ID, ITEM_PRODUCT, ITEM_QUANTITY))
            .primaryKey(PrimaryKey.of(ITEM_ID))
            .keyset();

    /** The 400 items of P001: one tie group of 400 that every page boundary falls inside. */
    private static final ModelQuery.Builder<OrderItemEntity, Long, ItemRow> P001 =
            ITEM_ROWS.where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001")));

    private static final TableField<CompositeKeyItemEntity, CompositeKeyItemEntity> COMPOSITE =
            TableField.root(CompositeKeyItemEntity.class);
    private static final ColumnField<CompositeRow, CompositeKeyItemEntity, Integer> TENANT =
            ColumnField.of(CompositeRow.class, COMPOSITE, "tenantId", Integer.class);
    private static final ColumnField<CompositeRow, CompositeKeyItemEntity, Integer> ITEM_NO =
            ColumnField.of(CompositeRow.class, COMPOSITE, "itemNo", Integer.class);
    private static final ColumnField<CompositeRow, CompositeKeyItemEntity, String> LABEL =
            ColumnField.of(CompositeRow.class, COMPOSITE, "label", String.class);

    private static final ModelQuery.Builder<CompositeKeyItemEntity, List<Object>, CompositeRow> COMPOSITE_ROWS =
            ModelQuery.builder(COMPOSITE,
                            row -> new CompositeRow(row.get(TENANT), row.get(ITEM_NO), row.get(LABEL)))
                    .select(SelectSet.of(TENANT, ITEM_NO, LABEL))
                    .primaryKey(PrimaryKey.composite(TENANT, ITEM_NO))
                    .keyset();

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(ORDERS, "items", INNER);
    private static final ColumnField<OrderRow, OrderEntity, Long> ORDER_ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> ORDER_STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> ORDER_TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<OrderRow, OrderEntity, OrderStatus> ORDER_STATUS_CODE =
            ColumnField.of(OrderRow.class, ORDERS, "statusCode", OrderStatus.class);
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

    private static final ModelQuery.Builder<NullableSortEntity, Long, SortRow> SORT_ROWS_QUERY = ModelQuery
            .builder(SORT_ROWS, row -> new SortRow(row.get(SORT_ID), row.get(SORT_INT), row.get(SORT_TEXT),
                    row.get(SORT_TS)))
            .select(SelectSet.of(SORT_ID, SORT_INT, SORT_TEXT, SORT_TS))
            .primaryKey(PrimaryKey.of(SORT_ID))
            .keyset();

    private static final ModelQueryConfig HONOUR =
            ModelQueryConfig.defaults().keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);

    private static final TableField<KeysetTypeEntity, KeysetTypeEntity> TYPES =
            TableField.root(KeysetTypeEntity.class);
    private static final ColumnField<CursorTypeRow, KeysetTypeEntity, Long> TYPE_ID =
            ColumnField.of(CursorTypeRow.class, TYPES, "id", Long.class);
    private static final ColumnField<CursorTypeRow, KeysetTypeEntity, BigDecimal> TYPE_AMOUNT =
            ColumnField.of(CursorTypeRow.class, TYPES, "amount", BigDecimal.class);
    private static final ColumnField<CursorTypeRow, KeysetTypeEntity, Timestamp> TYPE_STAMP =
            ColumnField.of(CursorTypeRow.class, TYPES, "stamp", Timestamp.class);
    private static final ColumnField<CursorTypeRow, KeysetTypeEntity, UUID> TYPE_TOKEN =
            ColumnField.of(CursorTypeRow.class, TYPES, "token", UUID.class);
    private static final ColumnField<CursorTypeRow, KeysetTypeEntity, byte[]> TYPE_PAYLOAD =
            ColumnField.of(CursorTypeRow.class, TYPES, "payload", byte[].class);
    private static final ColumnField<CursorTypeRow, KeysetTypeEntity, KeysetTypeEntity.Shape> TYPE_SHAPE =
            ColumnField.of(CursorTypeRow.class, TYPES, "shape", KeysetTypeEntity.Shape.class);

    private static final ModelQuery.Builder<KeysetTypeEntity, Long, CursorTypeRow> TYPE_ROWS = ModelQuery
            .builder(TYPES, row -> new CursorTypeRow(row.get(TYPE_ID), row.get(TYPE_AMOUNT), row.get(TYPE_STAMP),
                    row.get(TYPE_TOKEN), row.get(TYPE_PAYLOAD)))
            .select(SelectSet.of(TYPE_ID, TYPE_AMOUNT, TYPE_STAMP, TYPE_TOKEN, TYPE_PAYLOAD))
            .primaryKey(PrimaryKey.of(TYPE_ID))
            .keyset();

    // ---- AC-PAG-15

    @TckTest
    void ac_pag_15_a_first_after_walk_over_ties_visits_every_row_once_in_the_offset_page_order(TckDatabase db) {
        // 400 items share product P001, so every boundary falls inside one tie group and only the appended primary
        // key tells the cursor apart from the rows tied with it; pages of 97 and 133 straddle it differently. The
        // key follows the last order column's direction (R-PAG-04, R-PAG-16).
        for (boolean ascending : List.of(true, false)) {
            var q = P001.orderBy(ascending ? ITEM_PRODUCT.asc() : ITEM_PRODUCT.desc()).build();
            Comparator<ItemRow> order = Comparator.comparing(ItemRow::product).thenComparing(ItemRow::id);
            Comparator<ItemRow> expected = ascending ? order : order.reversed();
            for (int pageSize : new int[] {97, 133}) {
                withExecutor(db, OrderItemEntity.class, executor -> {
                    List<KeysetSlice<ItemRow>> pages = forward(executor, q, pageSize);
                    List<ItemRow> rows = flatten(pages);
                    assertThat(pages.get(0).hasPrevious()).isFalse();
                    assertThat(pages.get(pages.size() - 1).hasNext()).isFalse();
                    assertThat(rows).as("%s, page size %d", ascending ? "asc" : "desc", pageSize)
                            .extracting(ItemRow::id).hasSize(400).doesNotHaveDuplicates();
                    assertThat(rows).isSortedAccordingTo(expected);
                });
            }
        }
    }

    @TckTest
    void ac_pag_15_a_first_after_walk_over_a_composite_primary_key_visits_every_row_once(TckDatabase db) {
        // 2 000 composite-key rows over 25 labels of 80 rows each; the primary key closes the order in its last
        // direction, so the walk is the offset page's order (R-PAG-04, R-PAG-16).
        for (boolean ascending : List.of(true, false)) {
            var q = COMPOSITE_ROWS.orderBy(ascending ? LABEL.asc() : LABEL.desc()).build();
            Comparator<CompositeRow> order = Comparator.comparing(CompositeRow::label)
                    .thenComparing(CompositeRow::tenantId).thenComparing(CompositeRow::itemNo);
            Comparator<CompositeRow> expected = ascending ? order : order.reversed();
            withExecutor(db, CompositeKeyItemEntity.class, executor -> {
                List<KeysetSlice<CompositeRow>> pages = forward(executor, q, 201);
                assertThat(pages.get(pages.size() - 1).hasNext()).isFalse();
                List<CompositeRow> rows = flatten(pages);
                assertThat(rows).hasSize(TckFixture.COMPOSITE_KEY_ITEMS).doesNotHaveDuplicates();
                assertThat(rows).isSortedAccordingTo(expected);
            });
        }
    }

    @TckTest
    void ac_pag_15_an_exact_size_remainder_gives_has_next_false(TckDatabase db) {
        // 500 items paged by 500 and by 250: the last page holds exactly the remainder, so nothing follows it.
        var fiveHundred = ITEM_ROWS.where(f -> f.lte(ITEM_ID, 500L)).orderBy(ITEM_ID.asc()).build();
        withExecutor(db, OrderItemEntity.class, executor -> {
            KeysetSlice<ItemRow> single = executor.page(fiveHundred, KeysetSpec.first(500));
            assertThat(single.content()).hasSize(500);
            assertThat(single.hasNext()).isFalse();
            assertThat(single.hasPrevious()).isFalse();
            assertThat(single.nextCursor()).isEmpty();

            List<KeysetSlice<ItemRow>> pages = forward(executor, fiveHundred, 250);
            assertThat(pages).hasSize(2);
            assertThat(pages.get(0).content()).hasSize(250);
            assertThat(pages.get(0).hasNext()).isTrue();
            assertThat(pages.get(1).content()).hasSize(250);
            assertThat(pages.get(1).hasNext()).isFalse();
        });
    }

    // ---- AC-PAG-16

    @TckTest
    void ac_pag_16_before_returns_the_page_before_in_query_order_and_walking_back_reverses_the_forward_walk(
            TckDatabase db) {
        // Forward pages of 97 over P001's 400 rows; then, from each page's own previous cursor, `before` must return
        // exactly the page before, with the furthest look-ahead row dropped, and a full backward walk must reverse
        // the forward walk (R-PAG-20, R-PAG-21).
        var q = P001.orderBy(ITEM_QUANTITY.asc()).build();
        withExecutor(db, OrderItemEntity.class, executor -> {
            List<KeysetSlice<ItemRow>> pages = forward(executor, q, 97);
            assertThat(pages).hasSizeGreaterThan(2);
            assertThat(pages.get(0).hasPrevious()).isFalse();
            assertThat(pages.get(0).previousCursor()).isEmpty();
            for (int i = 1; i < pages.size(); i++) {
                KeysetSlice<ItemRow> page = pages.get(i);
                assertThat(page.hasPrevious()).as("page %d has a previous", i).isTrue();
                KeysetSlice<ItemRow> before =
                        executor.page(q, KeysetSpec.before(page.previousCursor().orElseThrow(), 97));
                assertThat(before.content()).as("the page before %d", i)
                        .containsExactlyElementsOf(pages.get(i - 1).content());
            }
            List<ItemRow> forwardRows = flatten(pages);
            assertThat(walkBack(executor, q, 97, pages)).as("walking back reverses the forward walk")
                    .extracting(ItemRow::id).containsExactlyElementsOf(ids(forwardRows, ItemRow::id));
        });
    }

    @TckTest
    void ac_pag_16_a_before_page_from_a_middle_cursor_carries_both_neighbour_cursors(TckDatabase db) {
        // `before` gives hasPrevious == read > size and hasNext always true; a page reached backwards from a middle
        // cursor, with a page on each side, carries both cursors (R-PAG-21).
        var q = P001.orderBy(ITEM_QUANTITY.asc()).build();
        withExecutor(db, OrderItemEntity.class, executor -> {
            List<KeysetSlice<ItemRow>> pages = forward(executor, q, 97);
            assertThat(pages).hasSizeGreaterThan(2);
            KeysetSlice<ItemRow> middle = pages.get(2);
            KeysetSlice<ItemRow> backwards =
                    executor.page(q, KeysetSpec.before(middle.previousCursor().orElseThrow(), 97));
            assertThat(backwards.content()).containsExactlyElementsOf(pages.get(1).content());
            assertThat(backwards.hasPrevious()).as("a page precedes page 2").isTrue();
            assertThat(backwards.hasNext()).isTrue();
            assertThat(backwards.previousCursor()).isPresent();
            assertThat(backwards.nextCursor()).isPresent();
        });
    }

    // ---- AC-PAG-17

    @TckTest
    void ac_pag_17_every_asc_desc_and_first_last_combination_pages_once_each_way(TckDatabase db) {
        // 600 nulls on sort_int against pages of 97: a forward walk then a backward walk must each visit every row
        // once, in the order Java sorts them with that precedence and the id in the last key's direction (R-PAG-05).
        for (boolean ascending : List.of(true, false)) {
            for (NullPrecedence nulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
                var q = SORT_ROWS_QUERY.orderBy(nullOrder(SORT_INT, ascending, nulls)).build();
                Comparator<SortRow> expected = comparator(ascending, nulls);
                withExecutor(db, NullableSortEntity.class, executor -> {
                    List<KeysetSlice<SortRow>> pages = forward(executor, q, 97);
                    List<SortRow> rows = flatten(pages);
                    assertThat(rows).as("%s %s", ascending ? "asc" : "desc", nulls)
                            .extracting(SortRow::id).hasSize(TckFixture.NULLABLE_SORT_ROWS).doesNotHaveDuplicates();
                    assertThat(rows).isSortedAccordingTo(expected);
                    assertThat(rows.stream().filter(row -> row.sortInt() == null).count()).isEqualTo(600);
                    assertThat(walkBack(executor, q, 97, pages)).extracting(SortRow::id)
                            .containsExactlyElementsOf(ids(rows, SortRow::id));
                });
            }
        }
    }

    @TckTest
    void ac_pag_17_a_default_precedence_null_throws_mq2202(TckDatabase db) {
        // sort_int has 600 NULLs and no explicit precedence: whichever end the database sorts them to, a walk over
        // the rows reaches them and refuses, instead of ending early without them (R-PAG-05, R-PAG-22). The predicate
        // keeps its OR IS NULL branch, so the NULL is read even where the database's ordering would skip it.
        var q = SORT_ROWS_QUERY.orderBy(SORT_INT.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, NullableSortEntity.class, executor -> {
            KeysetSpec spec = KeysetSpec.first(97);
            ModelQueryExecutionException refused = null;
            while (refused == null) {
                try {
                    KeysetSlice<SortRow> page = executor.page(q, spec);
                    if (!page.hasNext()) {
                        break;
                    }
                    spec = KeysetSpec.after(page.nextCursor().orElseThrow(), 97);
                } catch (ModelQueryExecutionException e) {
                    refused = e;
                }
            }
            assertThat(refused).as("a walk reaches the NULLs and refuses").isNotNull();
            assertThat(refused.code()).isEqualTo(MqCode.MQ2202);
            assertThat(refused).hasMessageContaining("keyset column sortInt is null in an exported row");
        }));
        assertThat(sql).as("the row is read, so a statement ran").isNotEmpty();
    }

    @TckTest
    void ac_pag_17_a_hibernate_default_null_ordering_key_reverses_under_before(TckDatabase db) {
        // Hibernate's default_null_ordering puts a bare order's NULLs at that end in both directions (D-36), so a
        // `before` page must flip its precedence explicitly or the reversed order would skip the NULLs (R-PAG-22).
        for (String nullOrdering : List.of("first", "last")) {
            NullPrecedence configured = nullOrdering.equals("first") ? NullPrecedence.FIRST : NullPrecedence.LAST;
            var q = SORT_ROWS_QUERY.orderBy(SORT_INT.asc()).build();
            try (SessionFactory sf = JoinTestSupport.sessionFactory(db, nullOrdering)) {
                sf.inSession(em -> {
                    ModelQueryExecutor<NullableSortEntity> executor =
                            ModelQueryExecutor.create(em, NullableSortEntity.class, HONOUR);
                    List<KeysetSlice<SortRow>> pages = forward(executor, q, 97);
                    List<SortRow> rows = flatten(pages);
                    assertThat(rows).as("default_null_ordering %s", nullOrdering)
                            .extracting(SortRow::id).hasSize(TckFixture.NULLABLE_SORT_ROWS).doesNotHaveDuplicates();
                    assertThat(rows).isSortedAccordingTo(comparator(true, configured));
                    assertThat(walkBack(executor, q, 97, pages)).extracting(SortRow::id)
                            .containsExactlyElementsOf(ids(rows, SortRow::id));
                });
            }
        }
    }

    @TckTest
    void ac_pag_17_before_renders_a_database_default_bare_and_an_explicit_precedence_reversed(TckDatabase db) {
        // Under `before` a database-default key may render bare reversed (the database flips it), while an explicit
        // precedence renders flipped and explicit; always explicit would lose the index on MySQL (R-PAG-22, D-35).
        var bare = SORT_ROWS_QUERY.orderBy(SORT_INT.asc()).build();
        var explicit = SORT_ROWS_QUERY.orderBy(SORT_INT.asc().nullsFirst()).build();
        List<String> sql = SqlSnapshots.assertMatches(db, "pag-17-keyset-page-before-null-rendering",
                ds -> withExecutor(ds, NullableSortEntity.class, HONOUR, executor -> {
                    for (var q : List.of(bare, explicit)) {
                        String cursor = executor.page(q, KeysetSpec.first(4)).nextCursor().orElseThrow();
                        executor.page(q, KeysetSpec.before(cursor, 4));
                    }
                }));
        assertThat(sql).as("both pages ran a statement").hasSizeGreaterThanOrEqualTo(4);
    }

    // ---- AC-PAG-18

    @TckTest
    void ac_pag_18_every_cursor_type_round_trips_and_pages_with_no_repeat_or_skip(TckDatabase db) {
        // One cursor carries a scale-4 BigDecimal, a microsecond Timestamp, a UUID, a byte[] and the primary key; the
        // walk over 1 000 rows visits each once, and the decoded cursor keeps every value's Java type and detail
        // (R-PAG-17, R-PAG-18).
        var q = TYPE_ROWS.orderBy(TYPE_AMOUNT.asc(), TYPE_STAMP.asc(), TYPE_TOKEN.asc(), TYPE_PAYLOAD.asc()).build();
        withExecutor(db, KeysetTypeEntity.class, executor -> {
            List<KeysetSlice<CursorTypeRow>> pages = forward(executor, q, 97);
            List<CursorTypeRow> rows = flatten(pages);
            assertThat(rows).extracting(CursorTypeRow::id).hasSize(TckFixture.KEYSET_TYPES).doesNotHaveDuplicates();
            assertThat(rows).isSortedAccordingTo(Comparator.comparing(CursorTypeRow::amount));
            Object[] values = KeysetSpec.after(pages.get(0).nextCursor().orElseThrow(), 7).values();
            assertThat(values).hasSize(5);
            assertThat(values[0]).isInstanceOf(BigDecimal.class);
            assertThat(((BigDecimal) values[0]).scale()).as("the decimal keeps its scale").isEqualTo(4);
            assertThat(values[1]).isInstanceOf(Timestamp.class);
            assertThat(((Timestamp) values[1]).getNanos()).as("the timestamp keeps its nanos").isNotZero();
            assertThat(values[2]).isInstanceOf(UUID.class);
            assertThat(values[3]).isInstanceOf(byte[].class);
            assertThat((byte[]) values[3]).hasSize(2);
            assertThat(values[4]).isInstanceOf(Long.class);
        });
    }

    @TckTest
    void ac_pag_18_an_enum_through_a_converter_pages_and_round_trips(TckDatabase db) {
        // orders.status is an OrderStatus through a ColumnConverter (the enum case of R-PAG-17); 5 000 rows over four
        // statuses make ties that straddle every boundary, and the cursor carries the constant by name.
        var q = ORDER_ROWS.orderBy(ORDER_STATUS_CODE.asc()).build();
        Comparator<OrderRow> order = Comparator.comparing(OrderRow::status).thenComparing(OrderRow::id);
        withExecutor(db, OrderEntity.class, executor -> {
            List<KeysetSlice<OrderRow>> pages = forward(executor, q, 997);
            List<OrderRow> rows = flatten(pages);
            assertThat(rows).extracting(OrderRow::id).hasSize(TckFixture.ORDERS).doesNotHaveDuplicates();
            assertThat(rows).isSortedAccordingTo(order);
            Object[] values = KeysetSpec.after(pages.get(0).nextCursor().orElseThrow(), 7).values();
            assertThat(values[0]).as("the enum travels by name").isInstanceOf(String.class);
            assertThat(OrderStatus.valueOf((String) values[0])).isNotNull();
        });
    }

    @TckTest
    void ac_pag_18_a_key_type_no_codec_carries_throws_mq2210_without_querying(TckDatabase db) {
        // Shape is a @Convert value class: no cursor codec knows it, so ordering by it is refused before any query
        // runs (R-PAG-17).
        var q = TYPE_ROWS.orderBy(TYPE_SHAPE.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, KeysetTypeEntity.class,
                executor -> assertThatThrownBy(() -> executor.page(q, KeysetSpec.first(5)))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2210))
                        .hasMessageStartingWith(MqCode.MQ2210.code() + ": CursorTypeRow: keyset column shape")));
        assertThat(sql).as("refused before any query runs").isEmpty();
    }

    // ---- AC-PAG-19

    @TckTest
    void ac_pag_19_every_malformed_cursor_throws_mq2208_at_keyset_spec_and_nothing_else() {
        String valid = KeysetCursorCodec.encode(new byte[8], new Object[] {"P001", 42, new BigDecimal("3.14")});
        assertThatThrownBy(() -> KeysetSpec.after(null, 5)).as("a null cursor")
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2208));
        for (String cursor : List.of("", " ", "not base64!", valid.substring(0, valid.length() - 3), valid + "x",
                "A".repeat(8193), unknownVersion(valid))) {
            assertThatThrownBy(() -> KeysetSpec.after(cursor, 5)).as("cursor [%s]", cursor)
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2208));
            assertThatThrownBy(() -> KeysetSpec.before(cursor, 5)).as("cursor [%s] before", cursor)
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2208));
        }
        // A changed base64 character decodes to different bytes, so the CRC32C never matches (R-PAG-18).
        char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        for (int i = 0; i < valid.length(); i++) {
            char replacement = valid.charAt(i) == alphabet[0] ? alphabet[1] : alphabet[0];
            String flipped = valid.substring(0, i) + replacement + valid.substring(i + 1);
            assertThatThrownBy(() -> KeysetSpec.after(flipped, 5)).as("character %d flipped", i)
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2208));
        }
    }

    // ---- AC-PAG-20

    @TckTest
    void ac_pag_20_a_cursor_from_another_order_throws_mq2209_before_querying(TckDatabase db) {
        var asc = ORDER_ROWS.orderBy(ORDER_STATUS.asc()).build();
        String cursor = firstNextCursor(db, OrderEntity.class, asc, 5);
        var anotherSort = ORDER_ROWS.orderBy(ORDER_TOTAL.asc()).build();
        var anotherDirection = ORDER_ROWS.orderBy(ORDER_STATUS.desc()).build();
        var nullsFirst = SORT_ROWS_QUERY.orderBy(SORT_INT.asc().nullsFirst()).build();
        String precedenceCursor = firstNextCursor(db, NullableSortEntity.class, nullsFirst, 5);
        var nullsLast = SORT_ROWS_QUERY.orderBy(SORT_INT.asc().nullsLast()).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
            assertMq2209(executor, anotherSort, cursor);
            assertMq2209(executor, anotherDirection, cursor);
        }));
        assertThat(sql).as("refused before any query runs").isEmpty();

        List<String> precedenceSql = SqlSnapshots.capture(db, ds -> withExecutor(ds, NullableSortEntity.class,
                executor -> assertMq2209(executor, nullsLast, precedenceCursor)));
        assertThat(precedenceSql).isEmpty();

        // Another entity: the fingerprint names the root entity (R-PAG-19).
        var itemOrder = ITEM_ROWS.orderBy(ITEM_PRODUCT.asc()).build();
        List<String> entitySql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderItemEntity.class,
                executor -> assertMq2209(executor, itemOrder, cursor)));
        assertThat(entitySql).isEmpty();
    }

    @TckTest
    void ac_pag_20_the_same_order_under_another_filter_is_accepted(TckDatabase db) {
        var all = ORDER_ROWS.orderBy(ORDER_STATUS.asc()).build();
        String cursor = firstNextCursor(db, OrderEntity.class, all, 40);
        var paid = ORDER_ROWS.where(f -> f.eq(ORDER_STATUS, Optional.of("PAID")))
                .orderBy(ORDER_STATUS.asc())
                .build();
        withExecutor(db, OrderEntity.class, executor -> {
            KeysetSlice<OrderRow> page = executor.page(paid, KeysetSpec.after(cursor, 20));
            assertThat(page.content()).isNotEmpty().allMatch(row -> row.status().equals("PAID"));
            assertThat(page.content()).isSortedAccordingTo(Comparator.comparing(OrderRow::id));
        });
    }

    // ---- AC-PAG-21

    @TckTest
    void ac_pag_21_first_on_an_empty_result_returns_an_empty_page_with_no_cursors(TckDatabase db) {
        var none = ITEM_ROWS.where(f -> f.eq(ITEM_ID, Optional.of(-1L))).build();
        withExecutor(db, OrderItemEntity.class, executor -> {
            KeysetSlice<ItemRow> page = executor.page(none, KeysetSpec.first(10));
            assertThat(page.content()).isEmpty();
            assertThat(page.size()).isEqualTo(10);
            assertThat(page.hasPrevious()).isFalse();
            assertThat(page.hasNext()).isFalse();
            assertThat(page.previousCursor()).isEmpty();
            assertThat(page.nextCursor()).isEmpty();
        });
    }

    @TckTest
    void ac_pag_21_after_once_its_rows_are_deleted_returns_an_empty_page_with_no_cursors(TckDatabase db) {
        // Page 1 of P003's 400 rows, then every P003 row is deleted and the same cursor is asked what follows: an
        // empty page, both flags false and both cursors empty (R-PAG-21). The delete rolls back.
        var q = ITEM_ROWS.where(f -> f.eq(ITEM_PRODUCT, Optional.of("P003")))
                .orderBy(ITEM_QUANTITY.asc())
                .build();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    em.getTransaction().begin();
                    try {
                        ModelQueryExecutor<OrderItemEntity> executor =
                                ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults());
                        String cursor = executor.page(q, KeysetSpec.first(3)).nextCursor().orElseThrow();
                        em.createNativeQuery("DELETE FROM order_items WHERE product_code = 'P003'")
                                .executeUpdate();
                        KeysetSlice<ItemRow> page = executor.page(q, KeysetSpec.after(cursor, 3));
                        assertThat(page.content()).isEmpty();
                        assertThat(page.hasPrevious()).isFalse();
                        assertThat(page.hasNext()).isFalse();
                        assertThat(page.previousCursor()).isEmpty();
                        assertThat(page.nextCursor()).isEmpty();
                    } finally {
                        em.getTransaction().rollback();
                    }
                });
            }
        });
        assertThat(sql).isNotEmpty();
    }

    // ---- AC-PAG-22

    @TckTest
    void ac_pag_22_a_query_without_keyset_throws_mq2207_before_any_query(TckDatabase db) {
        var noKeyset = ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ORDER_ID), row.get(ORDER_STATUS)))
                .select(SelectSet.of(ORDER_ID, ORDER_STATUS))
                .primaryKey(PrimaryKey.of(ORDER_ID))
                .build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class,
                executor -> assertThatThrownBy(() -> executor.page(noKeyset, KeysetSpec.first(5)))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2207))));
        assertThat(sql).as("refused before any query runs").isEmpty();
    }

    @TckTest
    void ac_pag_22_a_to_many_selection_throws_mq2204_before_any_query(TckDatabase db) {
        // Ordering by a column read through the to-many join counts as selecting it (D-29) and is refused (R-PAG-13).
        var ordered = ORDER_ROWS.orderBy(ORDER_ITEM_PRODUCT.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class,
                executor -> assertThatThrownBy(() -> executor.page(ordered, KeysetSpec.first(5)))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2204))
                        .hasMessageContaining("OrderEntity.items")));
        assertThat(sql).as("refused before any query runs").isEmpty();
    }

    @TckTest
    void ac_pag_22_a_primary_key_first_query_over_phases_that_disagree_throws_mq2206(TckDatabase db) {
        QueryCustomizer onlyModel = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL) {
                query.where(cb.equal(query.getRoots().iterator().next().get("status"), "PAID"));
            }
        };
        var twoStep = ORDER_ROWS.customize(onlyModel).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100)).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class,
                executor -> assertThatThrownBy(() -> executor.page(twoStep, KeysetSpec.first(5)))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2206))));
        assertThat(sql).as("refused before any query runs").isEmpty();
    }

    @TckTest
    void ac_pag_22_a_fetch_plan_loads_the_children_of_the_page_content_only(TckDatabase db) {
        // A plan runs on the page's content (R-PAG-23): each of the 7 content customers carries exactly its fixture
        // orders, and each order its fixture items; the look-ahead row has no children loaded because it is not in
        // the content.
        FetchPlan<Line> linePlan = FetchPlan.of(QLine.ALL);
        FetchPlan<OrderLines> orderPlan = FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS, linePlan);
        FetchPlan<CustomerOrders> customerPlan =
                FetchPlan.of(QCustomerOrders.ALL).child(QCustomerOrders.ORDERS, orderPlan);
        var q = QCustomerOrders.query()
                .where(f -> f.lte(QCustomerOrders.ID, 40L))
                .orderBy(QCustomerOrders.NAME.desc())
                .keyset()
                .fetch(customerPlan)
                .build();
        Map<Long, List<Long>> orders = grouped(db, "select o.customer.id, o.id from OrderEntity o"
                + " where o.customer.id <= 40 order by o.id");
        Map<Long, List<Long>> items = grouped(db, "select i.order.id, i.id from OrderItemEntity i"
                + " where i.order.customer.id <= 40 order by i.id");
        withExecutor(db, CustomerEntity.class, executor -> {
            KeysetSlice<CustomerOrders> page = executor.page(q, KeysetSpec.first(7));
            assertThat(page.content()).hasSize(7);
            for (CustomerOrders loaded : page.content()) {
                List<Long> expectedOrders = orders.get(loaded.id());
                assertThat(expectedOrders).as("fixture orders of customer %s", loaded.id()).isNotNull();
                assertThat(loaded.orders()).as("orders of customer %s", loaded.id()).isNotEmpty()
                        .extracting(OrderLines::id).containsExactlyElementsOf(expectedOrders);
                for (OrderLines order : loaded.orders()) {
                    List<Long> expectedItems = items.get(order.id());
                    assertThat(expectedItems).as("fixture items of order %s", order.id()).isNotNull();
                    assertThat(order.items()).as("items of order %s", order.id()).isNotEmpty()
                            .extracting(Line::id).containsExactlyElementsOf(expectedItems);
                }
            }
        });
    }

    // ---- AC-PAG-23

    @TckTest
    void ac_pag_23_a_page_holding_the_cursors_own_primary_key_throws_mq2205(TckDatabase db) {
        // Page 1 is 250 of P001's 400 rows; the boundary row's quantity moves to 9, after the cursor, so page 2 reads
        // it again and the page refuses it (R-PAG-24). The update rolls back.
        var q = P001.orderBy(ITEM_QUANTITY.asc()).build();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    ModelQueryExecutor<OrderItemEntity> executor =
                            ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults());
                    KeysetSlice<ItemRow> first = executor.page(q, KeysetSpec.first(250));
                    String cursor = first.nextCursor().orElseThrow();
                    Long boundary = first.content().get(first.content().size() - 1).id();
                    em.createNativeQuery("UPDATE order_items SET quantity = 9 WHERE id = :id")
                            .setParameter("id", boundary)
                            .executeUpdate();
                    assertThatThrownBy(() -> executor.page(q, KeysetSpec.after(cursor, 250)))
                            .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ2205))
                            .hasMessageStartingWith(MqCode.MQ2205.code() + ": ItemRow:");
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }

    @TckTest
    void ac_pag_23_repeats_from_a_predicate_only_to_many_join_are_dropped_and_no_root_is_skipped(TckDatabase db) {
        // Each order holding P001 holds it four times, so the join repeats each root four times in a row: within a
        // page the repeats are dropped, and the cursor is past all of them (R-PAG-02, R-PAG-24).
        var withItem = ORDER_ROWS.where(f -> f.eq(ORDER_ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ORDER_STATUS.asc())
                .build();
        List<Long> expected = new ArrayList<>();
        inSession(db, em -> expected.addAll(em.createQuery("select distinct o.id from OrderEntity o join o.items i"
                + " where i.productCode = 'P001'", Long.class).getResultList()));
        for (int pageSize : new int[] {3, 4, 7, 1_000}) {
            List<OrderRow> rows = new ArrayList<>();
            withExecutor(db, OrderEntity.class, executor -> {
                KeysetSpec spec = KeysetSpec.first(pageSize);
                while (true) {
                    KeysetSlice<OrderRow> page = executor.page(withItem, spec);
                    rows.addAll(page.content());
                    if (!page.hasNext()) {
                        break;
                    }
                    spec = KeysetSpec.after(page.nextCursor().orElseThrow(), pageSize);
                }
            });
            assertThat(rows).extracting(OrderRow::id).as("page size %d", pageSize)
                    .containsExactlyInAnyOrderElementsOf(expected).doesNotHaveDuplicates();
            assertThat(rows).isSortedAccordingTo(Comparator.comparing(OrderRow::status).thenComparing(OrderRow::id));
        }
    }

    // ---- support

    /** The pages of a full forward walk, in order. */
    private static <E, M> List<KeysetSlice<M>> forward(ModelQueryExecutor<E> executor, ModelQuery<E, ?, M> q,
            int size) {
        List<KeysetSlice<M>> pages = new ArrayList<>();
        KeysetSpec spec = KeysetSpec.first(size);
        while (true) {
            KeysetSlice<M> page = executor.page(q, spec);
            pages.add(page);
            if (!page.hasNext()) {
                return pages;
            }
            spec = KeysetSpec.after(page.nextCursor().orElseThrow(), size);
        }
    }

    /** The rows of a full backward walk, in the query's order. */
    private static <E, M> List<M> walkBack(ModelQueryExecutor<E> executor, ModelQuery<E, ?, M> q, int size,
            List<KeysetSlice<M>> pages) {
        List<M> rows = new ArrayList<>(pages.get(pages.size() - 1).content());
        Optional<String> cursor = pages.get(pages.size() - 1).previousCursor();
        while (cursor.isPresent()) {
            KeysetSlice<M> page = executor.page(q, KeysetSpec.before(cursor.get(), size));
            rows.addAll(0, page.content());
            cursor = page.previousCursor();
        }
        return rows;
    }

    private static <M> List<M> flatten(List<KeysetSlice<M>> pages) {
        List<M> rows = new ArrayList<>();
        for (KeysetSlice<M> page : pages) {
            rows.addAll(page.content());
        }
        return rows;
    }

    private static <M> List<Long> ids(List<M> rows, Function<M, Long> id) {
        return rows.stream().map(id).toList();
    }

    /** {@code cursor} with its version byte set to 2, its checksum recomputed so only the version is wrong. */
    private static String unknownVersion(String cursor) {
        byte[] all = Base64.getUrlDecoder().decode(cursor);
        all[0] = 2;
        CRC32C crc = new CRC32C();
        crc.update(all, 0, all.length - 4);
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) {
            all[all.length - 4 + i] = (byte) (value >> (24 - 8 * i));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    private static OrderField<SortRow, Integer> nullOrder(ColumnField<SortRow, NullableSortEntity, Integer> column,
            boolean ascending, NullPrecedence nulls) {
        return (ascending ? column.asc() : column.desc()).nulls(nulls);
    }

    private static Comparator<SortRow> comparator(boolean ascending, NullPrecedence nulls) {
        Comparator<Integer> values = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Comparator<SortRow> order = Comparator.comparing(SortRow::sortInt,
                nulls == NullPrecedence.FIRST ? Comparator.nullsFirst(values) : Comparator.nullsLast(values));
        Comparator<SortRow> byId = Comparator.comparing(SortRow::id);
        return order.thenComparing(ascending ? byId : byId.reversed());
    }

    private static <E, M> String firstNextCursor(TckDatabase db, Class<E> root, ModelQuery<E, ?, M> q, int size) {
        String[] cursor = new String[1];
        withExecutor(db, root,
                executor -> cursor[0] = executor.page(q, KeysetSpec.first(size)).nextCursor().orElseThrow());
        return cursor[0];
    }

    private static <E, M> void assertMq2209(ModelQueryExecutor<E> executor, ModelQuery<E, ?, M> q, String cursor) {
        assertThatThrownBy(() -> executor.page(q, KeysetSpec.after(cursor, 5)))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2209))
                .hasMessageStartingWith(MqCode.MQ2209.code());
    }

    private static Map<Long, List<Long>> grouped(TckDatabase db, String jpql) {
        Map<Long, List<Long>> grouped = new HashMap<>();
        inSession(db, em -> {
            for (Object[] row : em.createQuery(jpql, Object[].class).getResultList()) {
                grouped.computeIfAbsent((Long) row[0], key -> new ArrayList<>()).add((Long) row[1]);
            }
        });
        return grouped;
    }

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        inSession(db, em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        withExecutor(ds, root, ModelQueryConfig.defaults(), work);
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, config)));
        }
    }

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }
}
