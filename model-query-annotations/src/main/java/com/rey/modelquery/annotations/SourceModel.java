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
 * {@code source()} builder in place of {@code query()} (R-GEN-37). A source also carries {@link Window}, which no
 * query model does, and {@link Discriminator}, which fixes a key column to a constant (R-PROC-36, R-PROC-38).
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
     * The model the source is read as: another {@code @SourceModel} of the same compilation whose fields this model
     * maps onto its own root (R-PROC-38). The model is then a <em>branch</em> of that shape, and its {@code source()}
     * builder builds a {@code SourceQuery<Shape>}, so only another branch of the same shape combines with it. A
     * branch's fields must match the shape's by name, in declaration order, by language type and by what the database
     * stores, and its {@code @PrimaryKey} must be the shape's ({@code MQ3034}).
     *
     * @return the shape class, {@code void.class} for the model itself
     */
    @Incubating
    Class<?> shape() default void.class;
}
