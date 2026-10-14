package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an insert model compiled without its entity to its root: written in the module that sees the entity, on any
 * type or on a {@code package-info}, it generates the model's QModel in that type's package, as {@link InsertModel} on
 * the model would. The model carries its field annotations and no {@link InsertModel}; it and the getters the QModel
 * reads each row through are public, since the QModel is in another package.
 */
@Incubating
@Documented
@Target({ElementType.TYPE, ElementType.PACKAGE})
@Retention(RetentionPolicy.CLASS)
@Repeatable(BindInsertModels.class)
public @interface BindInsertModel {

    /**
     * The model class or record. It may be in the same compilation or on the classpath.
     *
     * @return the model class
     */
    Class<?> model();

    /**
     * The root JPA entity the model writes, as {@link InsertModel#root()}.
     *
     * @return the root entity class
     */
    Class<?> root();

    /**
     * Prefix of the generated class name, as {@link InsertModel#prefix()}.
     *
     * @return the class name prefix
     */
    String prefix() default "Q";
}
