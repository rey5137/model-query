package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The container of a repeated {@link JoinSource}. */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface JoinSources {

    /**
     * The repeated annotations.
     *
     * @return the joins
     */
    JoinSource[] value();
}
