package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The container that makes {@link BindInsertModel} repeatable. It is never written by hand.
 */
@Incubating
@Documented
@Target({ElementType.TYPE, ElementType.PACKAGE})
@Retention(RetentionPolicy.CLASS)
public @interface BindInsertModels {

    /**
     * The repeated annotations.
     *
     * @return the bindings, in declaration order
     */
    BindInsertModel[] value();
}
