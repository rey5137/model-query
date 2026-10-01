package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One ordering key: a selection, a direction and a null precedence. Immutable; the {@code nulls} methods return
 * copies.
 *
 * @param column the selection ordered by
 * @param ascending {@code true} for ascending
 * @param nulls where NULLs sort
 * @param <M> the model the selection belongs to
 * @param <C> the selection's Java type
 * @implSpec R-COL-12
 */
public record OrderField<M, C>(SelectField<M, C> column, boolean ascending, NullPrecedence nulls) {

    /** Validates the components. */
    public OrderField {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(nulls, "nulls");
    }

    /** A copy that sorts NULLs first on every vendor. */
    public OrderField<M, C> nullsFirst() {
        return nulls(NullPrecedence.FIRST);
    }

    /** A copy that sorts NULLs last on every vendor. */
    public OrderField<M, C> nullsLast() {
        return nulls(NullPrecedence.LAST);
    }

    /** A copy with {@code nulls}, for a value chosen at runtime. */
    public OrderField<M, C> nulls(NullPrecedence nulls) {
        return new OrderField<>(column, ascending, nulls);
    }

    /**
     * The JPA rendering, by {@code ctx}'s {@link RenderOptions}: {@code DEFAULT} renders the column alone; otherwise
     * the provider's native null-precedence renderer, if any, which renders no null clause where the dialect's default
     * already matches; otherwise the portable form, a leading {@code CASE WHEN col IS NULL THEN 0 ELSE 1 END} key,
     * ascending for {@code FIRST} and descending for {@code LAST}, then the column itself. Plain JPA 3.1 {@code Order}
     * has no null precedence, so the portable form is the path without {@code model-query-hibernate}, even where the
     * database's default already matches: a bare order would take any default null ordering the provider is
     * configured with, which nothing reports there (D-36).
     *
     * @implSpec R-COL-12
     */
    @EngineFacing
    public List<Order> toOrders(JoinContext ctx, CriteriaBuilder cb) {
        Expression<C> expression = column.expression(Objects.requireNonNull(ctx, "ctx"));
        Order own = ascending ? cb.asc(expression) : cb.desc(expression);
        if (nulls == NullPrecedence.DEFAULT) {
            return List.of(own);
        }
        RenderOptions options = ctx.renderOptions();
        // The provider's renderer omits the clause itself where the dialect already sorts so.
        Optional<Order> nativeOrder = options.nullPrecedenceRenderer()
                .flatMap(renderer -> renderer.order(cb, expression, ascending, nulls));
        if (nativeOrder.isPresent()) {
            return List.of(nativeOrder.get());
        }
        Expression<Integer> nullKey = cb.<Integer>selectCase()
                .when(cb.isNull(expression), 0)
                .otherwise(1);
        Order first = nulls == NullPrecedence.FIRST ? cb.asc(nullKey) : cb.desc(nullKey);
        return List.of(first, own);
    }
}
