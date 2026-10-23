package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reads a field of a source joined by {@link JoinSource} into the annotated field (R-PROC-44). The field's type is the
 * source field's, and a joined column is nullable under a {@code LEFT} join. It takes the source field's converter, so
 * a {@code @Column(converter)} beside it is refused.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface SourceColumn {

    /**
     * The {@link JoinSource#name()} of the join.
     *
     * @return the join name
     */
    String join();

    /**
     * The source model's field to read.
     *
     * @return the field name
     */
    String field();
}
