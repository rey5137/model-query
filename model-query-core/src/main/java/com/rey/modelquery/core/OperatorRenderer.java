package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Builds a {@link FilterOperator}'s predicate from its two operands. It never sees the value: in the value form
 * {@code right} is the statement parameter the engine binds, so the renderer must use that {@code right} and never
 * capture a value of its own ({@code cb.equal(left, someValue)}, {@code cb.literal}), which a provider may render
 * inline (R-FLT-08).
 *
 * @param <C> the column's type
 * @param <V> the operand's type
 * @implSpec api/12 R-FLT-20, D-167
 */
@Incubating
@FunctionalInterface
public interface OperatorRenderer<C, V> {

    /**
     * The predicate {@code left} meets {@code right}. Returning {@code null} or throwing a {@code RuntimeException} is
     * {@code MQ1313}; a {@link ModelQueryException} keeps its own code.
     *
     * @param ctx the statement's context
     * @param left the column's expression, of the attribute's type on a converted column (D-37), though typed
     *     {@code C}
     * @param right the operand: the bound value's parameter, or the right field's expression
     */
    Predicate render(OperatorContext ctx, Expression<C> left, Expression<V> right);
}
