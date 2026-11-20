package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * What a fetch plan's {@code values} entry adds to a value child's load: only its bound. A basic value has no column
 * for a filter, an order or a key group to read (R-FCH-19). Immutable (INV-9).
 *
 * @param <E> the element type
 * @implSpec R-FCH-11, R-FCH-19
 */
@CheckReturnValue
@Incubating
public final class ValueChildQuery<E> {

    private static final ValueChildQuery<?> EMPTY = new ValueChildQuery<>(0);

    private final int maxPerParent;

    private ValueChildQuery(int maxPerParent) {
        this.maxPerParent = maxPerParent;
    }

    /** No bound. */
    @SuppressWarnings("unchecked")
    static <E> ValueChildQuery<E> empty() {
        return (ValueChildQuery<E>) EMPTY; // holds no E
    }

    /**
     * Bounds the values of one parent at {@code n}, counting rows before a {@code Set} field collapses equal ones
     * (R-FCH-11, D-247).
     *
     * @throws ModelQueryExecutionException {@code MQ2001} when {@code n} is not positive
     */
    public ValueChildQuery<E> maxPerParent(int n) {
        if (n < 1) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "maxPerParent(" + n + ") must be positive");
        }
        return new ValueChildQuery<>(n);
    }

    /** The most values per parent, or 0 for no bound. */
    int bound() {
        return maxPerParent;
    }
}
