package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Leaves the column out of the inserts a {@code generateInserts} query model generates, so the database default
 * applies under {@code insert} and the no-arg constructor's value under {@code persist}. The model still reads it,
 * and its constant stays available, for example to {@code insert(rows).set(CREATED_AT, now)}.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface ExcludeFromInserts {
}
