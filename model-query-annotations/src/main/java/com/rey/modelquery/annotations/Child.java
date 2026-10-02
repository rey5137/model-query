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
     * One path only; a {@code List} child needs it, or {@code through}.
     *
     * @return the path, or none for the child model's {@code @PrimaryKey} attribute
     */
    String[] foreignKey() default {};

    /**
     * The association path from this model's root entity to the child model's, such as {@code "labels"} for a
     * {@code @ManyToMany} the child's entity has no way back from. The child query is then rooted at this model's
     * entity and joined {@code INNER} along the path, the child model resolving under its last join; {@code key} must
     * be this model's root {@code @Id}, and {@code foreignKey} is not set. A customizer of the child model sees this
     * model's entity in {@code query.getRoots()}.
     *
     * @return the path, or {@code ""} for a child matched on its {@code foreignKey}
     */
    String through() default "";
}
