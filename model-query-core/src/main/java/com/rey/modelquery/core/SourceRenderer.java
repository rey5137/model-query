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
 * <p>{@link #cte} is the one method a provider must implement; {@link #recursive}, {@link #window} and {@link #combine}
 * default to {@code MQ4014}, so a provider can ship CTE support only.
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
     * Registers {@code spec} as the {@code WITH RECURSIVE} CTE of {@code outer} and returns its root together with
     * the check expressions the statement's first row is read for (R-SRC-07, R-SRC-12, R-SRC-14). A tree source
     * requires a duplicate-key check; returning none is {@code MQ4014} before any statement. The CTE recurses to
     * {@code maxDepth + 1} levels, marks the row that closes a cycle, and joins each level to the previous one
     * through the step's keys (RFC 0013 §5).
     */
    default RecursiveCte recursive(CriteriaQuery<?> outer, RecursiveSpec spec) {
        throw unsupported("recursive CTEs");
    }

    /**
     * Renders {@code spec} as a window function with an {@code OVER (...)} clause, over {@code cb}'s criteria. The
     * provider's typed window methods are used where it has them, and {@code windowFunction(name, ...)} otherwise
     * (R-SRC-04).
     */
    default Expression<?> window(CriteriaBuilder cb, WindowSpec spec) {
        throw unsupported("window functions");
    }

    /**
     * Combines {@code left} and {@code right} into one set operation, itself a statement the provider can register as
     * the source's body (R-SRC-05, R-PROC-38). A combined source builds its CTE body from this, in the order its
     * operations were called, so {@code a.union(b).except(c)} is {@code (a union b) except c}.
     *
     * @param cb the provider's builder the branches were built with
     * @param operation the operation to apply
     * @param left the left branch, itself a combined query when the operation nests
     * @param right the right branch
     * @return the combined statement, whose columns are the branches' aliases in order
     */
    default CriteriaQuery<Tuple> combine(CriteriaBuilder cb, SetOperation operation, CriteriaQuery<Tuple> left,
            CriteriaQuery<Tuple> right) {
        throw unsupported("set operations");
    }

    private static ModelQueryConfigurationException unsupported(String feature) {
        return new ModelQueryConfigurationException(MqCode.MQ4014,
                "the factory's persistence provider renders CTE sources only, not " + feature);
    }
}
