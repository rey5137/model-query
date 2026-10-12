package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code in} and {@code notIn} over operand fields, which put an inlined literal in the set (spec api/12 R-FLT-19). */
class LiteralFiltersTest {

    static final class Entity {}

    static final class Model {}

    static final class OtherModel {}

    static final class OtherEntity {}

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final TableField<OtherEntity, OtherEntity> OTHER_ROOT = TableField.root(OtherEntity.class);
    private static final ColumnField<Model, Entity, String> STATUS =
            ColumnField.of(Model.class, ROOT, "status", String.class);
    private static final ColumnField<Model, Entity, String> CODE =
            ColumnField.of(Model.class, ROOT, "code", String.class);
    private static final ColumnField<Model, Entity, Integer> TOTAL =
            ColumnField.of(Model.class, ROOT, "total", Integer.class);
    private static final ColumnField<OtherModel, Entity, String> OTHER_STATUS =
            ColumnField.of(OtherModel.class, ROOT, "status", String.class);
    private static final ColumnField<Model, OtherEntity, String> LABEL =
            ColumnField.of(Model.class, OTHER_ROOT, "label", String.class);

    @Test
    void ac_flt_20_the_field_forms_record_their_operands_as_the_condition_values() {
        var pending = Expr.constant(STATUS, "PENDING");
        var failed = Expr.constant(STATUS, "FAILED");
        List<Condition> conditions = conditions(f -> f.in(STATUS, pending, failed));
        assertThat(conditions).hasSize(1);
        assertThat(conditions.get(0).kind()).isEqualTo(Condition.Kind.IN);
        assertThat(conditions.get(0).column()).contains(STATUS);
        assertThat(conditions.get(0).values()).containsExactly(pending, failed);

        // The three-operand form, and notIn, which records the same way.
        assertThat(conditions(f -> f.in(STATUS, pending, failed, CODE))).singleElement()
                .satisfies(condition -> assertThat(condition.values()).containsExactly(pending, failed, CODE));
        assertThat(conditions(f -> f.notIn(STATUS, pending, failed, CODE))).singleElement()
                .satisfies(condition -> {
                    assertThat(condition.kind()).isEqualTo(Condition.Kind.NOT_IN);
                    assertThat(condition.values()).containsExactly(pending, failed, CODE);
                });
        // An operand may be a column of the model, on another table of it.
        assertThat(conditions(f -> f.in(STATUS, pending, LABEL))).singleElement()
                .satisfies(condition -> assertThat(condition.values()).containsExactly(pending, LABEL));
    }

    @Test
    void ac_flt_20_a_null_operand_throws_mq1301_naming_the_column_and_the_operator() {
        ScalarField<Model, String> none = null;
        for (UnaryOperator<Filters<Model>> call : List.<UnaryOperator<Filters<Model>>>of(
                f -> f.in(STATUS, STATUS, none), f -> f.in(STATUS, none, STATUS, CODE),
                f -> f.notIn(STATUS, STATUS, none), f -> f.notIn(STATUS, none, STATUS, CODE))) {
            assertThatThrownBy(() -> FilterGroup.collect(call))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301))
                    .hasMessageStartingWith("MQ1301: Model.status")
                    .hasMessageContaining("null operand");
        }
    }

    @Test
    void ac_flt_20_var_infers_the_call_and_operands_of_another_model_or_type_do_not_compile(@TempDir Path out)
            throws IOException {
        assertThat(probe(out, "((java.util.function.UnaryOperator<Filters<Model>>) f -> {"
                + " var x = f.in(STATUS, STATUS, STATUS); return x; }).apply(null)")).isEmpty();
        assertThat(probe(out, "((java.util.function.UnaryOperator<Filters<Model>>) f ->"
                + " f.in(STATUS, OTHER_STATUS, STATUS)).apply(null)")).isNotEmpty();
        assertThat(probe(out, "((java.util.function.UnaryOperator<Filters<Model>>) f ->"
                + " f.notIn(STATUS, TOTAL, STATUS)).apply(null)")).isNotEmpty();
    }

    private static List<Condition> conditions(UnaryOperator<Filters<Model>> operator) {
        return ConditionGroup.conditions(FilterGroup.collect(operator));
    }

    private static List<String> probe(Path out, String call) throws IOException {
        return CompileProbe.problems(out, MEMBERS, call);
    }

    private static final List<String> MEMBERS = List.of(
            "    static final class Entity {}",
            "    static final class Model {}",
            "    static final class OtherModel {}",
            "    static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
            "    static final ColumnField<Model, Entity, String> STATUS =",
            "            ColumnField.of(Model.class, ROOT, \"status\", String.class);",
            "    static final ColumnField<Model, Entity, Integer> TOTAL =",
            "            ColumnField.of(Model.class, ROOT, \"total\", Integer.class);",
            "    static final ColumnField<OtherModel, Entity, String> OTHER_STATUS =",
            "            ColumnField.of(OtherModel.class, ROOT, \"status\", String.class);");
}
