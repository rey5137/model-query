package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or record listing the root-entity attributes a bulk update may write. The processor generates its
 * column constants with {@code update(...)} and {@code delete()}, and a change set named after the model with a
 * {@code Changes} suffix. The type is only read by the processor and never instantiated.
 */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface UpdateModel {

    /**
     * The root JPA entity the model writes. It may be in the same compilation or on the classpath.
     *
     * @return the root entity class
     */
    Class<?> root();

    /**
     * Prefix of the generated class of column constants; the change set's name has none. The
     * {@code -Amodelquery.prefix=} processor option sets it for a whole compilation.
     *
     * @return the class name prefix
     */
    String prefix() default "Q";
}
