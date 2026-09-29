package com.rey.modelquery.core;

/**
 * A comparison between two columns, for {@link Filters#compare}.
 *
 * @implSpec api/12 §1
 */
@Incubating
public enum Op {
    /** {@code left = right}. */
    EQ,
    /** {@code left <> right}. */
    NE,
    /** {@code left < right}. */
    LT,
    /** {@code left <= right}. */
    LTE,
    /** {@code left > right}. */
    GT,
    /** {@code left >= right}. */
    GTE
}
