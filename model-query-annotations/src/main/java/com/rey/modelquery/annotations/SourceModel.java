package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or record as a <em>source</em>: a query an outer model reads from, built into a CTE at run time
 * (R-SRC-01). It takes the same field and type annotations as {@link QueryModel} — {@link Column}, a to-one
 * {@link Join}, {@link Computed}, {@link Aggregate}, {@link GroupBy} and {@link FilterColumn} — and its
 * {@code source()} builder in place of {@code query()} (R-GEN-37).
 */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface SourceModel {

    /**
     * The JPA entity the source reads. It is an {@code @Entity} of the same compilation.
     *
     * @return the root entity class
     */
    Class<?> root();

    /**
     * Reserved for a later release (R-PROC-38): the model the source is read as, when a set operation or a
     * discriminator makes it differ from the source model itself. Not acted on yet.
     *
     * @return the shape class, {@code void.class} for the model itself
     */
    @Incubating
    Class<?> shape() default void.class;
}
