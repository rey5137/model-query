package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@code checkPhases}' scratch build binds an {@code op} value as the real build does (spec D-169 (4), R-FLT-21). */
class OperatorScratchBinderTest {

    enum Status { PENDING }

    static final class Entity {}

    static final class Model {}

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final ColumnField<Model, Entity, Status> STATUS =
            ColumnField.of(Model.class, ROOT, "status", Status.class);
    private static final FilterOperator<Status, Status> SAME =
            FilterOperator.of("same", (ctx, left, right) -> ctx.cb().conjunction());

    private static ModelQuery<Entity, Object, Model> query() {
        return ModelQuery.builder(ROOT, row -> new Model()).select(SelectSet.of(STATUS))
                .where(f -> f.op(SAME, STATUS, Status.PENDING))
                .customize((spec, joins, query, cb, phase) -> { }).build();
    }

    @Test
    void ac_flt_22_check_phases_with_options_that_carry_no_binder_refuses_an_enum_value_with_mq1502() {
        assertThatThrownBy(() -> query().checkPhases(criteria(), RenderOptions.portable()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502));
    }

    @Test
    void ac_flt_22_check_phases_with_no_options_uses_the_stand_in_binder_for_an_enum_value() {
        query().checkPhases(criteria());
    }

    @Test
    void ac_flt_22_check_phases_with_options_that_carry_a_binder_uses_it() {
        OperandBinder binder = new OperandBinder() {
            @Override
            public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
                return cb.parameter(type);
            }
        };
        query().checkPhases(criteria(), RenderOptions.portable().withOperandBinder(binder));
    }

    /** A CriteriaBuilder handing out one query; every expression is a fake. */
    private static CriteriaBuilder criteria() {
        CriteriaQuery<?> query = (CriteriaQuery<?>) Proxy.newProxyInstance(CriteriaQuery.class.getClassLoader(),
                new Class<?>[] {CriteriaQuery.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "from" -> fakeRoot();
                    case "isDistinct" -> false;
                    case "getOrderList", "getGroupList" -> List.of();
                    case "getRoots" -> Set.of();
                    default -> method.getReturnType().isInstance(proxy) ? proxy : null;
                });
        return (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("createTupleQuery")) {
                        return query;
                    }
                    if (method.getName().equals("parameter")) {
                        return fake(ParameterExpression.class, (Class<?>) args[0]);
                    }
                    if (Expression.class.isAssignableFrom(method.getReturnType())) {
                        return fake(method.getReturnType(), Long.class);
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private static Root<Entity> fakeRoot() {
        return (Root<Entity>) Proxy.newProxyInstance(Root.class.getClassLoader(), new Class<?>[] {Root.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJavaType" -> Entity.class;
                    case "get" -> fake(Path.class, Status.class);
                    default -> null;
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Class<?> javaType) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJavaType" -> javaType;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
