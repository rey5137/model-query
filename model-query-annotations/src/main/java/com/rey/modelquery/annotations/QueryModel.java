package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a model class or record for QModel generation.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface QueryModel {

    /**
     * The root JPA entity the model is read from.
     *
     * @return the root entity class
     */
    Class<?> root();

    /**
     * Whether to generate {@code ColumnSet} constants.
     *
     * @return {@code true} to generate column sets
     */
    boolean generateColumnSets() default true;

    /**
     * Prefix of the generated class name.
     *
     * @return the class name prefix
     */
    String prefix() default "Q";
}
