package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** {@code Expr.nextval}: its name check, its equality, and the provider capability it renders through (R-COL-28). */
class NextvalTest {

    static final class Entity {}

    static final class Model {}

    @Test
    void ac_col_33_a_name_that_is_not_an_optionally_qualified_identifier_is_mq1509() {
        for (String name : List.of("", "a b", "a;b", "a.b.c", "1seq", "a.", ".a", "a-b", "a'b")) {
            assertThatThrownBy(() -> Expr.nextval(name))
                    .as(name)
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                    .hasMessageContaining("plain SQL identifier");
        }
        assertThat(Expr.<Model>nextval("tck_seq").toString()).isEqualTo("nextval(tck_seq)");
        assertThat(Expr.<Model>nextval("public.tck_seq").toString()).isEqualTo("nextval(public.tck_seq)");
    }

    @Test
    void ac_col_33_a_sequence_value_is_a_long_that_reads_no_column_and_binds_nothing() {
        ExpressionField<Model, Long> next = Expr.nextval("s");
        assertThat(next.type()).isEqualTo(Long.class);
        assertThat(next.columns()).isEmpty();
        assertThat(next.binds()).isZero();
        assertThat(next).isEqualTo(Expr.nextval("s")).hasSameHashCodeAs(Expr.nextval("s"));
        assertThat(next).isNotEqualTo(Expr.nextval("t"));
    }

    @Test
    void ac_col_33_without_a_sequence_renderer_a_sequence_value_is_mq1509() {
        assertThatThrownBy(() -> Expr.<Model>nextval("s").expression(context(RenderOptions.portable())))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                .hasMessageContaining("no sequence support");
    }

    @Test
    void ac_col_33_a_sequence_renderer_renders_the_name_it_is_given() {
        AtomicReference<String> seen = new AtomicReference<>();
        var options = RenderOptions.portable().withSequenceRenderer((cb, sequence) -> {
            seen.set(sequence);
            return fake(Expression.class, Long.class);
        });
        Expression<Long> rendered = Expr.<Model>nextval("a.s").expression(context(options));
        assertThat(seen.get()).isEqualTo("a.s");
        assertThat(rendered.getJavaType()).isEqualTo(Long.class);
    }

    @Test
    void ac_col_33_a_sequence_value_in_a_clause_that_refuses_it_is_mq1509_even_with_a_renderer() {
        var options = RenderOptions.portable()
                .withSequenceRenderer((cb, sequence) -> fake(Expression.class, Long.class));
        var context = context(options);
        var next = Expr.<Model>nextval("s");
        assertThat(context.<Object>withoutSequences(() -> {
            assertThatThrownBy(() -> next.expression(context))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                    .hasMessageContaining("no filter, having, order or group key");
            // Nested in another expression, which resolves its arguments through expression(...).
            assertThatThrownBy(() -> Expr.function("abs", Long.class, next).expression(context))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509));
            return "done";
        })).isEqualTo("done");
        // Outside the refusing clause it renders.
        assertThat(next.expression(context)).isNotNull();
    }

    @Test
    void ac_col_33_an_expression_over_a_sequence_value_the_select_rendered_first_is_still_mq1509_in_a_filter() {
        var options = RenderOptions.portable()
                .withSequenceRenderer((cb, sequence) -> fake(Expression.class, Long.class));
        var context = context(options);
        var next = Expr.<Model>nextval("s");
        var nested = Expr.function("abs", Long.class, Expr.coalesce(next, 0L));
        var inCase = Expr.cases(Model.class, Long.class).when(f -> f.gt(next, 0L), 1L).otherwise(0L);
        // The select renders each first, so the filter would find them memoised.
        assertThat(nested.expression(context)).isNotNull();
        for (var expression : List.of(nested, inCase)) {
            assertThatThrownBy(() -> context.withoutSequences(() -> expression.expression(context)))
                    .as(expression.toString())
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                    .hasMessageContaining("reads a sequence value");
        }
    }

    @Test
    void ac_col_33_a_distinct_query_selecting_a_sequence_value_is_mq1509_before_any_statement() {
        var options = RenderOptions.portable()
                .withSequenceRenderer((cb, sequence) -> fake(Expression.class, Long.class));
        // Nested at depth, as the group-key refusal walks it
        var nested = Expr.function("abs", Long.class, Expr.<Model>nextval("s"));
        QueryCustomizer distinct = (spec, joins, query, cb, phase) -> query.distinct(true);
        for (Phase phase : List.of(Phase.MODEL, Phase.MODEL_BY_KEYS)) {
            assertThatThrownBy(() -> selecting(nested, distinct).buildQuery(criteria(), phase, options))
                    .as(phase.name())
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                    .hasMessageContaining("a distinct query cannot select it");
        }
        // Not distinct, it builds; distinct, a query selecting no sequence value builds too.
        assertThat(selecting(nested, (spec, joins, query, cb, phase) -> { }).buildQuery(criteria(), Phase.MODEL,
                options)).isNotNull();
        assertThat(selecting(Expr.function("abs", Long.class, Expr.<Model>constant(1L)), distinct)
                .buildQuery(criteria(), Phase.MODEL, options)).isNotNull();
    }

    @Test
    void ac_col_33_the_phase_check_of_a_customized_query_selecting_a_sequence_value_is_not_mq1509() {
        QueryCustomizer none = (spec, joins, query, cb, phase) -> { };
        selecting(Expr.<Model>nextval("s"), none).checkPhases(criteria());
        QueryCustomizer distinct = (spec, joins, query, cb, phase) -> query.distinct(true);
        assertThatThrownBy(() -> selecting(Expr.<Model>nextval("s"), distinct).checkPhases(criteria()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                .hasMessageContaining("a distinct query cannot select it");
    }

    private static ModelQuery<Entity, Object, Model> selecting(ExpressionField<Model, Long> column,
            QueryCustomizer customizer) {
        return ModelQuery.builder(TableField.root(Entity.class), row -> new Model()).select(SelectSet.of(column))
                .customize(customizer).build();
    }

    /** A CriteriaBuilder handing out one query that records {@code distinct}; every expression is a fake. */
    private static CriteriaBuilder criteria() {
        boolean[] distinct = {false};
        CriteriaQuery<?> query = (CriteriaQuery<?>) Proxy.newProxyInstance(CriteriaQuery.class.getClassLoader(),
                new Class<?>[] {CriteriaQuery.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "from" -> fakeRoot();
                    case "distinct" -> {
                        distinct[0] = (boolean) args[0];
                        yield proxy;
                    }
                    case "isDistinct" -> distinct[0];
                    case "getOrderList", "getGroupList" -> List.of();
                    case "getRoots" -> Set.of();
                    default -> method.getReturnType().isInstance(proxy) ? proxy : null;
                });
        return (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("createTupleQuery")) {
                        return query;
                    }
                    if (Expression.class.isAssignableFrom(method.getReturnType())) {
                        return fake(method.getReturnType(), Long.class);
                    }
                    return null;
                });
    }

    private static JoinContext context(RenderOptions options) {
        CriteriaBuilder cb = (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> {
                    if (Expression.class.isAssignableFrom(method.getReturnType())) {
                        return fake(method.getReturnType(), Long.class);
                    }
                    return null;
                });
        return JoinContext.of(fakeRoot(), cb, null, options);
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
