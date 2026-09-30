package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Joins an association and reuses the nested model's columns. The field or component is an
 * {@code Optional<NestedModel>}.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Join {

    /**
     * The association attribute on the root entity, when it is not the field's own name.
     *
     * @return the attribute, or {@code ""} for the field's name
     */
    String attribute() default "";

    /**
     * How the association is joined.
     *
     * @return the join kind
     */
    JoinKind type() default JoinKind.LEFT;

    /**
     * Prefix of the constants generated for the nested model's columns.
     *
     * @return the constant prefix, or {@code ""} for one derived from the field's name
     */
    String prefix() default "";

    /**
     * Alias of the join. Two joins on the same attribute with different aliases are two joins.
     *
     * @return the alias, or {@code ""} for none
     */
    String alias() default "";
}
