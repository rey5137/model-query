package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Definition-time refusals of correlated values (R-COL-33). */
class CorrelatedRefusalTest {

    static final class Entity {}

    static final class Model {}

    static final class Inner {}

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final ColumnField<Model, Entity, Long> ID = ColumnField.of(Model.class, ROOT, "id", Long.class);
    private static final ColumnField<Model, Entity, String> STATUS =
            ColumnField.of(Model.class, ROOT, "status", String.class);
    private static final ColumnField<Inner, Entity, Long> INNER_ID =
            ColumnField.of(Inner.class, ROOT, "id", Long.class);
    private static final ExpressionField<Model, Long> COUNT = Correlated.count(Model.class, SubSelect.of(INNER_ID),
            (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID)));

    @Test
    void ac_col_41_case_conditions_refuse_correlated_values_on_either_side_and_nested() {
        assertMq(MqCode.MQ1505, () -> Expr.cases(Model.class, Long.class).when(f -> f.eq(COUNT, 1L), 1L));
        assertMq(MqCode.MQ1505, () -> Expr.cases(Model.class, Long.class)
                .when(f -> f.compare(ID, Op.EQ, Expr.plus(COUNT, 1L)), 1L));
        assertMq(MqCode.MQ1505, () -> Expr.cases(Model.class, Long.class)
                .when(f -> f.or(or -> or.eq(ID, 1L), or -> or.eq(Expr.coalesce(COUNT, 0L), 2L)), 1L));
        assertMq(MqCode.MQ1505, () -> Expr.cases(Model.class, Long.class)
                .when(f -> f.in(ID, Expr.plus(COUNT, 1L), ID), 1L));
        // A CASE result is a scalar position and can itself be correlated.
        assertThat(Expr.cases(Model.class, Long.class).when(f -> f.eq(ID, 1L), COUNT).otherwise(0L).correlated())
                .isTrue();
    }

    @Test
    void ac_col_41_every_aggregate_factory_refuses_a_correlated_expression() {
        ExpressionField<Model, Long> nested = Expr.plus(Expr.coalesce(COUNT, 0L), 1L);
        assertMq(MqCode.MQ1510, () -> Agg.count(nested));
        assertMq(MqCode.MQ1510, () -> Agg.countDistinct(nested));
        assertMq(MqCode.MQ1510, () -> Agg.sum(nested));
        assertMq(MqCode.MQ1510, () -> Agg.sumAsLong(nested));
        assertMq(MqCode.MQ1510, () -> Agg.avg(nested));
        assertMq(MqCode.MQ1510, () -> Agg.sum(Expr.function("as_int", Integer.class, COUNT)));
        assertMq(MqCode.MQ1510, () -> Agg.sumAsLong(Expr.function("as_double", Double.class, COUNT)));
        assertMq(MqCode.MQ1510, () -> Agg.min(nested));
        assertMq(MqCode.MQ1510, () -> Agg.max(nested));
        assertMq(MqCode.MQ1510, () -> Agg.count(Expr.cases(Model.class, Long.class)
                .when(f -> f.eq(ID, 1L), COUNT).otherwise(0L)));
    }

    @Test
    void ac_col_41_a_correlated_group_key_is_mq1314_in_both_group_by_overloads() {
        assertMq(MqCode.MQ1314, () -> selecting(COUNT).groupBy(COUNT));
        assertMq(MqCode.MQ1314, () -> selecting(COUNT).groupBy(SelectSet.of(Expr.plus(COUNT, 1L))));
    }

    @Test
    void ac_col_41_grouped_selection_and_orders_require_every_lifted_group_key() {
        assertMq(MqCode.MQ1314, () -> selecting(COUNT).groupBy(STATUS).build());
        assertMq(MqCode.MQ1314, () -> selecting(STATUS).groupBy(STATUS).orderBy(COUNT.asc()).build());
        selecting(COUNT).groupBy(ID).build();
        selecting(ID).groupBy(ID).orderBy(COUNT.asc()).where(f -> f.eq(COUNT, 1L)).build();
        ExpressionField<Model, Long> twoKeys = Correlated.count(Model.class, SubSelect.of(INNER_ID),
                (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID))
                        .eq(outer.column(STATUS), "open"));
        assertMq(MqCode.MQ1314, () -> selecting(twoKeys).groupBy(ID).build());
        selecting(twoKeys).groupBy(ID, STATUS).build();
        // An ordinary ungrouped operand keeps the existing aggregate diagnostic.
        assertMq(MqCode.MQ1401, () -> selecting(Expr.concat(STATUS, Expr.function("str", String.class, COUNT)))
                .groupBy(ID).build());
    }

    @Test
    void ac_col_41_grouped_where_can_lift_a_non_group_key() {
        assertThat(selecting(STATUS).groupBy(STATUS).where(f -> f.gt(COUNT, 0L)).build()).isNotNull();
    }

    @Test
    void ac_col_41_keyset_refuses_a_correlated_order_while_offset_and_primary_key_first_accept_it() {
        var query = selecting(ID).primaryKey(PrimaryKey.of(ID)).orderBy(COUNT.desc());
        assertMq(MqCode.MQ1208, () -> query.keyset().build());
        assertThat(query.build().orderBy()).contains(COUNT.desc());
        assertThat(query.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build().orderBy())
                .contains(COUNT.desc());
    }

    @Test
    void ac_wrt_32_ac_col_41_conflict_update_where_refuses_correlated_values_before_rendering() {
        var columns = InsertColumns.<Model, Entity>of(ROOT).addKey(ID, row -> 1L).add(STATUS, row -> "NEW");
        var predicates = List.<UnaryOperator<Filters<Model>>>of(
                f -> f.eq(COUNT, 1L),
                f -> f.compare(ID, Op.EQ, COUNT),
                f -> f.in(ID, COUNT, ID),
                f -> f.or(g -> g.eq(ID, 1L), g -> g.eq(Expr.coalesce(COUNT, 0L), 2L)),
                f -> f.eq(Expr.cases(Model.class, Long.class).when(g -> g.eq(ID, 1L), COUNT).otherwise(0L), 1L));
        for (var predicate : predicates) {
            assertThatThrownBy(() -> ValuesInsert.builder(columns, Long.class, List.of(new Model()))
                    .onConflict(ID).doUpdate(u -> u.setFromRow(STATUS).where(predicate)).build())
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            error -> assertThat(error.code()).isEqualTo(MqCode.MQ1804))
                    .hasMessageContainingAll("correlated value", "no join or sub-query");
        }
    }

    private static ModelQuery.Builder<Entity, Object, Model> selecting(SelectField<Model, ?> field) {
        return ModelQuery.builder(ROOT, row -> new Model()).select(SelectSet.of(field));
    }

    private static void assertMq(MqCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
