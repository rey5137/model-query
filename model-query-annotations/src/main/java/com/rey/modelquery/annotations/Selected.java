package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the one field or record component of a {@code @QueryModel} that the generated mapper fills with the columns
 * the row selected, as a {@code SelectSet<M>} where {@code M} is the model itself (R-PROC-25). It is no column and
 * carries no other annotation of this library.
 *
 * <p>A column the row selected counts as selected even when its value is {@code NULL}, which a plain field cannot
 * tell from a column that was not selected. The set is never {@code null}, and holds every column, expression and
 * aggregate of the model that the mapper reads, joined columns included, that the row selected; it is not the
 * {@code SelectSet} the caller passed to the query, since the keys and ordering columns the engine adds count. A
 * nested {@code @Join} or {@code @Child} model with its own {@code @Selected} field fills it from its own row.
 *
 * <p>The type is checked by name: the processor reports {@code MQ3020} when it is not exactly
 * {@code SelectSet<M>}, or the model declares two. This module does not depend on {@code model-query-core}.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Selected {
}
