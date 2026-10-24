package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Joins an entity's full id to fields of one joined source, or to columns of the root (R-PROC-46). */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(JoinEntities.class)
public @interface JoinEntity {

    /** @return the join's name, unique among the model's source and entity joins */
    String name();

    /** @return the name of the model's joined source whose fields the pairs read, or empty for a column anchor */
    String source() default "";

    /** @return the entity to join */
    Class<?> entity();

    /** @return the join kind */
    JoinKind type() default JoinKind.LEFT;

    /** @return pairs covering every basic id attribute exactly once */
    On[] on();

    /** @return the static term ANDed into the join's one ON, or empty for none (R-SRC-25) */
    @Incubating
    When[] when() default {};

    /** One field of the joined source, or one column of the root, paired with an entity id attribute. */
    @Incubating
    @Documented
    @Target({})
    @Retention(RetentionPolicy.CLASS)
    @interface On {

        /** @return the source field, or empty for a column anchor */
        String field() default "";

        /** @return the model column over the root or a to-one path from it, or empty for a field anchor */
        @Incubating
        String column() default "";

        /** @return the id attribute path, or empty for the entity's single id */
        String id() default "";
    }

    /** A column over the root or a to-one path from it, fixed to {@code value} in the join's one ON. */
    @Incubating
    @Documented
    @Target({})
    @Retention(RetentionPolicy.CLASS)
    @interface When {

        /** @return the model column over the root or a to-one path from it */
        String column();

        /** @return the value of the column's boxed type, as a bindable constant (R-COL-18) */
        String value();
    }
}
