package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fixes a field of a {@link SourceModel} to a constant, so the branches of a set operation are told apart by value
 * (R-PROC-38). The field is part of the model's {@link PrimaryKey} and renders as its constant in the source's CTE
 * body, never read from the root entity.
 *
 * <p>Every branch of a shape whose key carries a discriminator declares one, and no two branches of one
 * {@code union} or {@code unionAll} share a value ({@code MQ1904}); the values are what makes the combined rows
 * unique by key (R-SRC-06).
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Discriminator {

    /**
     * The value the field always holds, rendered as a literal in the source's CTE body.
     *
     * @return the discriminator value
     */
    String value();
}
