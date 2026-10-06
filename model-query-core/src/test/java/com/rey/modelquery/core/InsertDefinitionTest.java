package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** Insert definitions checked at build(), before any Criteria query exists (spec api/14 §10, D-117). */
class InsertDefinitionTest {

    static final class Order {}

    static final class Source {}

    static final class OrderView {}

    record NewOrder(Long id, String ref, String status, Boolean flagged) {}

    static final class FlagConverter implements ColumnConverter<Boolean, String> {
        @Override
        public Boolean toModel(String attribute) {
            return "Y".equals(attribute);
        }

        @Override
        public String toAttribute(Boolean model) {
            return model ? "Y" : "N";
        }
    }

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Order> PARENT = TableField.join(ROOT, "parent", LEFT);
    private static final TableField<Source, Source> SOURCE = TableField.root(Source.class);
    private static final TableField<Source, Order> SOURCE_ORDERS = TableField.join(SOURCE, "orders", LEFT);
    private static final TableField<Order, Source> ITEMS = TableField.join(ROOT, "items", LEFT);

    private static final ColumnField<NewOrder, Order, Long> ID =
            ColumnField.of(NewOrder.class, ROOT, "id", Long.class);
    private static final ColumnField<NewOrder, Order, String> REF =
            ColumnField.of(NewOrder.class, ROOT, "ref", String.class);
    private static final ColumnField<NewOrder, Order, String> STATUS =
            ColumnField.of(NewOrder.class, ROOT, "status", String.class);
    private static final ColumnField<NewOrder, Order, Boolean> FLAGGED =
            ColumnField.of(NewOrder.class, ROOT, "flagged", Boolean.class, String.class, new FlagConverter());
    private static final ColumnField<NewOrder, Order, String> NOTE =
            ColumnField.of(NewOrder.class, ROOT, "note", String.class);
    private static final ColumnField<NewOrder, Order, String> PARENT_STATUS =
            ColumnField.of(NewOrder.class, PARENT, "status", String.class);
    private static final ColumnField<Order, Order, String> CREATED_BY =
            ColumnField.of(Order.class, ROOT, "createdBy", String.class);
    private static final ColumnField<Order, Order, String> STATUS_CONSTANT =
            ColumnField.of(Order.class, ROOT, "status", String.class);

    private static final ColumnField<OrderView, Source, Long> SOURCE_ID =
            ColumnField.of(OrderView.class, SOURCE, "id", Long.class);
    private static final ColumnField<OrderView, Source, String> SOURCE_REF =
            ColumnField.of(OrderView.class, SOURCE, "ref", String.class);
    private static final ColumnField<OrderView, Order, String> SOURCE_STATUS =
            ColumnField.of(OrderView.class, SOURCE_ORDERS, "status", String.class);
    private static final ColumnField<OrderView, Source, Boolean> SOURCE_FLAGGED =
            ColumnField.of(OrderView.class, SOURCE, "flagged", Boolean.class);
    private static final ColumnField<OrderView, Source, Boolean> SOURCE_FLAGGED_CONVERTED =
            ColumnField.of(OrderView.class, SOURCE, "flag", Boolean.class, String.class, new FlagConverter());

    private static final InsertColumns<NewOrder, Order> COLUMNS = InsertColumns.<NewOrder, Order>of(ROOT)
            .addKey(ID, NewOrder::id)
            .add(REF, NewOrder::ref)
            .add(STATUS, NewOrder::status)
            .add(FLAGGED, NewOrder::flagged);

    private static final NewOrder FIRST = new NewOrder(1L, "a", "NEW", true);
    private static final NewOrder SECOND = new NewOrder(2L, "b", "NEW", false);

    // ---- InsertColumns

    @Test
    void ac_wrt_32_insert_columns_keep_declaration_order_and_their_key_columns() {
        assertThat(COLUMNS.rootEntity()).isEqualTo(Order.class);
        assertThat(COLUMNS.columns()).containsExactly(ID, REF, STATUS, FLAGGED);
        assertThat(COLUMNS.keyColumns()).containsExactly(ID);
        assertThat(COLUMNS).hasToString("Order[id, ref, status, flagged]");
    }

    @Test
    void ac_wrt_32_a_column_added_twice_or_off_the_root_throws_mq1801() {
        assertCode(() -> COLUMNS.add(REF, NewOrder::ref), MqCode.MQ1801)
                .hasMessage("MQ1801: NewOrder.ref: added twice; each attribute is one column of the row");
        assertCode(() -> COLUMNS.add(PARENT_STATUS, NewOrder::status), MqCode.MQ1801)
                .hasMessage("MQ1801: NewOrder.status: an insert model writes only the root Order's own columns, not "
                        + "the join 'parent' (LEFT)");
    }

    @Test
    void ac_wrt_32_a_join_as_the_root_or_the_source_throws_mq1203() {
        assertCode(() -> InsertColumns.<NewOrder, Order>of(PARENT), MqCode.MQ1203);
        assertCode(() -> ModelInsert.select(COLUMNS, PARENT), MqCode.MQ1203);
    }

    // ---- insert-select

    @Test
    void ac_wrt_21_an_insert_select_binds_its_set_constants_by_name_through_their_converters() {
        var approved = ColumnField.of(Order.class, ROOT, "approved", Boolean.class, String.class, new FlagConverter());
        ModelInsert<Order, NewOrder> insert = select().set(CREATED_BY, "batch").set(approved, true).all().build();

        assertThat(insert.selectParameters()).containsExactly(entry("mqConstant0", "batch"),
                entry("mqConstant1", "Y"));
        assertThatThrownBy(() -> values(FIRST).build().selectParameters()).isInstanceOf(IllegalStateException.class)
                .hasMessage("Order (1 row): an insert-values has no source select");
    }

    @Test
    void ac_wrt_32_an_insert_select_mapping_every_column_once_builds() {
        ModelInsert<Order, NewOrder> insert = select().set(CREATED_BY, "batch").all()
                .chunked(ChunkOptions.size(10).lockKeys()).persistenceContext(PersistenceContextMode.KEEP).build();

        assertThat(insert.rootEntity()).isEqualTo(Order.class);
        assertThat(insert.chunkOptions()).contains(ChunkOptions.size(10).lockKeys());
        assertThat(insert.persistenceContext()).contains(PersistenceContextMode.KEEP);
        assertThat(insert.definition().mappings()).extracting(InsertDraft.Mapped::source)
                .containsExactly(SOURCE_ID, SOURCE_REF, SOURCE_STATUS, SOURCE_FLAGGED_CONVERTED);
        assertThat(insert).hasToString("Order (from Source, all rows)");
        assertThat(select().where(f -> f.eq(SOURCE_REF, "x")).build())
                .hasToString("Order (from Source, where [EQ(OrderView.ref, ?)])");
    }

    @Test
    void ac_wrt_32_an_insert_select_whose_where_skipped_every_filter_throws_mq1601() {
        assertCode(() -> select().where(f -> f.eq(SOURCE_REF, Optional.<String>empty())).build(), MqCode.MQ1601);
    }

    @Test
    void ac_wrt_32_a_column_unmapped_mapped_twice_or_outside_the_model_throws_mq1801() {
        assertCode(() -> ModelInsert.select(COLUMNS, SOURCE).map(ID, SOURCE_ID).map(REF, SOURCE_REF)
                .map(STATUS, SOURCE_STATUS).all().build(), MqCode.MQ1801)
                .hasMessage("MQ1801: NewOrder.flagged: not mapped; an insert-select maps every column of the insert "
                        + "model once");
        assertCode(() -> select().map(REF, SOURCE_REF).all().build(), MqCode.MQ1801)
                .hasMessage("MQ1801: NewOrder.ref: mapped twice");
        assertCode(() -> select().map(NOTE, SOURCE_REF).all().build(), MqCode.MQ1801)
                .hasMessage("MQ1801: NewOrder.note: mapped, but not a column of Order[id, ref, status, flagged]");
    }

    @Test
    void ac_wrt_32_a_mapping_between_columns_with_different_converters_throws_mq1801() {
        assertCode(() -> ModelInsert.select(COLUMNS, SOURCE).map(ID, SOURCE_ID).map(REF, SOURCE_REF)
                .map(STATUS, SOURCE_STATUS).map(FLAGGED, SOURCE_FLAGGED).all().build(), MqCode.MQ1801)
                .hasMessage("MQ1801: NewOrder.flagged: mapped from OrderView.flagged with no converter, but written "
                        + "with FlagConverter; the database copies attribute values, so both columns need the same "
                        + "converter or none");
    }

    @Test
    void ac_wrt_32_a_constant_on_a_model_column_set_twice_or_off_the_root_throws_mq1801() {
        assertCode(() -> select().set(STATUS_CONSTANT, "NEW").all().build(), MqCode.MQ1801)
                .hasMessage("MQ1801: Order.status: set(...) on a column the insert model already writes; each row's "
                        + "value is written");
        assertCode(() -> values().set(CREATED_BY, "a").set(CREATED_BY, "b").build(), MqCode.MQ1801)
                .hasMessage("MQ1801: Order.createdBy: set(...) twice");
        ColumnField<Order, Order, String> parentCreatedBy = ColumnField.of(Order.class, PARENT, "createdBy",
                String.class);
        assertCode(() -> values().set(parentCreatedBy, "a").build(), MqCode.MQ1801);
    }

    @Test
    void ac_wrt_32_a_null_constant_throws_mq1603() {
        assertCode(() -> values().set(CREATED_BY, null), MqCode.MQ1603);
        assertCode(() -> select().set(CREATED_BY, null), MqCode.MQ1603);
    }

    // ---- insert-values

    @Test
    void ac_wrt_32_insert_values_reads_each_row_once_at_build_into_its_column_values() {
        var reads = new AtomicInteger();
        InsertColumns<NewOrder, Order> counted = InsertColumns.<NewOrder, Order>of(ROOT).addKey(ID, row -> {
            reads.incrementAndGet();
            return row.id();
        }).add(REF, NewOrder::ref);
        var rows = new ArrayList<>(List.of(FIRST, SECOND));

        ValuesInsert.Rows<Order, Long, NewOrder> builder = ValuesInsert.builder(counted, Long.class, rows);
        rows.add(new NewOrder(3L, "c", "NEW", null));
        assertThat(reads).hasValue(0);
        ValuesInsert<Order, Long, NewOrder> insert = builder.build();
        rows.clear();

        assertThat(reads).hasValue(2);
        assertThat(insert.definition().values()).containsExactly(Arrays.asList(1L, "a"), Arrays.asList(2L, "b"));
        assertThat(insert.definition().rows()).isNull();
        assertThat(insert.keyType()).isEqualTo(Long.class);
        assertThat(insert).hasToString("Order (2 rows)");
        assertThatThrownBy(() -> insert.definition().values().get(0).set(1, "z"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(reads).hasValue(2);
    }

    @Test
    void ac_wrt_32_a_null_column_value_is_kept_and_an_empty_list_builds() {
        ValuesInsert<Order, Long, NewOrder> insert = values(new NewOrder(1L, null, null, null)).build();
        assertThat(insert.definition().values()).containsExactly(Arrays.asList(1L, null, null, null));
        assertThat(ValuesInsert.builder(COLUMNS, Long.class, List.<NewOrder>of()).build())
                .hasToString("Order (0 rows)");
    }

    @Test
    void ac_wrt_32_a_null_row_throws_mq1803_and_a_null_assigned_id_mq1802() {
        assertCode(() -> ValuesInsert.builder(COLUMNS, Long.class, Arrays.asList(FIRST, null)).build(),
                MqCode.MQ1803).hasMessage("MQ1803: Order[id, ref, status, flagged]: row 1 is null");
        assertCode(() -> values(FIRST, new NewOrder(null, "b", "NEW", true)).build(), MqCode.MQ1802)
                .hasMessage("MQ1802: NewOrder.id: row 1 has a null id; an id with no generator is assigned by the "
                        + "row");
        assertCode(() -> ModelPersist.of(COLUMNS, Long.class, null), MqCode.MQ1803);
        assertCode(() -> ModelPersist.of(COLUMNS, Long.class, new NewOrder(null, "b", "NEW", true)), MqCode.MQ1802);
    }

    @Test
    void ac_wrt_32_lock_keys_on_insert_values_throws_mq1801() {
        assertCode(() -> values().chunked(ChunkOptions.size(5).lockKeys()).build(), MqCode.MQ1801)
                .hasMessage("MQ1801: Order (2 rows): lockKeys() on insert-values, which selects no key to lock");
        assertCode(() -> values().onConflict(REF).doNothing().chunked(ChunkOptions.defaultSize().lockKeys()).build(),
                MqCode.MQ1801);
        assertThat(values().chunked(ChunkOptions.size(5).commitEachChunk()).build().chunkOptions())
                .contains(ChunkOptions.size(5).commitEachChunk());
    }

    @Test
    void ac_wrt_23_keys_with_commit_each_chunk_throw_mq1801_at_the_keys_call() {
        ValuesInsert<Order, Long, NewOrder> committing = values().chunked(ChunkOptions.size(5).commitEachChunk())
                .build();
        assertCode(committing::checkKeysReturnable, MqCode.MQ1801)
                .hasMessage("MQ1801: Order (2 rows): insertReturningKeys(...) with commitEachChunk(), whose failure "
                        + "would lose the committed rows' keys; use insert(...), or chunk in the caller's "
                        + "transaction");
        assertThatCode(() -> values().chunked(ChunkOptions.size(5)).build().checkKeysReturnable())
                .doesNotThrowAnyException();
        assertThatCode(() -> values().build().checkKeysReturnable()).doesNotThrowAnyException();
    }

    @Test
    void ac_wrt_32_persist_reads_its_row_once() {
        ModelPersist<Order, Long, NewOrder> persist = ModelPersist.of(COLUMNS, Long.class, FIRST);
        assertThat(persist.rootEntity()).isEqualTo(Order.class);
        assertThat(persist.keyType()).isEqualTo(Long.class);
        assertThat(persist.values()).containsExactly(1L, "a", "NEW", true);
        assertThat(persist).hasToString("Order (persist)");
    }

    // ---- conflict clauses

    @Test
    void ac_wrt_32_conflict_inserts_build_with_their_options() {
        ModelInsert<Order, NewOrder> skip = values().onConflict(REF).doNothing().anyUniqueKey().build();
        assertThat(skip).isNotInstanceOf(ValuesInsert.class).hasToString("Order (2 rows, on conflict)");
        assertThat(skip.definition().conflict().update()).isNull();
        assertThat(skip.definition().conflict().anyUniqueKey()).isTrue();

        ModelInsert<Order, NewOrder> upsert = values().onConflict(REF, ID)
                .doUpdate(u -> u.setFromRow(STATUS, FLAGGED).set(CREATED_BY, "batch").setNull(NOTE)
                        .where(f -> f.ne(STATUS, "CLOSED")))
                .chunked(ChunkOptions.size(100)).keepVersion().build();
        InsertDraft.Conflict<Order, NewOrder> conflict = upsert.definition().conflict();
        assertThat(conflict.keys()).containsExactly(REF, ID);
        assertThat(conflict.keepVersion()).isTrue();
        assertThat(conflict.update().fromRow()).containsExactly(STATUS, FLAGGED);
        assertThat(conflict.update().assignments()).extracting(a -> (Object) a.column())
                .containsExactly(CREATED_BY, NOTE);
        assertThat(conflict.update().where()).hasSize(1);
    }

    @Test
    void ac_wrt_32_two_rows_sharing_a_conflict_key_throw_mq1808_naming_positions_only() {
        var rows = List.of(FIRST, SECOND, new NewOrder(3L, "a", "OLD", null));
        assertCode(() -> ValuesInsert.builder(COLUMNS, Long.class, rows).onConflict(REF).doNothing().build(),
                MqCode.MQ1808).hasMessage("MQ1808: Order: rows 0 and 2 share the conflict key [NewOrder.ref]; vendors "
                        + "disagree on such rows, so one call may not hold both");
        assertThatCode(() -> ValuesInsert.builder(COLUMNS, Long.class, rows).onConflict(REF, STATUS).doNothing()
                .build()).doesNotThrowAnyException();
        assertThatCode(() -> ValuesInsert.builder(COLUMNS, Long.class, rows).build()).doesNotThrowAnyException();
    }

    @Test
    void ac_wrt_32_rows_whose_conflict_key_holds_a_null_never_count_as_sharing_it() {
        var nullRefs = List.of(new NewOrder(4L, null, "OLD", null), new NewOrder(5L, null, "OLD", null));
        assertThatCode(() -> ValuesInsert.builder(COLUMNS, Long.class, nullRefs).onConflict(REF).doNothing().build())
                .doesNotThrowAnyException();
        assertThatCode(() -> ValuesInsert.builder(COLUMNS, Long.class, nullRefs).onConflict(REF, STATUS).doNothing()
                .build()).doesNotThrowAnyException();
        var mixed = List.of(nullRefs.get(0), FIRST, nullRefs.get(1), new NewOrder(6L, "a", "NEW", null));
        assertCode(() -> ValuesInsert.builder(COLUMNS, Long.class, mixed).onConflict(REF).doNothing().build(),
                MqCode.MQ1808).hasMessageStartingWith("MQ1808: Order: rows 1 and 3 share the conflict key");
    }

    @Test
    void ac_wrt_32_a_conflict_column_outside_the_model_or_named_twice_throws_mq1804() {
        assertCode(() -> values().onConflict(NOTE).doNothing().build(), MqCode.MQ1804)
                .hasMessage("MQ1804: NewOrder.note: onConflict(...) names a column that is not one of Order[id, ref, "
                        + "status, flagged], so no row carries its value");
        assertCode(() -> values().onConflict(REF, REF).doNothing().build(), MqCode.MQ1804)
                .hasMessage("MQ1804: NewOrder.ref: onConflict(...) names it twice");
    }

    @Test
    void ac_wrt_32_a_conflict_update_assigning_a_key_a_column_twice_or_off_the_row_throws_mq1804() {
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(REF)).build(), MqCode.MQ1804)
                .hasMessage("MQ1804: NewOrder.ref: doUpdate(...) assigns a key column; the conflict key and the id "
                        + "stay as stored");
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(ID)).build(), MqCode.MQ1804);
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(STATUS).set(STATUS_CONSTANT, "x"))
                .build(), MqCode.MQ1804).hasMessage("MQ1804: Order.status: doUpdate(...) assigns it twice");
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(NOTE)).build(), MqCode.MQ1804)
                .hasMessage("MQ1804: NewOrder.note: setFromRow(...) on a column that is not one of Order[id, ref, "
                        + "status, flagged], so the incoming row has no value for it");
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setNull(PARENT_STATUS)).build(), MqCode.MQ1804);
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.set(STATUS, null)), MqCode.MQ1603);
    }

    @Test
    void ac_wrt_32_a_conflict_where_with_exists_a_sub_select_or_a_joined_column_throws_mq1804() {
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(STATUS).where(f -> f.exists(ITEMS)))
                .build(), MqCode.MQ1804)
                .hasMessage("MQ1804: doUpdate(...).where(...) with EXISTS: the conflict action filters the stored row "
                        + "on its own columns, with no join or sub-query");
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(STATUS)
                .where(f -> f.or(o -> o.eq(STATUS, "NEW"), o -> o.eq(PARENT_STATUS, "NEW")))).build(),
                MqCode.MQ1804)
                .hasMessage("MQ1804: NewOrder.status: doUpdate(...).where(...) reads the join 'parent' (LEFT); the "
                        + "conflict action has no join, so it filters on root columns");
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(STATUS)
                .where(f -> f.in(STATUS, SubSelect.of(SOURCE_REF)))).build(), MqCode.MQ1804);
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(STATUS)
                .where(f -> f.eq(Expr.coalesce(PARENT_STATUS, "NONE"), "NEW"))).build(), MqCode.MQ1804)
                .hasMessage("MQ1804: NewOrder.status: doUpdate(...).where(...) reads the join 'parent' (LEFT); the "
                        + "conflict action has no join, so it filters on root columns");
    }

    @Test
    void ac_wrt_32_a_join_column_naming_a_model_attribute_is_not_that_column() {
        assertCode(() -> ModelInsert.select(COLUMNS, SOURCE).map(ID, SOURCE_ID).map(REF, SOURCE_REF)
                .map(PARENT_STATUS, SOURCE_STATUS).map(FLAGGED, SOURCE_FLAGGED_CONVERTED).all().build(),
                MqCode.MQ1801).hasMessage("MQ1801: NewOrder.status: mapped, but not a column of Order[id, ref, "
                        + "status, flagged]");
        assertCode(() -> values().onConflict(PARENT_STATUS).doNothing().build(), MqCode.MQ1804)
                .hasMessage("MQ1804: NewOrder.status: onConflict(...) names a column that is not one of Order[id, "
                        + "ref, status, flagged], so no row carries its value");
        assertCode(() -> values().onConflict(REF).doUpdate(u -> u.setFromRow(PARENT_STATUS)).build(), MqCode.MQ1804)
                .hasMessage("MQ1804: NewOrder.status: setFromRow(...) on a column that is not one of Order[id, ref, "
                        + "status, flagged], so the incoming row has no value for it");
    }

    @Test
    void ac_wrt_32_a_conflict_where_that_skips_every_filter_updates_every_conflicting_row() {
        ModelInsert<Order, NewOrder> insert = values().onConflict(REF)
                .doUpdate(u -> u.setFromRow(STATUS).where(f -> f.eq(STATUS, Optional.<String>empty()))).build();
        assertThat(insert.definition().conflict().update().where()).isEmpty();
    }

    // ---- ChunkedWriteException

    @Test
    void ac_wrt_26_an_insert_values_failure_reports_the_next_row_and_the_rows_in_doubt() {
        var cause = new RuntimeException("commit failed");
        var e = new ChunkedWriteException("chunk 3", 200L, 200, 100, cause);
        assertThat(e.code()).isEqualTo(MqCode.MQ2502);
        assertThat(e.committedRows()).isEqualTo(200L);
        assertThat(e.nextRowIndex()).isEqualTo(OptionalInt.of(200));
        assertThat(e.inDoubtRowCount()).isEqualTo(100);
        assertThat(e.lastCommittedKey()).isEmpty();
        assertThat(e.inDoubtKeys()).isEmpty();
        assertThat(e).hasCause(cause);

        var keyed = new ChunkedWriteException("chunk 3", 2L, 7L, List.of(8L, 9L), cause);
        assertThat(keyed.nextRowIndex()).isEmpty();
        assertThat(keyed.inDoubtRowCount()).isZero();
        assertThatThrownBy(() -> new ChunkedWriteException("x", 0L, -1, 0, cause))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkedWriteException("x", 0L, 0, -1, cause))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_wrt_26_the_insert_values_failure_stays_serializable() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(new ChunkedWriteException("chunk 3", 200L, 200, 100, new RuntimeException("commit")));
        }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var e = (ChunkedWriteException) in.readObject();
            assertThat(e.nextRowIndex()).isEqualTo(OptionalInt.of(200));
            assertThat(e.inDoubtRowCount()).isEqualTo(100);
        }
    }

    private static ModelInsert.Mapping<Order, NewOrder, OrderView> select() {
        return ModelInsert.select(COLUMNS, SOURCE).map(ID, SOURCE_ID).map(REF, SOURCE_REF).map(STATUS, SOURCE_STATUS)
                .map(FLAGGED, SOURCE_FLAGGED_CONVERTED);
    }

    private static ValuesInsert.Rows<Order, Long, NewOrder> values(NewOrder... rows) {
        return ValuesInsert.builder(COLUMNS, Long.class, rows.length == 0 ? List.of(FIRST, SECOND) : List.of(rows));
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertCode(ThrowingCallable call, MqCode code) {
        return assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
