package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Subquery;
import java.util.List;

/**
 * The provider's ordered scalar sub-query support, beyond standard Criteria (R-VND-25, INV-7).
 * The executor supplies it through {@code ProviderSupport#subqueries()}.
 */
@EngineFacing
@Incubating
public interface SubqueryRenderer {

    /** Orders {@code sub} by the complete order, including its id tie-breaker, and limits it to one row (R-COL-32). */
    @Incubating
    <C> Expression<C> firstRow(CriteriaBuilder cb, Subquery<C> sub, List<Order> order);
}
