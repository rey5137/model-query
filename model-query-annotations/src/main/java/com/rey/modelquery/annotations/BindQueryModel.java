package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a query model compiled without its entity to its root: written in the module that sees the entity, on any type
 * or on a {@code package-info}, it generates the model's QModel in that type's package, as {@link QueryModel} on the
 * model would. The model carries its field annotations and no {@link QueryModel}; it, its no-arg constructor (a class)
 * and the setters and getters the QModel calls are public, since the QModel is in another package.
 */
@Incubating
@Documented
@Target({ElementType.TYPE, ElementType.PACKAGE})
@Retention(RetentionPolicy.CLASS)
@Repeatable(BindQueryModels.class)
public @interface BindQueryModel {

    /**
     * The model class or record. It may be in the same compilation or on the classpath.
     *
     * @return the model class
     */
    Class<?> model();

    /**
     * The root JPA entity the model is read from, as {@link QueryModel#root()}.
     *
     * @return the root entity class
     */
    Class<?> root();

    /**
     * Whether to generate {@code SelectSet} constants, as {@link QueryModel#generateSelectSets()}.
     *
     * @return {@code true} to generate column sets
     */
    boolean generateSelectSets() default true;

    /**
     * Prefix of the generated class name, as {@link QueryModel#prefix()}.
     *
     * @return the class name prefix
     */
    String prefix() default "Q";

    /**
     * Suffix of the generated class name, as {@link QueryModel#suffix()}.
     *
     * @return the class name suffix
     */
    String suffix() default "";

    /**
     * Marks a model of one group over the whole table, as {@link QueryModel#singleGroup()}.
     *
     * @return {@code true} when the model is one group over the whole table
     */
    boolean singleGroup() default false;
}
