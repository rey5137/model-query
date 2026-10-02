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

    /** Averages the attribute. */
    AVG,

    /** The attribute's smallest value. */
    MIN,

    /** The attribute's largest value. */
    MAX
}
