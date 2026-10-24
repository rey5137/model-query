package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Renames the entity attribute a model field reads, or converts its value.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Column {

    /** @return the entity join to read, or empty for the model's root */
    @Incubating
    String join() default "";

    /**
     * The entity attribute the column reads, when it is not the field's own name. A dotted path is allowed only while
     * it stays inside embedded values ({@code "address.city"}); crossing an association needs {@link Join} or
     * {@link FilterColumn}. On a named entity {@link #join()}, the path may cross to-one associations.
     *
     * @return the attribute, or {@code ""} for the field's name
     */
    String attribute() default "";

    /**
     * The strict entity subtype of the model's root that declares the attribute (R-PROC-41).
     *
     * @return the subtype, or {@code void.class} for none
     */
    @Incubating
    Class<?> treat() default void.class;

    /**
     * The {@code ColumnConverter} between the model type and the entity attribute type. It is declared as
     * {@code Class<?>} because this module depends on nothing but the JDK.
     *
     * @return the converter class, or {@code void.class} for none
     */
    Class<?> converter() default void.class;
}
