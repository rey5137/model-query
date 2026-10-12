package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaQuery;

/**
 * A rendered entity-mode write load: the query and the context that rendered it. An executor binds the context's
 * {@code Expr.value} parameters on the statement it creates (R-COL-26, R-WRT-42).
 *
 * @param <E> the entity type
 * @param query the load
 * @param joins the context that rendered it
 */
@EngineFacing
@Incubating
public record EntityLoad<E>(CriteriaQuery<E> query, JoinContext joins) {}
