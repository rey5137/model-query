package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an aggregate selection, mapped into the annotated field.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Aggregate {

    /**
     * Named entity join whose attributes and order keys this aggregate reads; empty for the model root.
     * A nonempty value cannot be combined with an expression, including one supplied by {@link BindExpression}.
     */
    @Incubating
    String entityJoin() default "";

    /**
     * The aggregate function.
     *
     * @return the function
     */
    AggregateFunction fn();

    /**
     * The entity attribute the function is applied to. Omit it for a {@code COUNT} over the root.
     *
     * @return the attribute, or {@code ""} for none
     */
    String attribute() default "";

    /**
     * The class implementing {@code ExpressionDefinition<Model, C>} whose expression the function is applied to,
     * instead of an attribute (R-PROC-22). It is declared as {@code Class<?>} because the interface is in
     * {@code model-query-core}, which this module does not depend on (R-PROC-01). {@code attribute} and
     * {@code expression} together are {@code MQ3208}.
     *
     * @return the definition class, or {@code void.class} for none
     */
    Class<?> expression() default void.class;

    /**
     * Whether the function is applied to distinct values.
     *
     * @return {@code true} for a distinct aggregate
     */
    boolean distinct() default false;

    /**
     * For {@code FIRST_BY}: the keys the group is ordered by, each an attribute path of the root with an optional
     * {@code asc} or {@code desc} and an optional {@code nulls first} or {@code nulls last}, such as
     * {@code "qty desc nulls last"}. {@code FIRST_BY} needs it and no other function takes it (R-PROC-45).
     *
     * @return the order keys, or none
     */
    String[] orderBy() default {};

    /**
     * The class implementing {@code FilterDefinition<Model>} whose condition limits the rows the function reads, as
     * {@code AggregateField#filter} does (R-AGG-17, R-PROC-45). It is declared as {@code Class<?>} for the reason
     * {@link #expression()} is.
     *
     * @return the definition class, or {@code void.class} for none
     */
    Class<?> filter() default void.class;

    /**
     * The number of decimals a {@code BigDecimal} aggregate is rounded to, half away from zero, from 0 to 30
     * (R-AGG-18). The field must be a {@code BigDecimal}.
     *
     * @return the scale; leave it out for no rounding
     */
    int scale() default -1;
}
