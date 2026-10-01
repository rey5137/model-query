package com.rey.modelquery.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public method that only an executor calls. It is not API: it may change in any release, and {@code japicmp}
 * excludes it (D-72).
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface EngineFacing {
}
