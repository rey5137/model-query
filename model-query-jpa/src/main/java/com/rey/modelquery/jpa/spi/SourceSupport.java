package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * A persistence provider's support for reading a query source, which {@code jpa} reaches only through narrow hooks on
 * standard JPA types, so no provider type leaks into it (INV-7, D-143). The engine builds the source's CTE body
 * itself, as a standard {@code CriteriaQuery<Tuple>} whose elements are aliased by the source's field names, and
 * registers it on every statement it builds over the source (R-SRC-12). Discovered through
 * {@link ProviderSupport#sources()}; an implementation is stateless and thread-safe.
 *
 * <p>Only {@code cte} exists yet: the set-operation, window and recursive hooks land with the slices that use them
 * (R-PROC-37, R-PROC-38).
 *
 * @implSpec R-SRC-11, R-SRC-12, D-143
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
}
