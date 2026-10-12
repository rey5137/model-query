package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A bound function argument: its class, its parameter, and the types a factory refuses (R-COL-26, R-COL-18). */
class BoundValueTest {

    static final class Entity {}

    static final class Model {}

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final ColumnField<Model, Entity, Object> CODE = ColumnField.of(Model.class, ROOT, "code",
            Object.class);

    @Test
    void ac_col_31_a_value_is_equal_by_class_and_value_binds_one_and_reads_no_column() {
        var vip = Expr.value("vip");
        assertThat(vip).isEqualTo(Expr.value("vip")).hasSameHashCodeAs(Expr.value("vip"));
        assertThat(vip).isNotEqualTo(Expr.value("other"));
        assertThat(vip).isNotEqualTo(Expr.value(1)); // the class counts, not only the value (R-COL-20)
        assertThat(vip.type()).isEqualTo(String.class);
        assertThat(vip.columns()).isEmpty();
        assertThat(vip.binds()).isEqualTo(1);
    }

    @Test
    void ac_col_31_a_null_optional_collection_or_map_value_is_mq1502() {
        assertThatThrownBy(() -> Expr.value((Object) null))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502)).hasMessageContaining("is null");
        for (Object refused : List.of(Optional.of(1), List.of(), Map.of())) {
            assertThatThrownBy(() -> Expr.value(refused))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("cannot bind a value of type");
        }
    }

    @Test
    void ac_col_31_the_widened_check_refuses_an_optional_on_another_factory() {
        // The check is every factory's, not Expr.value's alone (R-COL-18).
        assertThatThrownBy(() -> Expr.coalesce(CODE, Optional.of("x")))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("Optional");
    }

    @Test
    void ac_col_31_a_value_renders_one_parameter_the_executor_binds() {
        JoinContext ctx = context();
        Expression<?> rendered = Expr.value("vip").expression(ctx);
        assertThat(rendered).isSameAs(Expr.value("vip").expression(ctx)); // one node per context (R-COL-19)
        assertThat(ctx.statementParameters()).hasSize(1);
        assertThat(ctx.statementParameters()).allSatisfy((parameter, value) -> assertThat(value).isEqualTo("vip"));
    }

    /** A portable context whose criteria builder makes a parameter of the type it is asked for. */
    private static JoinContext context() {
        CriteriaBuilder cb = (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "parameter" -> fake(ParameterExpression.class, (Class<?>) args[0]);
                    default -> null;
                });
        return JoinContext.of(fakeRoot(), cb);
    }

    @SuppressWarnings("unchecked")
    private static Root<Entity> fakeRoot() {
        return (Root<Entity>) Proxy.newProxyInstance(Root.class.getClassLoader(), new Class<?>[] {Root.class},
                (proxy, method, args) -> method.getName().equals("getJavaType") ? Entity.class : null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Class<?> javaType) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJavaType" -> javaType;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName() + " of " + javaType.getName();
                    default -> null;
                });
    }
}
