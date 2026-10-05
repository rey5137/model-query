package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code Expr}, {@code ExpressionField}, {@code ScalarField} and what M9.14 still owes (api/10 R-COL-16 to R-COL-20). */
class ExpressionFieldTest {

    static final class Entity {}

    static final class Model {}

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final ColumnField<Model, Entity, Integer> QUANTITY =
            ColumnField.of(Model.class, ROOT, "quantity", Integer.class);
    private static final ColumnField<Model, Entity, BigDecimal> PRICE =
            ColumnField.of(Model.class, ROOT, "price", BigDecimal.class);
    private static final ColumnField<Model, Entity, String> CODE =
            ColumnField.of(Model.class, ROOT, "code", String.class);
    private static final ColumnField<Model, Entity, String> STATUS =
            ColumnField.of(Model.class, ROOT, "status", String.class);
    private static final ExpressionField<Model, Integer> PLUS_ONE = Expr.plus(QUANTITY, 1);

    @Test
    void ac_col_18_equal_calls_are_equal_and_hash_alike_whatever_named_says() {
        assertThat(Expr.plus(QUANTITY, 1)).isNotSameAs(PLUS_ONE).isEqualTo(PLUS_ONE).hasSameHashCodeAs(PLUS_ONE);
        // named(property) is the model field for orderedBy and messages, never part of equality (D-55, R-COL-20).
        assertThat(PLUS_ONE.named("total")).isEqualTo(PLUS_ONE).hasSameHashCodeAs(PLUS_ONE);
        assertThat(PLUS_ONE.named("total").property()).contains("total");
        assertThat(PLUS_ONE.property()).isEmpty();
        // A CASE carries its conditions and its results, but not the filter objects that built it (R-COL-20).
        assertThat(cases(f -> f.eq(STATUS, "A"), 1)).isEqualTo(cases(f -> f.eq(STATUS, "A"), 1));
        assertThat(cases(f -> f.eq(STATUS, "A"), 1)).isNotEqualTo(cases(f -> f.eq(STATUS, "B"), 1));
    }

    @Test
    void ac_col_18_expressions_differ_by_value_scale_declared_type_and_function_name() {
        assertThat(Expr.plus(QUANTITY, 1)).isNotEqualTo(Expr.plus(QUANTITY, 2));
        assertThat(Expr.plus(PRICE, new BigDecimal("1.0"))).isNotEqualTo(Expr.plus(PRICE, new BigDecimal("1.00")));
        assertThat(Expr.plus(QUANTITY, PRICE, BigDecimal.class))
                .isNotEqualTo(Expr.plus(QUANTITY, PRICE, Double.class));
        assertThat(Expr.function("lower", String.class, CODE))
                .isNotEqualTo(Expr.function("upper", String.class, CODE));
        assertThat(Expr.plus(QUANTITY, 1)).isNotEqualTo(Expr.negate(QUANTITY));
    }

    @Test
    void ac_col_18_an_expression_constant_serves_eight_threads_with_identical_results() throws Exception {
        Callable<String> use = () -> PLUS_ONE.name() + "|" + PLUS_ONE.hashCode() + "|" + PLUS_ONE.equals(PLUS_ONE)
                + "|" + PLUS_ONE.columns();
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = pool.invokeAll(List.of(use, use, use, use, use, use, use, use));
            var seen = new ArrayList<String>();
            for (Future<String> result : results) {
                seen.add(result.get());
            }
            assertThat(seen).hasSize(8).containsOnly(seen.get(0));
            assertThat(seen.get(0)).contains(PLUS_ONE.name()).endsWith("[" + QUANTITY + "]");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void ac_col_18_an_aggregate_in_filters_group_by_or_expr_and_a_case_with_no_when_do_not_compile(
            @TempDir Path out) throws IOException {
        assertThat(probe(out, "((Filters<Model>) null).eq(COUNT, 1L)")).isNotEmpty();
        assertThat(probe(out, "ModelQuery.builder(ROOT, row -> new Model()).groupBy(COUNT)")).isNotEmpty();
        assertThat(probe(out, "Expr.coalesce(COUNT, 1L)")).isNotEmpty();
        assertThat(probe(out, "Expr.cases(Model.class, String.class).otherwise(\"x\")")).isNotEmpty();
        // The same call over a column and a CASE with one when does compile, so the probe is not failing for nothing.
        assertThat(probe(out, "Expr.coalesce(QUANTITY, 1)")).isEmpty();
        assertThat(probe(out, "Expr.cases(Model.class, Integer.class).when(f -> f, 1).otherwise(2)")).isEmpty();
    }

    @Test
    void m9_14_an_expression_in_the_selection_group_by_or_order_by_is_refused() {
        assertThatThrownBy(() -> selecting(PLUS_ONE).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expression " + PLUS_ONE.name() + " is not supported as a selected column until M9.14");
        assertThatThrownBy(() -> selecting(STATUS).groupBy(PLUS_ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expression " + PLUS_ONE.name() + " is not supported as a group key until M9.14");
        assertThatThrownBy(() -> selecting(STATUS).orderBy(PLUS_ONE.asc()).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expression " + PLUS_ONE.name() + " is not supported as an order key until M9.14");
        // A plain column selection, group-by and order-by still build.
        selecting(STATUS).groupBy(STATUS).orderBy(STATUS.asc()).build();
    }

    private static ExpressionField<Model, Integer> cases(java.util.function.UnaryOperator<Filters<Model>> condition,
            int result) {
        return Expr.cases(Model.class, Integer.class).when(condition, result).otherwise(0);
    }

    private static ModelQuery.Builder<Entity, Object, Model> selecting(SelectField<Model, ?> column) {
        return ModelQuery.builder(ROOT, row -> new Model()).select(SelectSet.of(column));
    }

    private static List<String> probe(Path out, String call) throws IOException {
        return CompileProbe.problems(out, MEMBERS, call, "-Xlint:unchecked");
    }

    private static final List<String> MEMBERS = List.of(
            "    static final class Entity {}",
            "    static final class Model {}",
            "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
            "    static final ColumnField<Model, Entity, Integer> QUANTITY =",
            "            ColumnField.of(Model.class, ROOT, \"quantity\", Integer.class);",
            "    static final AggregateField<Model, Long> COUNT = Agg.count(ROOT);");
}
