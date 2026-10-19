package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.util.Objects;

/**
 * A recursive source's CTE once the provider registered it on a statement (R-SRC-07, R-SRC-12): the root the outer
 * statement reads the source's rows by, and the checks {@code core} adds to that statement's selection.
 *
 * <p>Each check is a scalar sub-select over the same CTE, so it sees every row the recursion produced, the marked one
 * and the level past {@code maxDepth} included, whatever the outer query filters. They are read from the statement's
 * first row and select the smallest key of the offending rows, so a raised code names the first offending key. A
 * check evaluates to {@code null} where the CTE holds no offending row:
 * {@link #depthKey()} is non-{@code null} only for a tree deeper than {@code maxDepth} ({@code MQ2701}), and
 * {@link #cycleKey()} only for a graph that closed a cycle ({@code MQ2702}). A tree source also carries
 * {@link #duplicateKey()}, read last, naming the first column of the smallest duplicated whole key ({@code MQ2703}).
 *
 * @param root the root of the CTE's columns, to read the source by
 * @param depthKey the depth check, always present: the smallest key of the rows past {@code maxDepth}, {@code null}
 *        where the CTE holds none
 * @param cycleKey the cycle check, always present: the smallest key of the marked rows, {@code null} where the CTE
 *        holds none
 * @param duplicateKey the duplicate-key check, or {@code null} when the renderer supplies none
 * @implSpec R-SRC-07, R-SRC-12, R-SRC-14
 */
@EngineFacing
@Incubating
public record RecursiveCte(Root<Tuple> root, Expression<?> depthKey, Expression<?> cycleKey,
        @Incubating Expression<?> duplicateKey) {

    public RecursiveCte {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(depthKey, "depthKey");
        Objects.requireNonNull(cycleKey, "cycleKey");
    }
}
