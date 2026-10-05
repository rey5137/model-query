package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * One value per row: a {@link ColumnField} or an {@link ExpressionField}. Everything that builds a {@code WHERE}
 * predicate or a group key, and every {@link Expr} argument, accepts a {@code ScalarField}, so an aggregate there is
 * a compile error rather than a runtime failure (R-COL-06, R-COL-16). Everything that selects, orders or reads a
 * value accepts the wider {@link SelectField}.
 *
 * @param <M> the model the field belongs to
 * @param <C> the value's Java type
 * @implSpec R-COL-06, R-COL-16, D-115
 */
@Incubating
public sealed interface ScalarField<M, C> extends SelectField<M, C> permits ColumnField, ExpressionField {
}
