package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** The factory checks {@code MQ1501} to {@code MQ1506}, which run where the factory is called (api/10 R-COL-17/18). */
class ExprFactoryTest {

    static final class Entity {}

    static final class Model {}

    enum Colour {
        RED, BLUE
    }

    /** Reads an integer attribute as a string, so an expression over it is {@code MQ1501}. */
    static final class CodeConverter implements ColumnConverter<String, Integer> {
        @Override
        public String toModel(Integer attribute) {
            return attribute == null ? null : attribute.toString();
        }

        @Override
        public Integer toAttribute(String model) {
            return model == null ? null : Integer.valueOf(model);
        }
    }

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final ColumnField<Model, Entity, Integer> QUANTITY =
            ColumnField.of(Model.class, ROOT, "quantity", Integer.class);
    private static final ColumnField<Model, Entity, Integer> OTHER_QTY =
            ColumnField.of(Model.class, ROOT, "otherQty", Integer.class);
    private static final ColumnField<Model, Entity, String> CODE =
            ColumnField.of(Model.class, ROOT, "code", String.class);
    private static final ColumnField<Model, Entity, String> OTHER_CODE =
            ColumnField.of(Model.class, ROOT, "otherCode", String.class);
    private static final ColumnField<Model, Entity, Colour> COLOUR =
            ColumnField.of(Model.class, ROOT, "colour", Colour.class);
    private static final ColumnField<Model, Entity, String> CONVERTED =
            ColumnField.of(Model.class, ROOT, "code", String.class, Integer.class, new CodeConverter());

    @Test
    void ac_col_19_mq1501_an_expression_over_a_converted_column_is_refused() {
        assertMq(MqCode.MQ1501, () -> Expr.coalesce(CONVERTED, "x"),
                "an expression reads a column with a ColumnConverter");
    }

    @Test
    void ac_col_19_mq1502_a_null_or_unbindable_value_is_refused() {
        assertMq(MqCode.MQ1502, () -> Expr.coalesce(CODE, (String) null), "an expression value is null");
        assertMq(MqCode.MQ1502, () -> Expr.nullIf(CODE, (String) null), "an expression value is null");
        assertMq(MqCode.MQ1502, () -> Expr.coalesce(COLOUR, Colour.RED),
                "an expression cannot bind a value of type Colour");
        assertMq(MqCode.MQ1502, () -> Expr.plus(QUANTITY, (Integer) null), "an expression value is null");
    }

    @Test
    void ac_col_19_mq1503_divided_by_two_integral_operands_is_refused() {
        assertMq(MqCode.MQ1503, () -> Expr.dividedBy(QUANTITY, OTHER_QTY),
                "an integral division truncates on PostgreSQL and H2 and not on MySQL");
    }

    @Test
    void ac_col_19_mq1504_a_case_condition_with_no_filter_is_refused() {
        assertMq(MqCode.MQ1504, () -> Expr.cases(Model.class, Integer.class).when(f -> f, 1),
                "the condition left no filter");
        assertMq(MqCode.MQ1504,
                () -> Expr.cases(Model.class, Integer.class).when(f -> f.eq(CODE, Optional.<String>empty()), 1),
                "the condition left no filter");
    }

    @Test
    void ac_col_19_mq1505_a_case_condition_with_add_exists_or_a_sub_select_is_refused() {
        String message = "cannot use add(...), exists(...) or a sub-select";
        assertMq(MqCode.MQ1505,
                () -> Expr.cases(Model.class, Integer.class).when(f -> f.add((ctx, cb) -> cb.conjunction()), 1),
                message);
        assertMq(MqCode.MQ1505,
                () -> Expr.cases(Model.class, Integer.class).when(f -> f.exists(TableField.join(ROOT, "items", INNER)),
                        1),
                message);
        assertMq(MqCode.MQ1505,
                () -> Expr.cases(Model.class, Integer.class).when(f -> f.in(CODE, SubSelect.of(OTHER_CODE)), 1),
                message);
    }

    @Test
    void ac_col_19_mq1506_a_function_name_that_is_not_plain_or_is_an_aggregate_is_refused() {
        String message = "a function name must be a plain SQL identifier that is not a built-in aggregate";
        assertMq(MqCode.MQ1506, () -> Expr.function("count", String.class, CODE), message);
        assertMq(MqCode.MQ1506, () -> Expr.function("bad name", String.class, CODE), message);
        // A valid name over a converted column is MQ1501, not MQ1506: the name is checked first, then the operands.
        assertMq(MqCode.MQ1501, () -> Expr.function("lower", String.class, CONVERTED),
                "an expression reads a column with a ColumnConverter");
    }

    @Test
    void ac_col_19_cases_boxes_a_primitive_type_so_int_class_is_integer_class() {
        ExpressionField<Model, Integer> primitive = Expr.cases(Model.class, int.class)
                .when(f -> f.eq(QUANTITY, 1), 1).orNull();
        ExpressionField<Model, Integer> boxed = Expr.cases(Model.class, Integer.class)
                .when(f -> f.eq(QUANTITY, 1), 1).orNull();
        assertThat(primitive.type()).isEqualTo(Integer.class);
        assertThat(primitive).isEqualTo(boxed).hasSameHashCodeAs(boxed);
    }

    private static void assertMq(MqCode code, ThrowingCallable call, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(message);
    }
}
