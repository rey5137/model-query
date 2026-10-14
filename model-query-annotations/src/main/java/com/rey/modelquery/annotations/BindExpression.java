package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The definition of one field of a bound model, written in {@link BindQueryModel#expressions()}: a {@link Computed}
 * field written without a value takes it as its definition, and an {@link Aggregate} field written without an
 * {@code expression} aggregates it, as {@code @Aggregate(expression = ...)} would.
 */
@Incubating
@Documented
@Target({})
@Retention(RetentionPolicy.CLASS)
public @interface BindExpression {

    /**
     * The model field or record component the definition is for.
     *
     * @return the field name
     */
    String field();

    /**
     * The class implementing {@code ExpressionDefinition<Model, C>}, as {@link Computed#value()} and
     * {@link Aggregate#expression()} take it.
     *
     * @return the definition class
     */
    Class<?> value();
}
