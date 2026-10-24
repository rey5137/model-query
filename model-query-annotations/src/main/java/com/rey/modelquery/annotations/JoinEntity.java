package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Joins an entity's full id to fields of one joined source (R-PROC-46). */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(JoinEntities.class)
public @interface JoinEntity {

    /** @return the join's name, unique among the model's source and entity joins */
    String name();

    /** @return the name of the model's joined source whose fields the pairs read */
    String source();

    /** @return the entity to join */
    Class<?> entity();

    /** @return the join kind */
    JoinKind type() default JoinKind.LEFT;

    /** @return pairs covering every basic id attribute exactly once */
    On[] on();

    /** One field of the joined source paired with an entity id attribute. */
    @Incubating
    @Documented
    @Target({})
    @Retention(RetentionPolicy.CLASS)
    @interface On {

        /** @return the source field */
        String field();

        /** @return the id attribute path, or empty for the entity's single id */
        String id() default "";
    }
}
