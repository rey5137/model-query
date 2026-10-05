package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.Expression;

/**
 * Anything a query can select, order by or read back from a row: a {@link ScalarField} (a {@link ColumnField} or an
 * {@link ExpressionField}) or an {@link AggregateField}. Predicates in {@code WHERE} take a {@code ScalarField} only,
 * so an aggregate there does not compile (R-COL-06).
 *
 * @param <M> the model the selection belongs to
 * @param <C> the selected value's Java type
 * @implSpec R-COL-06, R-COL-16, D-3
 */
@Incubating
public sealed interface SelectField<M, C> permits ScalarField, AggregateField {

    /** The Java type of the selected value. */
    Class<C> type();

    /** The name used in diagnostic messages. */
    String name();

    /** The Criteria expression this selection renders to, resolving any join through {@code ctx}. */
    Expression<C> expression(JoinContext ctx);

    /** Ascending order on this selection, with the database's own null order. */
    default OrderField<M, C> asc() {
        return new OrderField<>(this, true, NullPrecedence.DEFAULT);
    }

    /** Descending order on this selection, with the database's own null order. */
    default OrderField<M, C> desc() {
        return new OrderField<>(this, false, NullPrecedence.DEFAULT);
    }
}
