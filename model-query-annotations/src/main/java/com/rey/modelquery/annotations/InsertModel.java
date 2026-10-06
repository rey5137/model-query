package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or record listing the root-entity attributes an insert writes. Unlike an update model it is
 * instantiated: each instance is one row, and every field is a column of it. The processor generates its column
 * constants, {@code INSERT_COLUMNS}, and {@code insert(rows)}, {@code insertFrom(sourceRoot)} and
 * {@code persist(row)}. A type carries at most one of {@code @QueryModel}, {@code @UpdateModel} and
 * {@code @InsertModel}.
 */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface InsertModel {

    /**
     * The root JPA entity the model writes. It may be in the same compilation or on the classpath.
     *
     * @return the root entity class
     */
    Class<?> root();

    /**
     * Prefix of the generated class. The {@code -Amodelquery.prefix=} processor option sets it for a whole
     * compilation.
     *
     * @return the class name prefix
     */
    String prefix() default "Q";
}
