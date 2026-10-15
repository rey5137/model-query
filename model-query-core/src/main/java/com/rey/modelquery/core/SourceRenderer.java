package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

/**
 * How a query rooted at a source reaches its provider's CTE and window support, so {@code core} names no provider
 * (INV-7): it registers {@code body}, the standard {@code CriteriaQuery<Tuple>} the source's fields alias by name, on
 * the {@code outer} statement that reads it, returns the root of the CTE's columns, and renders a window field's
 * {@code OVER (...)} clause (R-SRC-10, R-SRC-12, R-SRC-04, D-143).
 *
 * <p>The executor supplies one from the factory's {@code ProviderSupport#sources()}; the engine calls it once per
 * statement it builds over a source, so a statement never reaches the database without its CTE registered (R-SRC-12).
 * A query over a source with no renderer is {@code MQ4014}, raised at build time, before any statement.
 *
 * @implSpec R-SRC-10, R-SRC-12, R-SRC-04, INV-7
 */
@EngineFacing
@Incubating
public interface SourceRenderer {

    /**
     * Registers {@code body} as the CTE of {@code outer} and returns the root whose columns the outer statement reads
     * by the source field names, each a {@link Tuple} element aliased to that name (R-SRC-12).
     */
    Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body);

    /**
     * Renders {@code spec} as a window function with an {@code OVER (...)} clause, over {@code cb}'s criteria. The
     * provider's typed window methods are used where it has them, and {@code windowFunction(name, ...)} otherwise
     * (R-SRC-04).
     */
    Expression<?> window(CriteriaBuilder cb, WindowSpec spec);
}
