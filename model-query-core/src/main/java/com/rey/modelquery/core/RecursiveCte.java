package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

/**
 * A recursive source's CTE once the provider registered it on a statement (R-SRC-07, R-SRC-12): the root the outer
 * statement reads the source's rows by, and the two checks {@code core} adds to that statement's selection.
 *
 * <p>Each check is a scalar sub-select over the same CTE, so it sees every row the recursion produced, the marked one
 * and the level past {@code maxDepth} included, whatever the outer query filters. Both are read from the statement's
 * first row and select the smallest key of the offending rows, so a raised code names the first offending key. A
 * check is {@code null} where the CTE holds no offending row, since {@code min} over none is {@code NULL}:
 * {@link #depthKey()} is non-{@code null} only for a tree deeper than {@code maxDepth} ({@code MQ2701}), and
 * {@link #cycleKey()} only for a graph that closed a cycle ({@code MQ2702}).
 *
 * @param root the root of the CTE's columns, to read the source by
 * @param depthKey the smallest key of the rows past {@code maxDepth}, or {@code null} for none
 * @param cycleKey the smallest key of the marked rows, or {@code null} for none
 * @implSpec R-SRC-07, R-SRC-12
 */
@EngineFacing
@Incubating
public record RecursiveCte(Root<Tuple> root, Expression<?> depthKey, Expression<?> cycleKey) {
}
