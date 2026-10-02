package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fills the field from another {@code @QueryModel}'s rows, whose {@code foreignKey} equals this model's {@code key},
 * when a fetch plan names it. The field is a {@code List<ChildModel>} (to-many) or an {@code Optional<ChildModel>}
 * (to-one), and holds {@code List.of()} or {@code Optional.empty()} until the child is loaded.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Child {

    /**
     * The attribute path on this model's root entity that the child's {@code foreignKey} equals. One path only.
     *
     * @return the path, or none for this model's {@code @PrimaryKey} attribute
     */
    String[] key() default {};

    /**
     * The attribute path on the child model's root entity matched against {@code key}, such as {@code "order.id"}.
     * One path only; a {@code List} child needs it.
     *
     * @return the path, or none for the child model's {@code @PrimaryKey} attribute
     */
    String[] foreignKey() default {};
}
