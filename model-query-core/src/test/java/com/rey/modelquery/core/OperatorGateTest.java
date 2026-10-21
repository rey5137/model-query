package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The operator gate and the operand binder {@code RenderOptions} carries to a filter's render: the vendor check first
 * ({@code MQ4015}), then the provider's ({@code MQ4014}), no gate {@code MQ4014}, a value typed by the binder, and an
 * ignore-case value bound through it only over a column it says converts (api/12 R-FLT-07, R-FLT-20, R-FLT-21,
 * vendor/40 R-VND-23, R-VND-24, D-169, D-177).
 */
class OperatorGateTest {

    enum Status { OPEN, CLOSED }

    static final class Order {}

    static final class OrderView {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<OrderView, Order, String> NAME =
            ColumnField.of(OrderView.class, ROOT, "name", String.class);
    private static final ColumnField<OrderView, Order, String> CODE =
            ColumnField.of(OrderView.class, ROOT, "code", String.class);
    private static final ColumnField<OrderView, Order, Status> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", Status.class);
    private static final ColumnField<OrderView, Order, Boolean> ACTIVE =
            ColumnField.of(OrderView.class, ROOT, "active", Boolean.class);

    private static final FilterOperator<String, String> MATCHES =
            FilterOperator.<String>of("matches", (ctx, left, right) -> ctx.cb().conjunction())
                    .requires(OperatorFeature.REGEX_MATCH);
    private static final FilterOperator<Status, Status> STATUS_IS =
            FilterOperator.of("statusIs", (ctx, left, right) -> ctx.cb().conjunction());
    private static final FilterOperator<String, String> NAME_IS =
            FilterOperator.of("nameIs", (ctx, left, right) -> ctx.cb().conjunction());
    private static final FilterOperator<Boolean, Boolean> ACTIVE_IS =
            FilterOperator.of("activeIs", (ctx, left, right) -> ctx.cb().conjunction());
    private static final FilterOperator<String, Boolean> FLAGGED =
            FilterOperator.of("flagged", Boolean.class, (ctx, left, right) -> ctx.cb().conjunction());

    private static final Set<OperatorFeature> ALL = EnumSet.allOf(OperatorFeature.class);

    // ---- AC-FLT-24: the two checks, in order

    @Test
    void ac_flt_24_the_vendor_check_runs_first_and_its_false_is_mq4015_whatever_the_provider_answers() {
        RenderOptions options = RenderOptions.portable().withOperatorGate(OperatorGate.of("H2", Set.of(), ALL));
        assertThatThrownBy(() -> render(f -> f.op(MATCHES, NAME, "a.*"), options))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015))
                .hasMessageContaining("op(matches)").hasMessageContaining("REGEX_MATCH").hasMessageContaining("H2")
                .hasMessageContaining("OrderView.name");
        // Both checks failing still names the vendor's: it is the real reason the operator can't run.
        RenderOptions neither = RenderOptions.portable().withOperatorGate(OperatorGate.of("H2", Set.of(), Set.of()));
        assertThatThrownBy(() -> render(f -> f.op(MATCHES, NAME, "a.*"), neither))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015));
    }

    @Test
    void ac_flt_24_a_vendor_that_serves_the_feature_and_a_provider_that_does_not_is_mq4014() {
        RenderOptions options = RenderOptions.portable()
                .withOperatorGate(OperatorGate.of("POSTGRESQL", ALL, Set.of(OperatorFeature.ILIKE)));
        assertThatThrownBy(() -> render(f -> f.op(MATCHES, NAME, "a.*"), options))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                .hasMessageContaining("op(matches)").hasMessageContaining("REGEX_MATCH")
                .hasMessageContaining("ProviderSupport#operators");
        // The field form is gated alike.
        assertThatThrownBy(() -> render(f -> f.op(MATCHES, NAME, CODE), options))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
    }

    @Test
    void ac_flt_24_both_checks_passing_renders_the_operator_in_value_and_field_form() {
        RenderOptions options = RenderOptions.portable().withOperatorGate(OperatorGate.of("POSTGRESQL", ALL, ALL));
        assertThat(render(f -> f.op(MATCHES, NAME, "a.*"), options)).isPresent();
        assertThat(render(f -> f.op(MATCHES, NAME, CODE), options)).isPresent();
    }

    @Test
    void ac_flt_24_an_operator_that_requires_a_feature_is_mq4014_with_no_gate() {
        assertThatThrownBy(() -> render(f -> f.op(MATCHES, NAME, "a.*"), RenderOptions.portable()))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                .hasMessageContaining("no operator gate");
    }

    @Test
    void ac_flt_24_an_operator_that_requires_no_feature_is_never_gated() {
        RenderOptions closed = RenderOptions.portable().withOperatorGate(OperatorGate.of("MYSQL", Set.of(), Set.of()));
        FilterOperator<String, String> plain = FilterOperator.of("plain", (ctx, left, right) -> ctx.cb().conjunction());
        assertThat(render(f -> f.op(plain, NAME, "x"), closed)).isPresent();
    }

    @Test
    void ac_flt_24_the_gate_copies_its_sets_and_withRenderersOf_carries_the_gate_and_the_binder() {
        Set<OperatorFeature> vendor = EnumSet.of(OperatorFeature.ILIKE);
        OperatorGate gate = OperatorGate.of("H2", vendor, Set.of());
        vendor.add(OperatorFeature.FULL_TEXT);
        assertThat(gate.vendorServes(OperatorFeature.FULL_TEXT)).isFalse();
        assertThat(gate.vendorServes(OperatorFeature.ILIKE)).isTrue();
        assertThat(gate.providerServes(OperatorFeature.ILIKE)).isFalse();

        OperandBinder binder = new RecordingBinder();
        RenderOptions executor = RenderOptions.of(10, 20).withOperatorGate(gate).withOperandBinder(binder);
        RenderOptions scratch = RenderOptions.of(1, 2).withRenderersOf(executor);
        assertThat(scratch.operatorGate()).containsSame(gate);
        assertThat(scratch.operandBinder()).containsSame(binder);
        assertThat(scratch.maxInListSize()).isEqualTo(1);
        assertThat(RenderOptions.portable().operatorGate()).isEmpty();
        assertThat(RenderOptions.portable().operandBinder()).isEmpty();
    }

    // ---- AC-FLT-22's binder half: what the engine does with the provider's binder

    @Test
    void ac_flt_22_with_a_binder_an_enum_and_a_boolean_bind_through_it_typed_by_the_column() {
        RecordingBinder binder = new RecordingBinder();
        RenderOptions options = RenderOptions.portable().withOperandBinder(binder);
        JoinContext ctx = context(options);
        assertThat(FilterGroup.<OperatorGateTest.OrderView>collect(
                f -> f.op(STATUS_IS, STATUS, Status.CLOSED).op(ACTIVE_IS, ACTIVE, true))
                .stream().map(filter -> filter.toPredicate(ctx))).allMatch(Optional::isPresent);
        assertThat(binder.types).containsExactly(Status.class, Boolean.class);
        assertThat(binder.typedBy).hasSize(2).doesNotContainNull();
        assertThat(ctx.statementParameters()).containsOnlyKeys(binder.made)
                .containsValues(Status.CLOSED, true);
    }

    @Test
    void ac_flt_22_a_value_typed_operator_keeps_the_no_binder_rule_with_a_binder() {
        RecordingBinder binder = new RecordingBinder();
        RenderOptions options = RenderOptions.portable().withOperandBinder(binder);
        assertThatThrownBy(() -> render(f -> f.op(FLAGGED, NAME, true), options))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("op(flagged)");
        assertThat(binder.types).isEmpty();
    }

    @Test
    void ac_flt_22_a_binder_that_throws_or_returns_null_is_mq1502_and_an_engine_error_keeps_its_code() {
        IllegalStateException cause = new IllegalStateException("unexpected node type");
        OperandBinder throwing = new OperandBinder() {
            @Override
            public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
                throw cause;
            }
        };
        assertThatThrownBy(() -> render(f -> f.op(STATUS_IS, STATUS, Status.OPEN),
                RenderOptions.portable().withOperandBinder(throwing)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("op(statusIs)").hasMessageContaining("OrderView.status").hasCause(cause);
        OperandBinder none = new OperandBinder() {
            @Override
            public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
                return null;
            }
        };
        assertThatThrownBy(() -> render(f -> f.op(STATUS_IS, STATUS, Status.OPEN),
                RenderOptions.portable().withOperandBinder(none)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("returned no parameter");
        OperandBinder engine = new OperandBinder() {
            @Override
            public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
                throw new ModelQueryConfigurationException(MqCode.MQ4014, "engine");
            }
        };
        assertThatThrownBy(() -> render(f -> f.op(STATUS_IS, STATUS, Status.OPEN),
                RenderOptions.portable().withOperandBinder(engine)))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
    }

    @Test
    void ac_flt_22_a_binder_returning_a_parameter_the_statement_already_binds_is_mq1502() {
        // Cached per type, so the second op would rebind the first one's parameter to its own value (INV-5).
        CachingBinder caching = new CachingBinder();
        RenderOptions options = RenderOptions.portable().withOperandBinder(caching);
        assertThatThrownBy(() -> render(f -> f.or(g -> g.op(STATUS_IS, STATUS, Status.OPEN),
                g -> g.op(STATUS_IS, STATUS, Status.CLOSED)), options))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("op(statusIs)").hasMessageContaining("OrderView.status")
                .hasMessageContaining("new parameter on every call");
        // A sub-query rendered in a context of its own, whose binds the statement merges, is checked there too.
        List<Filter> open = FilterGroup.<OrderView>collect(f -> f.op(STATUS_IS, STATUS, Status.OPEN));
        JoinContext statement = context(options);
        JoinContext sub = context(options);
        open.get(0).toPredicate(statement);
        open.get(0).toPredicate(sub);
        assertThatThrownBy(() -> statement.addSubqueryBinds(sub))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502));
    }

    @Test
    void ac_flt_22_one_filter_rendered_twice_into_one_statement_binds_two_parameters_with_a_fresh_binder() {
        RecordingBinder binder = new RecordingBinder();
        JoinContext ctx = context(RenderOptions.portable().withOperandBinder(binder));
        List<Filter> open = FilterGroup.<OrderView>collect(f -> f.op(STATUS_IS, STATUS, Status.OPEN));
        open.get(0).toPredicate(ctx);
        open.get(0).toPredicate(ctx);
        assertThat(ctx.statementParameters()).hasSize(2).containsOnlyKeys(binder.made);
    }

    @Test
    void ac_flt_22_with_no_binder_an_enum_is_still_mq1502() {
        assertThatThrownBy(() -> render(f -> f.op(STATUS_IS, STATUS, Status.OPEN), RenderOptions.portable()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502));
    }

    @Test
    void ac_flt_22_a_binder_declining_the_column_expression_keeps_the_no_binder_rule() {
        RecordingBinder binder = new RecordingBinder(false);
        JoinContext ctx = context(RenderOptions.portable().withOperandBinder(binder));
        assertThat(FilterGroup.<OperatorGateTest.OrderView>collect(f -> f.op(NAME_IS, NAME, "Ann"))
                .stream().map(filter -> filter.toPredicate(ctx))).allMatch(Optional::isPresent);
        assertThat(ctx.statementParameters()).hasSize(1).containsValues("Ann");
        assertThatThrownBy(() -> render(f -> f.op(STATUS_IS, STATUS, Status.OPEN),
                RenderOptions.portable().withOperandBinder(binder)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("op(statusIs)")
                .hasMessageContaining("can't type it by this column's expression");
        assertThat(binder.typedBy).hasSize(2).doesNotContainNull();
        assertThat(binder.types).isEmpty();
        assertThat(binder.made).isEmpty();
    }

    // ---- AC-FLT-33, AC-FLT-34: an ignore-case value over a converted column

    @Test
    void ac_flt_33_over_a_converted_column_the_ignore_case_value_binds_through_the_binder_as_given() {
        RecordingBinder binder = new RecordingBinder(true, true);
        JoinContext ctx = context(RenderOptions.portable().withOperandBinder(binder));
        assertThat(FilterGroup.<OrderView>collect(f -> f.eqIgnoreCase(NAME, "Ann")
                        .likeIgnoreCase(CODE, "A_1", LikeMode.STARTS_WITH).likeIgnoreCase(CODE, "B2", LikeMode.EXACT))
                .stream().map(filter -> filter.toPredicate(ctx))).allMatch(Optional::isPresent);
        // The value, or mode's escaped pattern of it, unlowered: the converter runs first, then lower(?) folds it.
        assertThat(binder.types).containsExactly(String.class, String.class, String.class);
        assertThat(ctx.statementParameters()).containsOnlyKeys(binder.made);
        assertThat(ctx.statementParameters().values()).containsExactly("Ann", "A\\_1%", "B2");
    }

    @Test
    void ac_flt_34_without_a_binder_or_a_converting_mapping_no_ignore_case_value_is_a_binder_parameter() {
        RecordingBinder plain = new RecordingBinder(true, false);
        RecordingBinder declining = new RecordingBinder(false, true);
        for (RenderOptions options : List.of(RenderOptions.portable(),
                RenderOptions.portable().withOperandBinder(plain),
                RenderOptions.portable().withOperandBinder(declining))) {
            JoinContext ctx = context(options);
            assertThat(FilterGroup.<OrderView>collect(f -> f.eqIgnoreCase(NAME, "Ann")
                            .likeIgnoreCase(CODE, "A1", LikeMode.CONTAINS))
                    .stream().map(filter -> filter.toPredicate(ctx))).allMatch(Optional::isPresent);
            assertThat(ctx.statementParameters()).isEmpty();
        }
        assertThat(plain.converted).hasSize(2);
        // Declined, converts is never asked.
        assertThat(declining.converted).isEmpty();
        assertThat(plain.made).isEmpty();
        assertThat(declining.made).isEmpty();
    }

    @Test
    void ac_flt_33_a_converting_binder_that_throws_is_mq1502_naming_the_ignore_case_filter() {
        OperandBinder throwing = new OperandBinder() {
            @Override
            public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
                throw new IllegalStateException("no basic type");
            }

            @Override
            public boolean converts(Expression<?> typedBy) {
                return true;
            }
        };
        assertThatThrownBy(() -> render(f -> f.likeIgnoreCase(CODE, "a", LikeMode.EXACT),
                RenderOptions.portable().withOperandBinder(throwing)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                .hasMessageContaining("likeIgnoreCase cannot bind its value").hasMessageContaining("no basic type");
    }

    // ---- helpers: a stand-in CriteriaBuilder and root, since core has no provider

    /** A binder that records what it is asked for and makes a parameter of its own. */
    private static final class RecordingBinder implements OperandBinder {
        final List<Class<?>> types = new ArrayList<>();
        final List<Expression<?>> typedBy = new ArrayList<>();
        final List<ParameterExpression<?>> made = new ArrayList<>();
        final List<Expression<?>> converted = new ArrayList<>();
        private final boolean typesAll;
        private final boolean convertsAll;

        RecordingBinder() {
            this(true);
        }

        /** {@code typesAll} false: declines every expression, as for a column it can type only by its class. */
        RecordingBinder(boolean typesAll) {
            this(typesAll, false);
        }

        /** {@code convertsAll}: says every expression's mapping converts a value. */
        RecordingBinder(boolean typesAll, boolean convertsAll) {
            this.typesAll = typesAll;
            this.convertsAll = convertsAll;
        }

        @Override
        public boolean converts(Expression<?> typedBy) {
            converted.add(typedBy);
            return convertsAll;
        }

        @Override
        public boolean typesBy(Expression<?> typedBy) {
            if (!typesAll) {
                this.typedBy.add(typedBy);
            }
            return typesAll;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
            types.add(type);
            this.typedBy.add(typedBy);
            ParameterExpression<T> parameter = fake(ParameterExpression.class, type);
            made.add(parameter);
            return parameter;
        }
    }

    /** A binder that returns one parameter per type, which the engine refuses once a statement binds it twice. */
    private static final class CachingBinder implements OperandBinder {
        final Map<Class<?>, ParameterExpression<?>> made = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
            return (ParameterExpression<T>) made.computeIfAbsent(type, t -> fake(ParameterExpression.class, t));
        }
    }

    private static Optional<Predicate> render(UnaryOperator<Filters<OrderView>> filters, RenderOptions options) {
        List<Filter> recorded = FilterGroup.collect(filters);
        assertThat(recorded).hasSize(1);
        return recorded.get(0).toPredicate(context(options));
    }

    private static JoinContext context(RenderOptions options) {
        CriteriaBuilder cb = fake(CriteriaBuilder.class, Object.class);
        return JoinContext.of(fake(Root.class, Order.class), cb, null, options);
    }

    /** The type of a path to {@code args[0]}, an attribute of {@link Order}, or of a parameter of that class. */
    private static Class<?> javaType(Object[] args) {
        if (args == null || args.length == 0) {
            return Object.class;
        }
        if (args[0] instanceof Class<?> type) {
            return type;
        }
        return switch (String.valueOf(args[0])) {
            case "name", "code" -> String.class;
            case "status" -> Status.class;
            case "active" -> Boolean.class;
            default -> Object.class;
        };
    }

    /** A proxy whose interface-typed calls return more proxies, and whose {@code getJavaType} is {@code java}. */
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<?> type, Class<?> java) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getJavaType" -> java;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName();
                    default -> method.getReturnType().isInterface()
                            ? fake(method.getReturnType(), javaType(args))
                            : null;
                });
    }
}
