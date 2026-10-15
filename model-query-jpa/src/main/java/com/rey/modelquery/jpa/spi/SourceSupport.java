package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.RecursiveCte;
import com.rey.modelquery.core.RecursiveSpec;
import com.rey.modelquery.core.SetOperation;
import com.rey.modelquery.core.WindowSpec;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

/**
 * A persistence provider's support for reading a query source, which {@code jpa} reaches only through narrow hooks on
 * standard JPA types, so no provider type leaks into it (INV-7, D-143). The engine builds the source's CTE body
 * itself, as a standard {@code CriteriaQuery<Tuple>} whose elements are aliased by the source's field names, and
 * registers it on every statement it builds over the source (R-SRC-12). Discovered through
 * {@link ProviderSupport#sources()}; an implementation is stateless and thread-safe.
 *
 * <p>A recursive source's CTE is registered through {@link #recursive} instead of {@link #cte}: its seed and its
 * recursive term are built by {@code core}, and the provider adds what only it can name — the {@code with recursive}
 * clause, the cycle clause and the two checks the statement's first row is read for (R-SRC-07).
 *
 * @implSpec R-SRC-11, R-SRC-12, R-SRC-04, R-SRC-05, R-SRC-07, D-143
 */
@Incubating
public interface SourceSupport {

    /**
     * Registers {@code body} as the common table expression of {@code outer}, the statement that reads the source,
     * and returns the root whose columns the outer statement reads by the source field names, each a {@link Tuple}
     * element aliased to that name (R-SRC-12).
     *
     * @param outer the statement that reads the source's rows; it carries no source yet
     * @param body the source's body, a standard {@code CriteriaQuery<Tuple>} aliased by field name
     * @return the root of the CTE's columns, to read the source by
     */
    Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body);

    /**
     * Registers {@code spec} as the {@code WITH RECURSIVE} CTE of {@code outer} and returns the root of its columns
     * together with the two check expressions, each a scalar sub-select over the same CTE: the smallest key of the
     * rows past {@code maxDepth} ({@code MQ2701}) and the smallest key of the marked rows ({@code MQ2702})
     * (R-SRC-07, R-SRC-12). The CTE's term is the {@code spec}'s, which {@code core} builds; the provider adds the
     * cycle clause, which is what makes a graph that reaches one node twice loud.
     *
     * @param outer the statement that reads the source's rows; it carries no source yet
     * @param spec the CTE: its seed, its recursive term, its depth limit and its column names
     * @return the root of the CTE's columns and the two checks, to append to {@code outer}'s selection
     */
    RecursiveCte recursive(CriteriaQuery<?> outer, RecursiveSpec spec);

    /**
     * Renders {@code spec} as a window function with an {@code OVER (...)} clause over {@code cb}: the function, its
     * partition terms, its order terms and its frame (R-SRC-04). A frame is never rendered for a function that takes
     * none, which H2 rejects; the source's own body renders no such frame (R-PROC-36).
     *
     * @param cb the provider's builder the source's body was built with
     * @param spec the window: function, argument, partitions, order terms and frame
     * @return the window expression, aliased by the source's body
     */
    Expression<?> window(CriteriaBuilder cb, WindowSpec spec);

    /**
     * Combines {@code left} and {@code right} into one set operation over {@code cb}: the statement the source's CTE
     * body is built from when a source combines branches (R-SRC-05). The provider's set-operation methods are used,
     * which JPA's own {@code CriteriaBuilder} has none of (D-143).
     *
     * @param cb the provider's builder the branches were built with
     * @param operation the operation to apply, {@code UNION_ALL} being {@code UNION} without duplicate removal
     * @param left the left branch, itself a combined statement when the operation nests
     * @param right the right branch
     * @return the combined statement, which a CTE may be registered from
     */
    CriteriaQuery<Tuple> combine(CriteriaBuilder cb, SetOperation operation, CriteriaQuery<Tuple> left,
            CriteriaQuery<Tuple> right);
}
