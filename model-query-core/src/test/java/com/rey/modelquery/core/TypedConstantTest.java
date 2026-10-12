package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A column-typed constant: its witness, its converter, and the provider capability it renders through (R-COL-27). */
class TypedConstantTest {

    static final class Entity {}

    static final class Model {}

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final ColumnField<Model, Entity, String> STATUS =
            ColumnField.of(Model.class, ROOT, "status", String.class);
    private static final ColumnField<Model, Entity, String> CODE =
            ColumnField.of(Model.class, ROOT, "code", String.class);
    private static final ColumnField<Model, Entity, Instant> PLACED_AT = ColumnField.of(Model.class, ROOT, "placedAt",
            Instant.class, java.sql.Timestamp.class, InstantTimestampConverter.INSTANCE);

    @Test
    void ac_col_32_a_typed_constant_is_equal_by_witness_and_value_and_reads_no_column() {
        var paid = Expr.constant(STATUS, "PAID");
        assertThat(paid).isEqualTo(Expr.constant(STATUS, "PAID")).hasSameHashCodeAs(Expr.constant(STATUS, "PAID"));
        assertThat(paid).isNotEqualTo(Expr.constant(STATUS, "VOID"));
        assertThat(paid).isNotEqualTo(Expr.constant(CODE, "PAID"));
        assertThat(paid.type()).isEqualTo(String.class);
        // The witness is a type only: it adds no column and no bind (R-COL-20).
        assertThat(paid.columns()).isEmpty();
        assertThat(paid.binds()).isZero();
    }

    @Test
    void ac_col_32_a_null_value_is_mq1502_and_a_value_the_converter_refuses_is_mq1308() {
        assertThatThrownBy(() -> Expr.constant(STATUS, (String) null))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("Model.status");
        assertThatThrownBy(() -> Expr.constant(PLACED_AT, Instant.MAX))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1308))
                .hasMessageContaining("InstantTimestampConverter");
    }

    @Test
    void ac_col_32_a_provider_without_the_capability_throws_mq1508_when_the_constant_resolves() {
        assertThatThrownBy(() -> Expr.constant(STATUS, "PAID").operand(JoinContext.of(fakeRoot(), null)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1508))
                .hasMessageContaining("Model.status").hasMessageContaining("Expr.constant cannot render PAID")
                .hasMessageContaining("supplies no ConstantRenderer");
    }

    @Test
    void ac_col_32_a_provider_that_stores_no_scalar_throws_mq1508_naming_the_cause() {
        assertThatThrownBy(() -> Expr.constant(STATUS, "PAID")
                .operand(context(options -> options.withConstantRenderer((entity, attribute, value) -> Optional.empty()))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1508))
                .hasMessageContaining("must be a basic attribute of a root table's entity")
                .hasMessageContaining("embedded, dotted or joined");
        assertThatThrownBy(() -> Expr.constant(STATUS, "PAID")
                .operand(context(options -> options.withConstantRenderer(
                        (entity, attribute, value) -> Optional.of(new Object())))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1508))
                .hasMessageContaining("is not a String, Number or Boolean");
    }

    @Test
    void ac_col_32_a_witness_on_a_joined_table_throws_mq1508_even_with_a_renderer() {
        TableField<Entity, Entity> joined = TableField.join(ROOT, "parent", jakarta.persistence.criteria.JoinType.LEFT);
        ColumnField<Model, Entity, String> joinedStatus = ColumnField.of(Model.class, joined, "status", String.class);
        assertThatThrownBy(() -> Expr.constant(joinedStatus, "PAID").operand(
                context(options -> options.withConstantRenderer((entity, attribute, value) -> Optional.of("x")))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1508))
                .hasMessageContaining("no root table's entity");
    }

    @Test
    void ac_col_31_a_typed_constant_is_operand_only_in_a_projection_an_order_a_group_key_or_a_function_argument() {
        var context = context(options -> options.withConstantRenderer((entity, attribute, value) -> Optional.of("x")));
        var constant = Expr.constant(STATUS, "PAID");
        assertThatThrownBy(() -> constant.expression(context))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1508))
                .hasMessageContaining("operand-only");
        // Nested in another expression, which resolves its arguments through expression(...).
        assertThatThrownBy(() -> Expr.function("lower", String.class, constant).expression(context))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1508))
                .hasMessageContaining("operand-only");
    }

    @Test
    void ac_col_32_the_renderer_receives_the_converted_attribute_value_and_its_literal_is_rendered() {
        AtomicReference<Object> seen = new AtomicReference<>();
        var context = context(options -> options.withConstantRenderer((entity, attribute, value) -> {
            seen.set(value);
            assertThat(entity).isEqualTo(Entity.class);
            assertThat(attribute).isEqualTo("status");
            return Optional.of("stored");
        }));
        Expression<String> expression = Expr.constant(STATUS, "PAID").operand(context);
        assertThat(seen.get()).isEqualTo("PAID");
        assertThat(expression.getJavaType()).isEqualTo(String.class);
    }

    /** A portable context whose renderer is {@code renderer}, over a root of the test entity. */
    private static JoinContext context(java.util.function.UnaryOperator<RenderOptions> renderer) {
        CriteriaBuilder cb = (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) ->
                        method.getName().equals("literal") ? fake(Expression.class, args[0].getClass()) : null);
        return JoinContext.of(fakeRoot(), cb, null, renderer.apply(RenderOptions.portable()));
    }

    @SuppressWarnings("unchecked")
    private static Root<Entity> fakeRoot() {
        return (Root<Entity>) Proxy.newProxyInstance(Root.class.getClassLoader(), new Class<?>[] {Root.class},
                (proxy, method, args) -> method.getName().equals("getJavaType") ? Entity.class : null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Class<?> javaType) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> method.getName().equals("getJavaType") ? javaType : null);
    }
}
