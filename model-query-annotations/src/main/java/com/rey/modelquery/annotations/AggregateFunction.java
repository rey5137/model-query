package com.rey.modelquery.annotations;

/**
 * The function of an {@link Aggregate}.
 */
@Incubating
public enum AggregateFunction {

    /** Counts rows, or the attribute's non-null values. */
    COUNT,

    /** Sums the attribute. */
    SUM,

    /**
     * Averages the attribute. On a {@code BigDecimal} field it is the exact average of a {@code BigDecimal},
     * {@code Long}, {@code Integer} or {@code Short} attribute (R-AGG-18); on a {@code Double} field, the database's.
     */
    AVG,

    /** The attribute's smallest value. */
    MIN,

    /** The attribute's largest value. */
    MAX,

    /**
     * The attribute's value on the first row of the group in the {@link Aggregate#orderBy()} order, with the
     * root's id appended so a tie picks the same row everywhere (R-AGG-16). Needs {@code orderBy}.
     */
    FIRST_BY
}
