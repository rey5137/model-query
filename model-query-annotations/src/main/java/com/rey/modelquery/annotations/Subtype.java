package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps the exact entity class into one {@code Class<? extends R>} field, where {@code R} is the model's root
 * (R-PROC-43). It may also carry {@link GroupBy}.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Subtype {}
