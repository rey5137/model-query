package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * The recursive term of a source's CTE (R-SRC-07): one level of the recursion, built by {@code core} and handed to
 * the provider, which is the only side that can name the CTE a term reads from.
 *
 * <p>The provider creates the term's {@code query}, puts the CTE's own columns on it and passes that root as
 * {@code parent}; the term then adds the entity root, one row per child, and joins each child to its parent through
 * the step's keys. It is called once per statement, inside the provider's {@code SourceRenderer#recursive}.
 *
 * @implSpec R-SRC-07, R-SRC-12, INV-7
 */
@FunctionalInterface
@EngineFacing
@Incubating
public interface RecursiveTerm {

    /**
     * Fills {@code query} with one level of the recursion and returns it, the query the provider registers as the
     * term.
     *
     * @param query the query the provider created for the term, already carrying {@code parent} as the statement's
     *     existing root
     * @param parent the root of the CTE's own columns on {@code query}, read by the aliases of
     *     {@link RecursiveSpec}
     */
    CriteriaQuery<Tuple> term(CriteriaQuery<Tuple> query, Root<Tuple> parent);
}
