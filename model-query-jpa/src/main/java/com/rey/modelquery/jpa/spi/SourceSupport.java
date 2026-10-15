package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
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
 * <p>The recursive hook lands with the slice that uses it (R-PROC-37).
 *
 * @implSpec R-SRC-11, R-SRC-12, R-SRC-04, R-SRC-05, D-143
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
