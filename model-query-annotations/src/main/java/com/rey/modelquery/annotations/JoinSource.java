package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Joins a unique {@link SourceModel} to a query model rooted at an entity, on the source's full key (R-SRC-21,
 * R-PROC-44). The generated {@code query(...)} takes the source's {@code SourceQuery}, and the model reads the joined
 * source's fields with {@link SourceColumn}. Several joins are allowed, each with its own {@link #name()}.
 */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(JoinSources.class)
public @interface JoinSource {

    /**
     * The join's name, a Java identifier unique in the model: the generated {@code SourceJoin} constant and the
     * {@code query(...)} parameter, and what {@link SourceColumn#join()} names.
     *
     * @return the name
     */
    String name();

    /**
     * The {@link SourceModel} joined.
     *
     * @return the source model
     */
    Class<?> source();

    /**
     * {@code LEFT} keeps an outer row with no source row, reading its joined columns as NULL; {@code INNER} drops it.
     *
     * @return the join kind
     */
    JoinKind type() default JoinKind.LEFT;

    /**
     * The pairs joining the source's key: each of its key fields exactly once.
     *
     * @return the pairs
     */
    On[] on();

    /** One pair of the join: an outer column and one key field of the source, compared by {@code =}. */
    @Incubating
    @Documented
    @Target({})
    @Retention(RetentionPolicy.CLASS)
    @interface On {

        /**
         * The outer column: an attribute path of the model's root, through to-one associations as
         * {@code @Column.attribute} allows.
         *
         * @return the attribute path
         */
        String column();

        /**
         * The source's key field.
         *
         * @return the field name
         */
        String key();
    }
}
