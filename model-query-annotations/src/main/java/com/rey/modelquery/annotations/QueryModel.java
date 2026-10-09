package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a model class or record for QModel generation.
 */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface QueryModel {

    /**
     * The root JPA entity the model is read from. It may be in the same compilation or on the classpath.
     *
     * @return the root entity class
     */
    Class<?> root();

    /**
     * Whether to generate {@code SelectSet} constants.
     *
     * @return {@code true} to generate column sets
     */
    boolean generateSelectSets() default true;

    /**
     * Prefix of the generated class name. The {@code -Amodelquery.prefix=} processor option sets it for a whole
     * compilation.
     *
     * @return the class name prefix
     */
    String prefix() default "Q";

    /**
     * Suffix of the generated class name. The {@code -Amodelquery.suffix=} processor option sets it for a whole
     * compilation.
     *
     * @return the class name suffix
     */
    String suffix() default "";

    /**
     * Marks a model that has {@link Aggregate} fields and deliberately no {@link GroupBy} field: a whole-table total.
     *
     * @return {@code true} when the model is one group over the whole table
     */
    boolean singleGroup() default false;

    /**
     * Whether to also generate a change set over the model's root, non-key columns, with {@code changes()},
     * {@code update(...)} and the change set's {@code from(model, columns)}. Meant for internal use: a change set
     * bound from a request can write every root column of the model.
     *
     * @return {@code true} to generate a change set
     */
    @Incubating
    boolean generateChanges() default false;

    /**
     * Whether to also generate {@code INSERT_COLUMNS}, {@code insert(rows)}, {@code insertFrom(sourceRoot)} and
     * {@code persist(row)} over the model's root columns, as an {@link InsertModel} does. Joined, child, computed,
     * selected, transient and filter-only fields, to-one and version columns, columns an insert can't write and a
     * generated id are left out; the generated {@code INSERT_COLUMNS} Javadoc lists each. No foreign key is written,
     * so a model that sets one keeps its own {@link InsertModel}. Meant for internal use: a model bound from a request
     * can write every listed column.
     *
     * @return {@code true} to generate the insert members
     */
    @Incubating
    boolean generateInserts() default false;
}
