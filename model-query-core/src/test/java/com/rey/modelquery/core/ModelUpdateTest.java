package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Bulk-write definitions checked at build(), before any Criteria query exists (spec api/14 R-WRT-05..R-WRT-14). */
class ModelUpdateTest {

    static final class Order {}

    static final class OrderPatch {}

    /** A hand-written change set, as the processor generates one: set columns in the order first set. */
    static final class PatchChanges implements Changes<OrderPatch> {

        private final Map<ColumnField<OrderPatch, ?, ?>, Assignment<OrderPatch, ?>> set = new LinkedHashMap<>();

        <C> PatchChanges with(ColumnField<OrderPatch, Order, C> column, C value) {
            set.put(column, value == null ? Assignment.ofNull(column) : Assignment.of(column, value));
            return this;
        }

        @Override
        public boolean isSet(ColumnField<OrderPatch, ?, ?> column) {
            return set.containsKey(column);
        }

        @Override
        public PatchChanges unset(ColumnField<OrderPatch, ?, ?> column) {
            set.remove(column);
            return this;
        }

        @Override
        public boolean isEmpty() {
            return set.isEmpty();
        }

        @Override
        public List<Assignment<OrderPatch, ?>> assignments() {
            return List.copyOf(set.values());
        }
    }

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

    private static final ColumnField<OrderPatch, Order, Long> ID =
            ColumnField.of(OrderPatch.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderPatch, Order, String> STATUS =
            ColumnField.of(OrderPatch.class, ROOT, "status", String.class);
    private static final ColumnField<OrderPatch, Order, String> NOTE =
            ColumnField.of(OrderPatch.class, ROOT, "note", String.class);
    private static final ColumnField<OrderPatch, Order, Long> TOTAL =
            ColumnField.of(OrderPatch.class, ROOT, "total", Long.class);
    private static final ColumnField<OrderPatch, Order, Boolean> FLAGGED =
            ColumnField.of(OrderPatch.class, ROOT, "flagged", Boolean.class, String.class, new FlagConverter());
    private static final ColumnField<OrderPatch, Order, String> PARENT_STATUS =
            ColumnField.of(OrderPatch.class, PARENT, "status", String.class);

    private static ModelUpdate.Builder<Order, Long, OrderPatch> update() {
        return ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID));
    }

    private static ModelDelete.Builder<Order, Long, OrderPatch> delete() {
        return ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID));
    }

    // ---- AC-WRT-04

    @Test
    void ac_wrt_04_set_on_a_self_referencing_join_throws_mq1604_at_build() {
        ModelUpdate.Options<Order, Long, OrderPatch> options = update().set(PARENT_STATUS, "PAID").whereKey(1L);
        assertThatThrownBy(options::build)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1604))
                .hasMessage("MQ1604: OrderPatch.status: sits on the join 'parent' (LEFT), not on the root Order; an "
                        + "update writes only the root's own columns");
    }

    @Test
    void ac_wrt_04_a_join_as_the_root_of_an_update_or_a_delete_throws_mq1203() {
        assertThatThrownBy(() -> ModelUpdate.builder(PARENT))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1203));
        assertThatThrownBy(() -> ModelDelete.builder(PARENT))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1203));
    }

    @Test
    void ac_wrt_04_set_with_null_throws_mq1603() {
        assertThatThrownBy(() -> update().set(NOTE, null))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1603))
                .hasMessage("MQ1603: OrderPatch.note: set(...) received null; write NULL with setNull(...) or a "
                        + "change set");
        assertThatThrownBy(() -> Assignment.of(NOTE, null))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1603));
        assertThat(update().setNull(NOTE).whereKey(1L).build().assignments())
                .containsExactly(Assignment.ofNull(NOTE));
    }

    @Test
    void ac_wrt_04_a_column_assigned_twice_throws_mq1602() {
        var changes = new PatchChanges().with(STATUS, "PAID");
        assertThatThrownBy(() -> update().set(changes).set(STATUS, "SHIPPED").whereKey(1L).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1602))
                .hasMessage("MQ1602: OrderPatch.status: assigned twice; a column the server sets belongs in a "
                        + "hand-written ColumnField, not in the update model");
        assertThatThrownBy(() -> update().setNull(NOTE).setExpression(NOTE, (path, cb) -> path).all().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1602));
    }

    @Test
    void ac_wrt_04_assigning_a_primary_key_column_throws_mq1605_at_build() {
        assertThatThrownBy(() -> update().set(ID, 2L).whereKey(1L).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1605))
                .hasMessage("MQ1605: OrderPatch.id: a primary-key column is never assigned; whereKey(...) chooses "
                        + "the row");
    }

    // ---- AC-WRT-05

    @Test
    void ac_wrt_05_expect_version_with_keep_version_and_an_empty_change_set_throws_mq1606_at_build() {
        var empty = new PatchChanges();
        assertThatThrownBy(() -> update().set(empty).whereKey(1L).expectVersion(3L).keepVersion().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1606))
                .hasMessage("MQ1606: OrderPatch: expectVersion(...) with keepVersion() and nothing to write; check "
                        + "Changes#isEmpty() before updating");
        // Without keepVersion it still increments the version; without expectVersion it is a no-op (R-WRT-07).
        assertThatCode(() -> update().set(empty).whereKey(1L).expectVersion(3L).build()).doesNotThrowAnyException();
        assertThatCode(() -> update().set(empty).whereKey(1L).keepVersion().build()).doesNotThrowAnyException();
        assertThatCode(() -> update().set(new PatchChanges().with(STATUS, "PAID")).whereKey(1L).expectVersion(3L)
                .keepVersion().build()).doesNotThrowAnyException();
    }

    @Test
    void ac_wrt_05_changing_a_change_set_after_set_leaves_the_builder_and_the_built_update_unchanged() {
        var changes = new PatchChanges().with(STATUS, "PAID").with(NOTE, null);
        ModelUpdate.Keyed<Order, Long, OrderPatch> keyed = update().set(changes).whereKey(1L);
        ModelUpdate<Order, OrderPatch> built = keyed.build();

        changes.unset(STATUS).with(TOTAL, 10L).with(NOTE, "kept");

        List<Assignment<OrderPatch, ?>> expected = List.of(Assignment.of(STATUS, "PAID"), Assignment.ofNull(NOTE));
        assertThat(built.assignments()).isEqualTo(expected);
        assertThat(keyed.build().assignments()).isEqualTo(expected);
        assertThatThrownBy(() -> built.assignments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- AC-WRT-08

    @Test
    void ac_wrt_08_a_write_whose_every_filter_was_skipped_throws_mq1601() {
        assertThatThrownBy(() -> update().set(STATUS, "PAID")
                        .where(f -> f.eq(STATUS, Optional.empty()).eq(NOTE, Optional.empty()))
                        .build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1601))
                .hasMessage("MQ1601: OrderPatch: where(...) left no predicate, since every filter was skipped; all() "
                        + "writes every row");
        assertThatThrownBy(() -> delete().where(f -> f.or(o -> o.eq(STATUS, Optional.empty()))).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1601));
        assertThat(update().set(STATUS, "PAID").all().build().rootEntity()).isEqualTo(Order.class);
        assertThat(delete().all().build().rootEntity()).isEqualTo(Order.class);
    }

    @Test
    void ac_wrt_08_keys_narrowed_by_a_skipped_where_and_no_keys_still_count_as_a_predicate() {
        Optional<String> none = Optional.empty();
        assertThatCode(() -> update().set(STATUS, "PAID").whereKey(1L).where(f -> f.eq(NOTE, none)).build())
                .doesNotThrowAnyException();
        assertThatCode(() -> update().set(STATUS, "PAID").whereKeys(List.of()).where(f -> f.eq(NOTE, none)).build())
                .doesNotThrowAnyException();
        assertThatCode(() -> delete().whereKey(1L).where(f -> f.eq(NOTE, none)).build()).doesNotThrowAnyException();
        assertThatCode(() -> delete().whereKeys(List.of()).build()).doesNotThrowAnyException();
    }

    // ---- AC-WRT-06

    @Test
    void ac_wrt_06_where_keys_writes_each_distinct_converted_key_once_in_first_seen_order() {
        var flags = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(FLAGGED)).set(STATUS, "PAID")
                .whereKeys(List.of(true, false, true)).build();
        var composite = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.composite(ID, FLAGGED))
                .whereKeys(List.of(List.of(2L, false), List.of(1L, true), List.of(2L, false))).build();

        assertThat(flags.distinctKeys()).contains(List.of("Y", "N"));
        assertThat(composite.distinctKeys()).contains(List.of(List.of(2L, "N"), List.of(1L, "Y")));
        assertThat(update().set(STATUS, "PAID").where(f -> f.eq(NOTE, "x")).build().distinctKeys()).isEmpty();
        assertThatThrownBy(() -> ModelDelete.builder(ROOT).primaryKey(PrimaryKey.composite(ID, FLAGGED))
                .whereKeys(List.of(List.of(1L))).build().distinctKeys())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- AC-WRT-10

    @Test
    void ac_wrt_10_a_write_own_persistence_context_mode_is_empty_unless_set() {
        assertThat(update().set(STATUS, "PAID").whereKey(1L).build().persistenceContext()).isEmpty();
        assertThat(delete().whereKey(1L).persistenceContext(PersistenceContextMode.KEEP).build()
                .persistenceContext()).contains(PersistenceContextMode.KEEP);
    }

    // ---- AC-WRT-09

    @Test
    void ac_wrt_09_set_expression_on_a_converted_column_throws_mq1609() {
        assertThatThrownBy(() -> update().setExpression(FLAGGED, (path, cb) -> path).whereKey(1L).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1609))
                .hasMessage("MQ1609: OrderPatch.flagged: setExpression(...) on a column converted by FlagConverter; "
                        + "the expression's path has the entity attribute's type, not the model's");
        assertThatCode(() -> update().set(FLAGGED, true).setExpression(TOTAL, (path, cb) -> cb.sum(path, 1L))
                .whereKey(1L).build()).doesNotThrowAnyException();
    }
}
