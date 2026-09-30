package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import java.util.Optional;

/**
 * Renders an ordering with an explicit null precedence natively, where the persistence provider can. Without one,
 * the precedence is rendered as a portable sort key (R-COL-12).
 *
 * @implSpec R-COL-12
 */
@Incubating
@FunctionalInterface
public interface NullPrecedenceRenderer {

    /**
     * The ordering of {@code expression} with {@code precedence}, or empty when this renderer cannot render it and
     * the portable form is used instead.
     */
    Optional<Order> order(CriteriaBuilder cb, Expression<?> expression, boolean ascending, NullPrecedence precedence);
}
