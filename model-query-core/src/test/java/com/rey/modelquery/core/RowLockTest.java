package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.RowLock.Strength;
import com.rey.modelquery.core.RowLock.Wait;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC-QRY-16: the {@link RowLock} value, the builder's {@code lock(...)}, {@code withLock}, and the two definition
 * refusals a lock adds, {@code MQ1209} and {@code MQ1205} (spec api/11 R-QRY-17, R-QRY-11; D-127).
 */
class RowLockTest {

    static final class Order {}

    static final class OrderView {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, Order, String> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", String.class);
    private static final RowMapper<OrderView> MAPPER = row -> new OrderView();

    private static ModelQuery.Builder<Order, Long, OrderView> keyed() {
        return ModelQuery.builder(ROOT, MAPPER).select(SelectSet.of(ID)).primaryKey(PrimaryKey.of(ID));
    }

    // ---- the value

    @Test
    void ac_qry_16_the_factories_produce_equal_locks() {
        assertThat(RowLock.exclusive()).isEqualTo(RowLock.exclusive(Wait.WAIT))
                .hasSameHashCodeAs(RowLock.exclusive(Wait.WAIT));
        assertThat(RowLock.shared()).isEqualTo(RowLock.shared(Wait.WAIT));
        assertThat(RowLock.exclusive()).isNotEqualTo(RowLock.shared());
        assertThat(RowLock.exclusive(Wait.NO_WAIT)).isNotEqualTo(RowLock.exclusive(Wait.SKIP_LOCKED));
        assertThat(RowLock.exclusive().strength()).isEqualTo(Strength.EXCLUSIVE);
        assertThat(RowLock.shared(Wait.SKIP_LOCKED).waitPolicy()).isEqualTo(Wait.SKIP_LOCKED);
        assertThat(RowLock.exclusive(Wait.SKIP_LOCKED)).hasToString("RowLock.exclusive(SKIP_LOCKED)");
    }

    @Test
    void ac_qry_16_a_null_wait_is_refused() {
        assertThatThrownBy(() -> RowLock.exclusive(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RowLock.shared(null)).isInstanceOf(NullPointerException.class);
    }

    // ---- the builder and the copies

    @Test
    void ac_qry_16_builder_lock_shows_in_lock() {
        assertThat(keyed().build().lock()).isEmpty();
        assertThat(keyed().lock(RowLock.shared(Wait.NO_WAIT)).build().lock())
                .contains(RowLock.shared(Wait.NO_WAIT));
    }

    @Test
    void ac_qry_16_with_lock_refuses_null_and_returns_this_for_an_equal_lock() {
        var query = keyed().lock(RowLock.exclusive()).build();
        assertThatThrownBy(() -> query.withLock(null)).isInstanceOf(NullPointerException.class);
        assertThat(query.withLock(RowLock.exclusive())).isSameAs(query);
        var other = query.withLock(RowLock.exclusive(Wait.SKIP_LOCKED));
        assertThat(other).isNotSameAs(query);
        assertThat(other.lock()).contains(RowLock.exclusive(Wait.SKIP_LOCKED));
    }

    @Test
    void ac_qry_16_with_lock_returns_the_same_copy_for_a_constant_lock() {
        var query = keyed().build();
        var lock = RowLock.exclusive(Wait.NO_WAIT);
        var copy = query.withLock(lock);
        assertThat(query.withLock(lock)).isSameAs(copy);
        assertThat(query.withLock(RowLock.exclusive(Wait.NO_WAIT))).isSameAs(copy);
        assertThat(query.withLock(RowLock.shared())).isNotSameAs(copy);
        assertThat(copy.definition()).isSameAs(copy);
    }

    @Test
    void ac_qry_16_every_copy_keeps_the_lock() {
        var lock = RowLock.exclusive();
        var locked = keyed().lock(lock).build();
        var sort = SortSpec.of(SortSpec.Key.asc("id"));
        var plan = FetchPlan.of(SelectSet.of(ID));
        assertThat(locked.orderedBy(sort).lock()).contains(lock);
        assertThat(locked.withFetch(plan).lock()).contains(lock);
        assertThat(locked.withFetch(plan).orderedBy(sort).lock()).contains(lock);
        assertThat(locked.orderedBy(sort).withFetch(plan).lock()).contains(lock);
        // A lock set first survives every builder call after it.
        var builtAfter = ModelQuery.builder(ROOT, MAPPER).lock(lock).select(SelectSet.of(ID, STATUS))
                .primaryKey(PrimaryKey.of(ID)).orderBy(STATUS.asc()).keyset().where(f -> f)
                .afterMap((model, row) -> { }).finisher(m -> m).customize((spec, joins, query, cb, phase) -> { })
                .fetch(plan).build();
        assertThat(builtAfter.lock()).contains(lock);
    }

    @Test
    void ac_qry_16_with_lock_keeps_the_fetch_plan() {
        var plan = FetchPlan.of(SelectSet.of(ID));
        var planned = keyed().fetch(plan).lock(RowLock.exclusive()).build();
        var relocked = planned.withLock(RowLock.exclusive(Wait.NO_WAIT));
        assertThat(relocked.fetch()).contains(plan);
        assertThat(relocked.lock()).contains(RowLock.exclusive(Wait.NO_WAIT));
        // A withFetch copy, locked afterwards, keeps its plan too.
        var fetched = keyed().build().withFetch(plan).withLock(RowLock.shared());
        assertThat(fetched.fetch()).contains(plan);
        assertThat(fetched.lock()).contains(RowLock.shared());
    }

    // ---- MQ1209, a lock on a grouped query

    @Test
    void ac_qry_16_a_lock_on_a_grouped_query_throws_mq1209_at_build() {
        assertThatThrownBy(() -> ModelQuery.builder(ROOT, MAPPER)
                .select(SelectSet.of(STATUS)).groupBy(STATUS).lock(RowLock.exclusive()).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1209))
                .hasMessageStartingWith("MQ1209: OrderView:");
    }

    @Test
    void ac_qry_16_with_lock_on_a_grouped_query_throws_mq1209() {
        var grouped = ModelQuery.builder(ROOT, MAPPER).select(SelectSet.of(STATUS)).groupBy(STATUS).build();
        assertThatThrownBy(() -> grouped.withLock(RowLock.exclusive()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1209))
                .hasMessageStartingWith("MQ1209: OrderView:");
    }

    // ---- MQ1205, distinct or having added to a locked query

    @Test
    void ac_qry_16_a_customizers_distinct_on_a_locked_query_throws_mq1205() {
        assertMq1205(customized(RowLock.exclusive(), (spec, joins, query, cb, phase) -> query.distinct(true)));
        // The same customizer on an unlocked query is fine: distinct is the caller's to set there.
        buildQuery(customized(null, (spec, joins, query, cb, phase) -> query.distinct(true)));
    }

    @Test
    void ac_qry_16_a_customizers_having_on_a_locked_query_throws_mq1205() {
        Predicate dummy = stubPredicate();
        assertMq1205(customized(RowLock.exclusive(), (spec, joins, query, cb, phase) -> query.having(dummy)));
        buildQuery(customized(null, (spec, joins, query, cb, phase) -> query.having(dummy)));
    }

    private static ModelQuery<Order, Object, OrderView> customized(RowLock lock, QueryCustomizer customizer) {
        var builder = ModelQuery.builder(ROOT, MAPPER).select(SelectSet.<OrderView>of()).customize(customizer);
        return (lock == null ? builder : builder.lock(lock)).build();
    }

    private static void assertMq1205(ModelQuery<Order, Object, OrderView> query) {
        assertThatThrownBy(() -> buildQuery(query))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1205))
                .hasMessageContaining("on a locked query");
    }

    /** Builds the MODEL statement against a stub CriteriaBuilder, as an executor would; only the refusal matters. */
    private static void buildQuery(ModelQuery<Order, Object, OrderView> query) {
        query.buildQuery(stubCriteria(), Phase.MODEL, RenderOptions.portable());
    }

    // ---- a CriteriaBuilder that hands out one recording query: the guard is pure definition, no database

    private static CriteriaBuilder stubCriteria() {
        CriteriaQuery<Tuple> query = stubQuery();
        return (CriteriaBuilder) Proxy.newProxyInstance(RowLockTest.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method.getName(), args);
                    }
                    if (method.getName().equals("createTupleQuery")) {
                        return query;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CriteriaQuery<Tuple> stubQuery() {
        boolean[] distinct = {false};
        Object[] having = {null};
        return (CriteriaQuery<Tuple>) Proxy.newProxyInstance(RowLockTest.class.getClassLoader(),
                new Class<?>[] {CriteriaQuery.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "from" -> stubRoot();
                    case "multiselect" -> proxy;
                    case "having" -> {
                        having[0] = args[0] instanceof Object[] all ? (all.length == 0 ? null : all[0]) : args[0];
                        yield proxy;
                    }
                    case "getGroupRestriction" -> having[0];
                    case "distinct" -> {
                        distinct[0] = (boolean) args[0];
                        yield proxy;
                    }
                    case "getOrderList", "getGroupList" -> List.of();
                    case "isDistinct" -> distinct[0];
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "stub CriteriaQuery";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Root<?> stubRoot() {
        return (Root<?>) Proxy.newProxyInstance(RowLockTest.class.getClassLoader(), new Class<?>[] {Root.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getJavaType")) {
                        return Order.class;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method.getName(), args);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The predicate a customizer's {@code having(...)} takes: the guard compares it, it is never rendered. */
    private static Predicate stubPredicate() {
        return (Predicate) Proxy.newProxyInstance(RowLockTest.class.getClassLoader(),
                new Class<?>[] {Predicate.class}, (proxy, method, args) -> null);
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> "a stub of " + proxy.getClass().getInterfaces()[0].getSimpleName();
        };
    }
}
