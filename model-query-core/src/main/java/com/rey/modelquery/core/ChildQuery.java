package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The filters, order and bound of one child load. Immutable: every method returns a copy (INV-9). A plan starts from
 * an empty one: no filter, the child's primary-key order and no bound.
 *
 * @param <C> the child model
 * @implSpec R-FCH-04, R-FCH-11
 */
@Incubating
public final class ChildQuery<C> {

    private static final ChildQuery<?> EMPTY = new ChildQuery<>(List.of(), List.of(), 0, null);

    private final List<Filter> where;
    private final List<OrderField<C, ?>> orderBy;
    /** The most children per parent, or 0 for no bound. */
    private final int maxPerParent;
    /** The child model's root entity, so a correlation checks a lifted column against it, or {@code null}. */
    private final Class<?> rootEntity;

    private ChildQuery(List<Filter> where, List<OrderField<C, ?>> orderBy, int maxPerParent, Class<?> rootEntity) {
        this.where = where;
        this.orderBy = orderBy;
        this.maxPerParent = maxPerParent;
        this.rootEntity = rootEntity;
    }

    /** A child load with no filter, the default order and no bound. */
    @SuppressWarnings("unchecked")
    static <C> ChildQuery<C> empty() {
        return (ChildQuery<C>) EMPTY; // holds no C
    }

    /** As {@link #empty()}, with {@code rootEntity} as the child model's root entity (R-FLT-17). */
    static <C> ChildQuery<C> empty(Class<?> rootEntity) {
        return new ChildQuery<>(List.of(), List.of(), 0, rootEntity);
    }

    /**
     * The child filters, ANDed with the key match: {@code filters} runs once, here, as
     * {@link ModelQuery.Builder#where} runs. Replaces any filters set before.
     */
    public ChildQuery<C> where(UnaryOperator<Filters<C>> filters) {
        return new ChildQuery<>(FilterGroup.collect(rootEntity, Objects.requireNonNull(filters, "filters")), orderBy,
                maxPerParent, rootEntity);
    }

    /** The child order, replacing any set before; a load closes it with the child's primary key (R-FCH-04). */
    @SafeVarargs
    public final ChildQuery<C> orderBy(OrderField<C, ?>... orderBy) {
        var copy = new ArrayList<OrderField<C, ?>>();
        for (OrderField<C, ?> order : Objects.requireNonNull(orderBy, "orderBy")) {
            copy.add(Objects.requireNonNull(order, "orderBy element"));
        }
        return new ChildQuery<>(where, List.copyOf(copy), maxPerParent, rootEntity);
    }

    /**
     * Bounds the children of one parent at {@code n} (R-FCH-11).
     *
     * @throws ModelQueryExecutionException {@code MQ2001} when {@code n} is not positive
     */
    public ChildQuery<C> maxPerParent(int n) {
        if (n < 1) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "maxPerParent(" + n + ") must be positive");
        }
        return new ChildQuery<>(where, orderBy, n, rootEntity);
    }

    /** The child filters, ANDed. */
    List<Filter> filters() {
        return where;
    }

    /** The child order, without the primary-key tie-breaker. */
    List<OrderField<C, ?>> order() {
        return orderBy;
    }

    /** The most children per parent, or 0 for no bound. */
    int bound() {
        return maxPerParent;
    }
}
