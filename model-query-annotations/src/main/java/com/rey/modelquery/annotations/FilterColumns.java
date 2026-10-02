package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The container that makes {@link FilterColumn} repeatable. It is never written by hand.
 */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface FilterColumns {

    /**
     * The repeated annotations.
     *
     * @return the filter columns, in declaration order
     */
    FilterColumn[] value();
}
