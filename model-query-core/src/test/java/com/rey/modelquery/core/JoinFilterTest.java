package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class JoinFilterTest {

    static final class Order {}
    static final class Customer {}
    static final class Source {}
    static final class View {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Customer> CUSTOMER = TableField.join(ROOT, "customer", JoinType.LEFT);
    private static final OrderedColumnField<View, Order, Long> ID =
            ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final OrderedColumnField<View, Customer, String> NAME =
            ColumnField.of(View.class, CUSTOMER, "name", String.class);
    private static final OrderedColumnField<Source, Order, Long> SOURCE_ID =
            ColumnField.of(Source.class, ROOT, "id", Long.class).named("id");
    private static final SourceQuery<Source> SOURCE = SourceQuery.builder(Source.class, ROOT,
            SelectSet.of(SOURCE_ID), PrimaryKey.of(SOURCE_ID)).build();
    private static final SourceJoin<View, Source> SOURCE_JOIN =
            SourceJoin.of("source", Source.class, JoinKind.LEFT, List.of(SourceJoin.on(ID, SOURCE_ID)));
    private static final EntityJoin<View, Customer> ENTITY = EntityJoin.of("customer", Customer.class, JoinKind.LEFT,
            List.of(EntityJoin.on(ColumnField.over(View.class, SOURCE_JOIN.root(), SOURCE_ID), "id")));
    private static final OrderedColumnField<View, Customer, String> ENTITY_NAME =
            ColumnField.of(View.class, ENTITY.root(), "name", String.class);

    private static ModelQuery.Builder<Order, Long, View> base() {
        return ModelQuery.builder(ROOT, row -> new View()).primaryKey(PrimaryKey.of(ID)).select(SelectSet.of(ID, NAME));
    }

    @Test
    void ac_col_54_operators_run_once_at_the_call_and_only_recorded_values_survive() {
        var calls = new AtomicInteger();
        var request = new ArrayList<>(List.of("first"));
        var bound = base().joinFilter(CUSTOMER, f -> {
            calls.incrementAndGet();
            return f.eq(NAME, request.get(0));
        }).joinSource(SOURCE_JOIN, SOURCE).joinEntity(ENTITY).joinFilter(ENTITY, f -> {
            calls.incrementAndGet();
            return f.eq(ENTITY_NAME, request.get(0));
        });
        assertThat(calls).hasValue(2);
        request.set(0, "later");
        var first = bound.build();
        var second = bound.build();
        assertThat(calls).hasValue(2);
        assertThat(first.conditions()).isEqualTo(second.conditions()).hasSameHashCodeAs(second.conditions());
        assertThat(first.conditions().joinFilters().keySet()).containsExactly(CUSTOMER, ENTITY.root());
        first.conditions().joinFilters().values().forEach(terms ->
                assertThat(terms.get(0).values()).containsExactly("first"));
        assertThat(first.conditions().toString()).doesNotContain("first");
    }

    @Test
    void ac_col_54_second_call_on_the_same_join_key_replaces_the_filter() {
        var original = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "first"));
        var equalKey = TableField.<Order, Customer>join(ROOT, "customer", JoinType.LEFT);
        var replaced = original.joinFilter(equalKey, f -> f.eq(NAME, "second")).build();
        assertThat(replaced.conditions().joinFilters()).hasSize(1);
        assertThat(replaced.conditions().joinFilters().get(CUSTOMER).get(0).values()).containsExactly("second");
        assertThat(original.build().conditions().joinFilters().get(CUSTOMER).get(0).values()).containsExactly("first");
        var entity = base().joinSource(SOURCE_JOIN, SOURCE).joinEntity(ENTITY)
                .joinFilter(ENTITY, f -> f.eq(ENTITY_NAME, "first"))
                .joinFilter(ENTITY, f -> f.eq(ENTITY_NAME, "second")).build();
        assertThat(entity.conditions().joinFilters().get(ENTITY.root()).get(0).values()).containsExactly("second");
    }

    @Test
    void ac_col_57_every_builder_copy_carries_filters_without_changing_shared_definitions() {
        var bound = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "bound"));
        List<UnaryOperator<ModelQuery.Builder<Order, Long, View>>> copies = List.of(
                b -> b.select(SelectSet.of(ID, NAME)),
                b -> b.fetch(FetchPlan.of(SelectSet.of(ID, NAME))),
                b -> b.forChildren(FetchPlan.of(SelectSet.of(ID, NAME)), List.of(), List.of()),
                b -> b.primaryKey(PrimaryKey.of(ID)),
                b -> b.orderBy(ID.asc()),
                b -> b.keyset(),
                b -> b.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(1_000L)),
                b -> b.lock(RowLock.shared()),
                b -> b.where(f -> f.eq(ID, 1L)),
                b -> b.afterMap((view, row) -> { }),
                b -> b.finisher(view -> view),
                b -> b.customize((spec, joins, query, cb, phase) -> { }),
                b -> b.groupBy(ID, NAME),
                b -> b.groupBy(SelectSet.of(ID, NAME)),
                b -> b.groupBy(ID, NAME).having(h -> h),
                b -> b.joinSource(SOURCE_JOIN, SOURCE),
                b -> b.joinSource(SOURCE_JOIN, SOURCE).joinEntity(ENTITY));
        var expected = bound.build().conditions().joinFilters();
        for (var copy : copies) {
            assertThat(copy.apply(bound).build().conditions().joinFilters()).isEqualTo(expected);
        }
        assertThat(bound.build().orderedBy(SortSpec.of(SortSpec.Key.asc("id"))).conditions().joinFilters())
                .isEqualTo(expected);
        assertThat(base().build().conditions().joinFilters()).isEmpty();
        assertThat(CUSTOMER).isEqualTo(TableField.join(ROOT, "customer", JoinType.LEFT));
        assertThat(ENTITY.on()).hasSize(1);
        assertThatThrownBy(expected::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> expected.get(CUSTOMER).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ac_col_55_static_on_and_request_filter_use_one_on_call_and_reuse_the_join() {
        var recording = new Recording();
        var table = CUSTOMER.as("active").on((join, cb) -> cb.conjunction());
        var name = ColumnField.of(View.class, table, "name", String.class);
        JoinContext ctx = recording.context();
        ctx.joinFilters(Map.of(table, FilterGroup.<View>collect(f -> f.eq(name, "Ann"))));
        assertThat(recording.joins).hasValue(0);
        table.resolve(ctx);
        name.path(ctx);
        assertThat(recording.joins).hasValue(1);
        assertThat(recording.on).containsExactly("and(conjunction(),equal(root.customer.name,Ann))");
        var withoutStatic = new Recording();
        JoinContext plain = withoutStatic.context();
        plain.joinFilters(Map.of(CUSTOMER, FilterGroup.<View>collect(f -> f.eq(NAME, "Ann"))));
        CUSTOMER.resolve(plain);
        assertThat(withoutStatic.on).containsExactly("equal(root.customer.name,Ann)");
    }

    @Test
    void ac_col_55_skipped_filters_add_no_on_predicate_and_replace_previous_terms() {
        var skipped = base().joinFilter(CUSTOMER, f -> f.eq(NAME, "first"))
                .joinFilter(CUSTOMER, f -> f.eq(NAME, Optional.empty()).when(false, g -> g.eq(NAME, "unused")));
        // An all-skipped filter adds nothing, so it is not in force: out of inspection, equal to no call (R-COL-39).
        assertThat(skipped.build().conditions().joinFilters()).isEmpty();
        assertThat(skipped.build().conditions()).isEqualTo(base().build().conditions());
        assertThat(base().joinFilter(CUSTOMER, f -> f.eq(NAME, Optional.empty())).build().conditions().joinFilters())
                .doesNotContainKey(CUSTOMER);
        var recording = new Recording();
        JoinContext ctx = recording.context();
        ctx.joinFilters(Map.of(CUSTOMER, FilterGroup.<View>collect(f -> f.eq(NAME, Optional.empty()))));
        CUSTOMER.resolve(ctx);
        assertThat(recording.on).isEmpty();
        var table = CUSTOMER.as("static").on((join, cb) -> cb.conjunction());
        ctx.joinFilters(Map.of(table, List.of()));
        table.resolve(ctx);
        assertThat(recording.on).containsExactly("conjunction()");
    }

    @Test
    void ac_col_56_another_key_resolving_to_the_filtered_join_is_refused_in_either_order() {
        var inner = TableField.<Order, Customer>join(ROOT, "customer", JoinType.INNER);
        Map<TableField<?, ?>, List<Filter>> filters =
                Map.of(CUSTOMER, FilterGroup.<View>collect(f -> f.eq(NAME, "Ann")));
        // Inside or(...) the INNER key resolves to the filtered LEFT join (R-FLT-10), whichever reads it first.
        var innerFirst = new Recording().context();
        innerFirst.joinFilters(filters);
        assertMq1912(() -> innerFirst.leftJoining(() -> inner.resolve(innerFirst)));
        var filteredFirst = new Recording().context();
        filteredFirst.joinFilters(filters);
        CUSTOMER.resolve(filteredFirst);
        assertMq1912(() -> filteredFirst.leftJoining(() -> inner.resolve(filteredFirst)));
        // Outside or(...) the INNER key is a join of its own, which the filter does not touch.
        var alone = new Recording().context();
        alone.joinFilters(filters);
        inner.resolve(alone);
        CUSTOMER.resolve(alone);
        alone.checkJoinFilters();
    }

    @Test
    void ac_col_56_null_values_fail_with_mq1301_at_the_join_filter_call() {
        assertMq1301(() -> base().joinFilter(CUSTOMER, f -> f.eq(NAME, (String) null)));
        assertMq1301(() -> base().joinFilter(ENTITY, f -> f.eq(ENTITY_NAME, (String) null)));
    }

    @Test
    void ac_col_57_request_filters_are_not_allowed_on_a_persist_returning_definition() {
        var query = base().select(SelectSet.of(ID)).joinFilter(CUSTOMER, f -> f.eq(NAME, "Ann")).build();
        assertThatThrownBy(() -> query.checkReturning(new Recording().context().cb()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
                    assertThat(e.code()).isEqualTo(MqCode.MQ1809);
                    assertThat(e).hasMessageContaining("joinFilter(...)");
                });
    }

    @Test
    void ac_col_54_entity_key_and_filter_reach_the_renderer_as_one_predicate() throws ReflectiveOperationException {
        var recording = new Recording();
        JoinContext ctx = recording.context();
        ctx.joinSource(SOURCE_JOIN.root().key(), recording.from(From.class, "source", Source.class),
                ctx.root(), false, "id");
        ctx.joinFilters(Map.of(ENTITY.root(), FilterGroup.<View>collect(f -> f.eq(ENTITY_NAME, "Ann"))));
        var query = base().joinSource(SOURCE_JOIN, SOURCE).joinEntity(ENTITY)
                .joinFilter(ENTITY, f -> f.eq(ENTITY_NAME, "Ann")).build();
        var rendererCalls = new AtomicInteger();
        EntityJoinRenderer renderer = new EntityJoinRenderer() {
            @Override
            @SuppressWarnings("unchecked")
            public <E> From<?, E> join(From<?, ?> root, Class<E> entity, JoinKind kind,
                    Function<From<?, E>, Predicate> on) {
                rendererCalls.incrementAndGet();
                From<?, E> joined = recording.from(From.class, "entity", Customer.class);
                recording.on.add(on.apply(joined).toString());
                return joined;
            }
        };
        var method = ModelQuery.class.getDeclaredMethod("joinEntity", EntityJoin.class, EntityJoinRenderer.class,
                From.class, JoinContext.class);
        method.setAccessible(true);
        method.invoke(query, ENTITY, renderer, ctx.root(), ctx);
        assertThat(rendererCalls).hasValue(1);
        assertThat(recording.on).containsExactly("and(equal(entity.id,source.mq_id),equal(entity.name,Ann))");
        assertThat(ENTITY_NAME.path(ctx).toString()).isEqualTo("entity.name");
    }

    private static void assertMq1912(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ModelQueryDefinitionException.class).hasMessageContaining("MQ1912");
    }

    private static void assertMq1301(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301));
    }

    /** Records public Criteria calls without a provider, as the other core rendering tests do. */
    private static final class Recording {
        private final AtomicInteger joins = new AtomicInteger();
        private final List<String> on = new ArrayList<>();

        JoinContext context() {
            CriteriaBuilder cb = node(CriteriaBuilder.class, "", Object.class);
            return JoinContext.of(from(Root.class, "root", Order.class), cb);
        }

        <T> T from(Class<?> api, String text, Class<?> javaType) {
            return node(api, text, javaType);
        }

        @SuppressWarnings("unchecked")
        private <T> T node(Class<?> api, String text, Class<?> javaType) {
            return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "toString" -> text;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "getJavaType" -> javaType;
                    case "getAttribute" -> null;
                    case "getModel" -> javaType == Customer.class ? customerType() : null;
                    case "get" -> node(Path.class, text + "." + args[0],
                            args[0].equals("id") ? Long.class : String.class);
                    case "join" -> {
                        joins.incrementAndGet();
                        yield from(Join.class, text + "." + args[0], Customer.class);
                    }
                    case "on" -> {
                        on.add(render(args));
                        yield proxy;
                    }
                    case "equal", "and", "conjunction" ->
                            node(Predicate.class, method.getName() + "(" + render(args) + ")", Boolean.class);
                    default -> throw new AssertionError(method.getName());
                };
            });
        }

        private static String render(Object[] args) {
            if (args == null) {
                return "";
            }
            return String.join(",", Arrays.stream(args)
                    .map(arg -> arg instanceof Object[] array ? render(array) : String.valueOf(arg)).toList());
        }

        private static EntityType<?> customerType() {
            Type<?> basic = meta(Type.class, (method, args) -> Type.PersistenceType.BASIC);
            SingularAttribute<?, ?> id = meta(SingularAttribute.class, (method, args) -> switch (method) {
                case "isId" -> true;
                case "isAssociation" -> false;
                case "getName" -> "id";
                case "getJavaType" -> Long.class;
                case "getType" -> basic;
                default -> throw new AssertionError(method);
            });
            return meta(EntityType.class, (method, args) -> switch (method) {
                case "getSingularAttributes" -> Set.of(id);
                case "getAttribute" -> null;
                case "getJavaType" -> Customer.class;
                default -> throw new AssertionError(method);
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T meta(Class<?> api, java.util.function.BiFunction<String, Object[], Object> answer) {
            return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api},
                    (proxy, method, args) -> answer.apply(method.getName(), args));
        }
    }
}
