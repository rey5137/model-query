package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@code Expr.typeOf}, {@code ofType} and {@code notOfType} in core: their sites, refusals and rendering over a
 * recording Criteria fake (R-COL-36, R-FLT-28).
 */
class TypeOfTest {

    abstract static class Payment {}
    static class Card extends Payment {}
    static class Premium extends Card {}
    static class Bank extends Payment {}
    /** A subtype the metamodel doesn't map. */
    static class Cash extends Payment {}
    /** A mapped entity outside the hierarchy. */
    static class Unrelated {}
    static class View {}

    private static final TableField<Payment, Payment> ROOT = TableField.root(Payment.class);
    private static final TableField<Payment, Payment> PEER = TableField.join(ROOT, "peer", JoinType.LEFT);
    private static final TableField<Payment, Payment> PARENT = TableField.join(ROOT, "parent", JoinType.INNER);
    private static final ColumnField<View, Payment, Long> ID = ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final ExpressionField<View, Class<? extends Payment>> TYPE = Expr.typeOf(View.class, ROOT);
    private static final Metamodel METAMODEL =
            metamodel(Payment.class, Card.class, Premium.class, Bank.class, Unrelated.class);

    // ---- sites (R-VND-26)

    @Test
    void ac_col_49_type_of_reads_one_type_site_per_use_with_no_attribute() {
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, TYPE))
                .orderBy(TYPE.asc()).where(f -> f.eq(TYPE, Card.class).in(TYPE, List.of(Bank.class))).build();
        assertThat(sites(query, StatementShape.CONTENT)).containsExactly(
                site(StatementShape.CONTENT, true, TreatUse.SELECT, TreatUse.TYPE),
                site(StatementShape.CONTENT, true, TreatUse.ORDER, TreatUse.TYPE),
                site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.TYPE));
        // A count drops the selection and the order, and keeps the predicates.
        assertThat(sites(query, StatementShape.COUNT)).containsExactly(
                site(StatementShape.COUNT, true, TreatUse.PREDICATE, TreatUse.TYPE));
    }

    @Test
    void ac_col_49_a_type_read_makes_no_treated_statement() {
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, TYPE))
                .orderBy(TYPE.asc()).where(f -> f.ofType(ROOT, Card.class).notOfType(PEER, Bank.class)).build();
        // No root-id count and no mq_0id anchor: typeOf and ofType alone don't make one (R-COL-35).
        assertThat(TreatChecks.treated(query)).isFalse();
    }

    @Test
    void ac_col_49_a_grouped_type_key_is_one_unanchored_site_the_engine_admits() {
        var grouped = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(TYPE, Agg.count(ID)))
                .groupBy(TYPE).build();
        assertThat(sites(grouped, StatementShape.CONTENT)).containsExactly(
                site(StatementShape.GROUPED, false, TreatUse.SELECT, TreatUse.GROUP_BY, TreatUse.TYPE));
        // The count of its groups is a GROUPED_COUNT statement, unanchored too (D-159).
        assertThat(sites(grouped, StatementShape.COUNT)).containsExactly(
                site(StatementShape.GROUPED_COUNT, false, TreatUse.SELECT, TreatUse.GROUP_BY, TreatUse.TYPE));
        // A type read is per row: selected outside the group keys it is MQ1401, as a column is.
        assertCode(MqCode.MQ1401, () -> ModelQuery.builder(ROOT, row -> new View())
                .select(SelectSet.of(TYPE, Agg.count(ID))).groupBy(ID).build());
    }

    @Test
    void ac_col_49_type_of_over_a_left_joined_node_is_mq4016_and_not_of_type_over_it_is_admitted() {
        var peerType = Expr.typeOf(View.class, PEER);
        var selected = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, peerType)).build();
        assertThatThrownBy(() -> sites(selected, StatementShape.CONTENT))
                .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining("typeOf(").hasMessageContaining("LEFT_JOIN")
                .hasMessageContaining("absent")
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                .isEqualTo(MqCode.MQ4016);
        var negated = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.notOfType(PEER, Card.class)).build();
        assertThat(sites(negated, StatementShape.CONTENT)).containsExactly(site(StatementShape.CONTENT, true,
                TreatUse.PREDICATE, TreatUse.NEGATED, TreatUse.NULL_TEST, TreatUse.DISJUNCTION, TreatUse.LEFT_JOIN,
                TreatUse.TYPE));
        var inner = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.notOfType(PARENT, Card.class)).build();
        assertThat(sites(inner, StatementShape.CONTENT)).containsExactly(
                site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.INNER_JOIN, TreatUse.TYPE));
    }

    @Test
    void ac_col_49_a_type_read_over_a_treated_node_is_mq4016_before_any_statement() {
        var treatedRoot = ROOT.treat(Card.class);
        var downcast = PARENT.treat(Card.class);
        for (TableField<?, Card> treated : List.of(treatedRoot, downcast)) {
            var selected = ModelQuery.builder(ROOT, row -> new View())
                    .select(SelectSet.of(ID, Expr.typeOf(View.class, treated))).build();
            var filtered = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.ofType(treated, Premium.class)).build();
            var negated = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.notOfType(treated, Premium.class)).build();
            for (var query : List.of(selected, filtered, negated)) {
                assertCode(MqCode.MQ4016, () -> sites(query, StatementShape.CONTENT),
                        "typeOf(" + treated.describe() + ")", "treated node");
            }
        }
    }

    @Test
    void ac_flt_30_of_type_and_not_of_type_are_type_predicates_and_an_empty_set_reads_no_type() {
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.ofType(ROOT, Card.class, Bank.class).notOfType(ROOT, List.of(Premium.class))).build();
        assertThat(sites(query, StatementShape.CONTENT))
                .containsExactly(site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.TYPE));
        var empty = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.ofType(ROOT, List.of()).notOfType(ROOT, List.of())).build();
        assertThat(sites(empty, StatementShape.CONTENT)).isEmpty();
        // Nor does it need a renderer.
        TreatChecks.check(empty);
    }

    @Test
    void ac_vnd_16_without_a_renderer_every_type_read_is_mq4014() {
        var selected = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, TYPE)).build();
        var filtered = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.ofType(ROOT, Card.class)).build();
        var negated = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.notOfType(ROOT, Optional.of(List.of(Card.class)))).build();
        for (Object definition : List.of(selected, filtered, negated)) {
            assertThatThrownBy(() -> TreatChecks.check(definition))
                    .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                    .hasMessageContaining("TreatRenderer")
                    .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                    .isEqualTo(MqCode.MQ4014);
        }
        // At rendering too, for a build no check preceded.
        assertCode(MqCode.MQ4014, () -> TYPE.expression(context(RenderOptions.portable())));
        assertCode(MqCode.MQ4014, () -> render(RenderOptions.portable(), f -> f.ofType(ROOT, Card.class)));
        // A renderer but no metamodel from the executor: the subtypes cannot be listed.
        assertCode(MqCode.MQ4014, () -> render(RenderOptions.portable().withTreatRenderer(new Renderer()),
                f -> f.ofType(ROOT, Card.class)));
    }

    @Test
    void ac_flt_30_an_unmapped_or_foreign_type_is_mq1106_before_any_statement_and_at_rendering() {
        var unmapped = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.ofType(ROOT, Cash.class)).build();
        assertCode(MqCode.MQ1106, () -> sites(unmapped, StatementShape.CONTENT), "is not a mapped entity");
        assertCode(MqCode.MQ1106, () -> render(f -> f.ofType(ROOT, Cash.class)), "is not a mapped entity");
        @SuppressWarnings({"unchecked", "rawtypes"})
        UnaryOperator<Filters<View>> foreign = f -> ((Filters) f).notOfType(ROOT, Unrelated.class);
        var outside = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID)).where(foreign).build();
        assertCode(MqCode.MQ1106, () -> sites(outside, StatementShape.CONTENT), "is not Payment or a subtype");
        assertCode(MqCode.MQ1106, () -> render(foreign), "is not Payment or a subtype");
    }

    @Test
    void ac_col_49_definition_refusals() {
        // keyset() orders by attribute values only (R-QRY-16).
        assertCode(MqCode.MQ1208, () -> ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .primaryKey(PrimaryKey.of(ID)).orderBy(TYPE.asc()).keyset().build());
        assertCode(MqCode.MQ1106, () -> Expr.typeOf(View.class, TableField.source(View.class)));
        assertCode(MqCode.MQ1106, () -> FilterGroup.<View>collect(f -> f.ofType(TableField.source(View.class),
                List.of(View.class))));
        assertCode(MqCode.MQ1301, () -> FilterGroup.<View>collect(f -> f.ofType(ROOT, Card.class, (Class<Card>) null)));
        assertCode(MqCode.MQ1301, () -> FilterGroup.<View>collect(f -> f.ofType(ROOT, (Class<Card>) null)));
        assertCode(MqCode.MQ1301, () -> FilterGroup.<View>collect(f -> f.notOfType(ROOT,
                (List<Class<? extends Payment>>) null)));
        assertThatThrownBy(() -> FilterGroup.<View>collect(f -> f.ofType(ROOT,
                (Optional<List<Class<? extends Payment>>>) null))).isExactlyInstanceOf(NullPointerException.class);
        // Inside exists(path, ...) the node must sit on the path, as a column must.
        assertCode(MqCode.MQ1302, () -> FilterGroup.<View>collect(f -> f.exists(PEER, e -> e.ofType(ROOT,
                Card.class))));
    }

    @Test
    void ac_flt_30_conditions_record_the_types_as_passed_and_an_empty_optional_records_nothing() {
        var conditions = ConditionGroup.conditions(FilterGroup.<View>collect(f -> f.ofType(ROOT, Card.class, Bank.class)
                .notOfType(PEER, Optional.of(List.of(Premium.class))).ofType(ROOT, Optional.empty())));
        assertThat(conditions).extracting(Condition::kind)
                .containsExactly(Condition.Kind.OF_TYPE, Condition.Kind.NOT_OF_TYPE);
        assertThat(conditions.get(0).values()).containsExactly(Card.class, Bank.class);
        assertThat(conditions.get(0).column()).contains(TYPE);
        assertThat(conditions.get(1).values()).containsExactly(Premium.class);
        assertThat(conditions.get(1).column()).contains(Expr.typeOf(View.class, PEER));
        assertThat(TYPE.columns()).isEmpty();
        assertThat(TYPE.type()).isEqualTo(Class.class);
    }

    // ---- rendering (R-FLT-28, D-158)

    @Test
    void ac_flt_30_of_type_renders_the_concrete_mapped_subtypes_in_name_order_and_declares_its_restriction() {
        var ctx = context(options());
        assertThat(render(ctx, f -> f.ofType(ROOT, Card.class)))
                .containsExactly("root.type().in(literal(Card), literal(Premium))");
        // An abstract type keeps its concrete subtypes, which a grandchild is.
        assertThat(render(ctx, f -> f.ofType(ROOT, Payment.class)))
                .containsExactly("root.type().in(literal(Bank), literal(Card), literal(Premium))");
        assertThat(ctx.expectedNarrowings()).containsExactly(
                new ExpectedNarrowing(ExpectedNarrowing.Kind.TYPE_RESTRICTION, Payment.class,
                        Set.of(Card.class, Premium.class)),
                new ExpectedNarrowing(ExpectedNarrowing.Kind.TYPE_RESTRICTION, Payment.class,
                        Set.of(Bank.class, Card.class, Premium.class)));
    }

    @Test
    void ac_flt_30_an_of_type_over_a_joined_node_of_the_root_entity_declares_no_restriction() {
        var ctx = context(options());
        // A self-join reaches the root's entity, but only the statement's untreated root declares (D-158).
        assertThat(render(ctx, f -> f.ofType(PARENT, Card.class)))
                .containsExactly("root.join(parent, INNER).type().in(literal(Card), literal(Premium))");
        assertThat(ctx.expectedNarrowings()).isEmpty();
        render(ctx, f -> f.ofType(ROOT, Bank.class));
        assertThat(ctx.expectedNarrowings()).containsExactly(new ExpectedNarrowing(
                ExpectedNarrowing.Kind.TYPE_RESTRICTION, Payment.class, Set.of(Bank.class)));
    }

    @Test
    void ac_flt_30_an_of_type_under_or_not_or_case_declares_no_restriction() {
        var ctx = context(options());
        render(ctx, f -> f.or(a -> a.ofType(ROOT, Card.class), b -> b.ofType(ROOT, Bank.class)));
        render(ctx, f -> f.not(n -> n.ofType(ROOT, Card.class)));
        render(ctx, f -> f.notOfType(ROOT, Card.class));
        Expr.cases(View.class, String.class).when(w -> w.ofType(ROOT, Card.class), "card").otherwise("other")
                .expression(ctx);
        assertThat(ctx.expectedNarrowings()).isEmpty();
    }

    @Test
    void ac_flt_31_not_of_type_keeps_an_absent_left_join_and_an_empty_set_keeps_every_row() {
        assertThat(render(f -> f.notOfType(ROOT, Card.class)))
                .containsExactly("not(root.type().in(literal(Card), literal(Premium)))");
        // No OR: Hibernate 7.4.14 prunes a LEFT join's subtype joins for a type read under one (D-203).
        assertThat(render(f -> f.notOfType(PEER, Card.class))).containsExactly(
                "equal(selectCase().when(root.join(peer, LEFT).type().in(literal(Card), literal(Premium)), 1)"
                        + ".otherwise(0), 0)");
        // An empty collection holds for every row; for ofType it is FALSE, and neither reads a type.
        assertThat(render(RenderOptions.portable(), f -> f.notOfType(ROOT, List.of()))).isEmpty();
        assertThat(render(RenderOptions.portable(), f -> f.ofType(ROOT, List.of()))).containsExactly("disjunction()");
        assertThat(render(RenderOptions.portable(), f -> f.ofType(ROOT, Optional.empty()))).isEmpty();
    }

    @Test
    void ac_col_49_type_of_renders_the_node_type() {
        assertThat(TYPE.expression(context(options()))).hasToString("root.type()");
        assertThat(TYPE.name()).isEqualTo("typeOf(" + ROOT.describe() + ")");
        assertThat(TYPE).isEqualTo(Expr.typeOf(View.class, ROOT)).isNotEqualTo(Expr.typeOf(View.class, PEER));
    }

    @Test
    void ac_vnd_21_render_options_keep_the_metamodel_in_every_copy() {
        RenderOptions options = RenderOptions.of(31, 97).withMetamodel(METAMODEL);
        assertThat(RenderOptions.portable().metamodel()).isEmpty();
        assertThat(options.withTreatRenderer(new Renderer()).withSubqueryRenderer(stub(SubqueryRenderer.class))
                .withSourceRenderer(stub(SourceRenderer.class)).withSequenceRenderer(stub(SequenceRenderer.class))
                .withConstantRenderer(stub(ConstantRenderer.class))
                .withNullPrecedenceRenderer(stub(NullPrecedenceRenderer.class)).metamodel()).containsSame(METAMODEL);
        assertThatThrownBy(() -> options.withMetamodel(null)).isExactlyInstanceOf(NullPointerException.class);
    }

    @Test
    void ac_vnd_21_a_scratch_copy_takes_every_renderer_and_the_metamodel_under_its_own_limits() {
        TreatRenderer treats = new Renderer();
        SequenceRenderer standIn = stub(SequenceRenderer.class);
        SubqueryRenderer subqueries = stub(SubqueryRenderer.class);
        RenderOptions scratch = RenderOptions.of(5, 7).withSequenceRenderer(standIn).withRenderersOf(
                RenderOptions.of(31, 97).withTreatRenderer(treats).withSubqueryRenderer(subqueries)
                        .withMetamodel(METAMODEL));
        assertThat(scratch.maxInListSize()).isEqualTo(5);
        assertThat(scratch.maxBindParameters()).isEqualTo(7);
        assertThat(scratch.treatRenderer()).containsSame(treats);
        assertThat(scratch.subqueryRenderer()).containsSame(subqueries);
        assertThat(scratch.metamodel()).containsSame(METAMODEL);
        // One the copied options lack keeps the scratch's own, as checkPhases' stand-in sequence renderer.
        assertThat(scratch.sequenceRenderer()).containsSame(standIn);
        assertThat(scratch.sourceRenderer()).isEmpty();
    }

    // ---- helpers

    private static List<TreatSite> sites(ModelQuery<?, ?, ?> query, StatementShape shape) {
        return TreatChecks.check(query, RenderOptions.portable().withTreatRenderer(new Renderer()), METAMODEL, shape,
                false, true);
    }

    private static TreatSite site(StatementShape shape, boolean anchored, TreatUse... uses) {
        return new TreatSite(Payment.class, Payment.class, Optional.empty(), shape, anchored, Set.of(uses));
    }

    private static RenderOptions options() {
        return RenderOptions.portable().withTreatRenderer(new Renderer()).withMetamodel(METAMODEL);
    }

    private static List<String> render(UnaryOperator<Filters<View>> filters) {
        return render(options(), filters);
    }

    private static List<String> render(RenderOptions options, UnaryOperator<Filters<View>> filters) {
        return render(context(options), filters);
    }

    private static List<String> render(JoinContext ctx, UnaryOperator<Filters<View>> filters) {
        return ConditionGroup.toPredicates(FilterGroup.collect(filters), ctx).stream().map(Object::toString)
                .toList();
    }

    private static JoinContext context(RenderOptions options) {
        Root<?> root = (Root<?>) fake(Root.class, "root");
        CriteriaBuilder cb = (CriteriaBuilder) fake(CriteriaBuilder.class, "");
        Object criteria = fake(CriteriaQuery.class, "query");
        // The statement's roots are the fixture's root, which the ofType declaration checks (D-158).
        CriteriaQuery<?> query = (CriteriaQuery<?>) Proxy.newProxyInstance(CriteriaQuery.class.getClassLoader(),
                new Class<?>[] {CriteriaQuery.class}, (proxy, method, args) -> method.getName().equals("getRoots")
                        ? Set.of(root) : method.invoke(criteria, args));
        return JoinContext.of(root, cb, query, options);
    }

    private static void assertCode(MqCode code, Executable call, String... messages) {
        assertThatThrownBy(call::execute).isInstanceOf(ModelQueryException.class)
                .satisfies(failure -> {
                    for (String message : messages) {
                        assertThat(failure.getMessage()).contains(message);
                    }
                })
                .extracting(failure -> ((ModelQueryException) failure).code()).isEqualTo(code);
    }

    /**
     * A Criteria node whose every interface-typed call returns another node named after the call, so a rendering
     * reads back as text; every node's Java type is {@code Payment}, the fixture's only entity path.
     */
    private static Object fake(Class<?> type, String text) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "toString" -> text;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    // A CASE of the fixture is a String; every other node is a Payment path.
                    case "getJavaType" -> text.contains("selectCase") ? String.class : Payment.class;
                    case "getAttribute", "getModel" -> null;
                    default -> {
                        String call = method.getName() + "(" + text(args) + ")";
                        Class<?> returned = method.getReturnType();
                        if (!returned.isInterface()) {
                            throw new AssertionError("unexpected " + call);
                        }
                        yield fake(returned, text.isEmpty() ? call : text + "." + call);
                    }
                });
    }

    private static String text(Object[] args) {
        if (args == null) {
            return "";
        }
        var parts = new ArrayList<String>();
        for (Object arg : args) {
            if (arg instanceof Object[] array) {
                parts.add(text(array));
            } else if (arg instanceof Class<?> type) {
                parts.add(type.getSimpleName());
            } else {
                parts.add(String.valueOf(arg));
            }
        }
        return String.join(", ", parts);
    }

    private static Metamodel metamodel(Class<?>... entities) {
        Set<EntityType<?>> types = Arrays.stream(entities).map(TypeOfTest::entity)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return stub(Metamodel.class, (method, args) -> switch (method) {
            case "getEntities" -> types;
            case "managedType", "entity" -> types.stream().filter(type -> type.getJavaType() == args[0])
                    .findFirst().orElseThrow();
            default -> throw new AssertionError(method);
        });
    }

    private static EntityType<?> entity(Class<?> javaType) {
        // Every association of the fixture, peer and parent, reaches a Payment.
        SingularAttribute<?, ?> association = stub(SingularAttribute.class, (method, args) -> switch (method) {
            case "getType" -> stub(Type.class, (m, a) -> Payment.class);
            default -> throw new AssertionError(method);
        });
        // A single basic id, which a statement with a treated node selects and counts (R-COL-35).
        SingularAttribute<?, ?> id = stub(SingularAttribute.class, (method, args) -> switch (method) {
            case "isId" -> true;
            case "getName" -> "id";
            case "getType" -> stub(Type.class, (m, a) -> Type.PersistenceType.BASIC);
            default -> throw new AssertionError(method);
        });
        return stub(EntityType.class, (method, args) -> switch (method) {
            case "getJavaType" -> javaType;
            case "getAttribute" -> association;
            case "hasSingleIdAttribute" -> true;
            case "getSingularAttributes" -> Set.of(id);
            case "toString" -> javaType.getSimpleName();
            case "hashCode" -> javaType.hashCode();
            case "equals" -> false;
            default -> throw new AssertionError(method);
        });
    }

    private interface Answer {
        Object answer(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<?> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> answer.answer(method.getName(), args));
    }

    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> { throw new AssertionError(method.getName()); }));
    }

    private static class Renderer implements TreatRenderer {
        @Override
        public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
            throw new AssertionError("a type read renders no treat");
        }

        @Override public Optional<String> refusal(TreatSite site) { return Optional.empty(); }

        @Override
        public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape,
                List<ExpectedNarrowing> expected) {
            return Optional.empty();
        }
    }
}
