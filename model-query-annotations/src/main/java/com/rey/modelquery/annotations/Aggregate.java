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
     * Whether the function is applied to distinct values.
     *
     * @return {@code true} for a distinct aggregate
     */
    boolean distinct() default false;
}
