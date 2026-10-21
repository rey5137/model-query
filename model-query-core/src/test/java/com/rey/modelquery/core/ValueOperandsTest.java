package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The shared value rule and the inline setting {@code RenderOptions} carries: a binder that types by the column makes
 * the parameter, else only a mapping-independent value binds, the rest {@code MQ1502} naming the label; every
 * parameter goes to the sink (api/12 R-FLT-08, R-FLT-21, vendor/40 R-VND-28; TCK AC-FLT-35, AC-FLT-36).
 */
class ValueOperandsTest {

    enum Status { OPEN }

    /** A sink over a map, as a build's {@code JoinContext} is. */
    private static final class Sink implements ParameterSink {
        final Map<ParameterExpression<?>, Object> bound = new IdentityHashMap<>();

        @Override
        public boolean binds(ParameterExpression<?> parameter) {
            return bound.containsKey(parameter);
        }

        @Override
        public void add(ParameterExpression<?> parameter, Object value) {
            bound.put(parameter, value);
        }
    }

    private final CriteriaBuilder cb = criteria();
    private final Expression<?> column = fake(Expression.class);

    @Test
    void ac_flt_36_inline_value_handling_is_off_by_default_and_carried_to_a_scratch_build() {
        assertThat(RenderOptions.portable().inlinesValues()).isFalse();
        assertThat(RenderOptions.of(10, 20).inlinesValues()).isFalse();
        RenderOptions inline = RenderOptions.of(10, 20).withInlinesValues(true);
        assertThat(inline.inlinesValues()).isTrue();
        // A wither keeps it, and a scratch build under its own limits renders as the real build does.
        assertThat(inline.withMetamodel(fake(Metamodel.class)).inlinesValues()).isTrue();
        assertThat(RenderOptions.of(3, 5).withRenderersOf(inline).inlinesValues()).isTrue();
        assertThat(inline.withRenderersOf(RenderOptions.portable()).inlinesValues()).isTrue();
        assertThat(inline.withInlinesValues(false).inlinesValues()).isFalse();
    }

    @Test
    void ac_flt_36_without_a_binder_a_mapping_independent_value_binds_by_its_class() {
        Sink sink = new Sink();
        for (Object value : List.of("PAID", 7L, new BigDecimal("1.50"), LocalDate.of(2020, 1, 1))) {
            Expression<?> operand = ValueOperands.valueOperand(RenderOptions.portable(), cb, column, Object.class,
                    value, "O.status: eq(...)", sink);
            assertThat(sink.bound).containsEntry((ParameterExpression<?>) operand, value);
        }
        assertThat(sink.bound).hasSize(4);
    }

    @Test
    void ac_flt_36_without_a_binder_an_enum_a_boolean_a_uuid_or_an_array_is_mq1502_naming_the_label() {
        for (Object value : List.of(Status.OPEN, true, UUID.randomUUID(), new LocalDate[0])) {
            Sink sink = new Sink();
            assertThatThrownBy(() -> ValueOperands.valueOperand(RenderOptions.portable(), cb, column, Object.class,
                    value, "O.status: eq(...) under inline value handling", sink))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("O.status: eq(...) under inline value handling")
                    .hasMessageContaining("without the provider's operand binder");
            assertThat(sink.bound).isEmpty();
        }
    }

    @Test
    void ac_flt_35_a_binder_typing_by_the_column_makes_the_parameter_and_a_null_typed_by_skips_it() {
        List<Expression<?>> typedBy = new ArrayList<>();
        OperandBinder binder = new OperandBinder() {
            @Override
            public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> by, Class<T> type) {
                typedBy.add(by);
                return fake(ParameterExpression.class);
            }
        };
        RenderOptions options = RenderOptions.portable().withOperandBinder(binder).withInlinesValues(true);
        Sink sink = new Sink();
        Expression<?> enumOperand = ValueOperands.valueOperand(options, cb, column, Object.class, Status.OPEN,
                "O.status: eq(...)", sink);
        assertThat(typedBy).containsExactly(column);
        assertThat(sink.bound).containsEntry((ParameterExpression<?>) enumOperand, Status.OPEN);
        // No column to type by, a <C, V> operator's value: the binder isn't asked, so an enum is MQ1502 (D-169).
        assertThatThrownBy(() -> ValueOperands.valueOperand(options, cb, null, Object.class, Status.OPEN,
                "O.status: op(is)", sink))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502));
        assertThat(typedBy).hasSize(1);
    }

    @Test
    void ac_flt_35_a_binder_reusing_a_parameter_the_statement_binds_is_mq1502() {
        ParameterExpression<?> shared = fake(ParameterExpression.class);
        OperandBinder binder = new OperandBinder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> by, Class<T> type) {
                return (ParameterExpression<T>) shared;
            }
        };
        RenderOptions options = RenderOptions.portable().withOperandBinder(binder);
        Sink sink = new Sink();
        ValueOperands.valueOperand(options, cb, column, Object.class, "a", "O.status: in(...)", sink);
        assertThatThrownBy(() -> ValueOperands.valueOperand(options, cb, column, Object.class, "b",
                "O.status: in(...)", sink))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("already binds");
        assertThat(sink.bound).containsEntry(shared, "a").hasSize(1);
    }

    /** A CriteriaBuilder whose {@code parameter} makes a new fake parameter on every call. */
    private static CriteriaBuilder criteria() {
        return (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("parameter")) {
                        return fake(ParameterExpression.class);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<?> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
