package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a filter-only column: a column constant with no model field, left out of every generated column set and of
 * the row mapping.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(FilterColumns.class)
public @interface FilterColumn {

    /**
     * Name of the generated constant.
     *
     * @return the constant name
     */
    String name();

    /**
     * Dotted attribute path from the root entity ({@code "deleted"}, {@code "customer.country"}).
     *
     * @return the attribute path
     */
    String path();

    /**
     * How an association on the path is joined when no {@link Join} already joins it.
     *
     * @return the join kind
     */
    JoinKind joinType() default JoinKind.LEFT;

    /**
     * Puts the whole path on a separate join. Filter columns sharing an alias share that join.
     *
     * @return the alias, or {@code ""} for none
     */
    String alias() default "";

    /**
     * The {@code ColumnConverter} between the constant's type and the entity attribute type.
     *
     * @return the converter class, or {@code void.class} for none
     */
    Class<?> converter() default void.class;
}
