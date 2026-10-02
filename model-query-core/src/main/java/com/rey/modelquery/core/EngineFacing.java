package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public type or method that only an executor uses. It is not API: it may change in any release, and
 * {@code japicmp} excludes it (D-72, D-86).
 */
@Incubating
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface EngineFacing {
}
