package com.rey.modelquery.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type whose methods return a new immutable value rather than changing the receiver, so ignoring a result
 * (an unassigned {@code select(...)}) is a bug. Package-private on purpose: Error Prone's {@code CheckReturnValue}
 * check matches any annotation of this simple name, so it needs no public type or dependency (D-183).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@interface CheckReturnValue {
}
