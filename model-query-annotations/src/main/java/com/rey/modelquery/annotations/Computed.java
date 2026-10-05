package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a computed expression selection, mapped into the annotated field as an {@code ExpressionField} (R-PROC-21).
 * The field may also carry {@link GroupBy}, and nothing else.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Computed {

    /**
     * The class implementing {@code ExpressionDefinition<Model, FieldType>}, with a public static {@code INSTANCE} or a
     * no-arg constructor visible to the generated model. It is declared as {@code Class<?>} because the interface is in
     * {@code model-query-core}, which this module does not depend on (R-PROC-01).
     *
     * @return the definition class
     */
    Class<?> value();
}
