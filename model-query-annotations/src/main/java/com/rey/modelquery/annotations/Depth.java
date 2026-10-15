package com.rey.modelquery.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field of a {@link SourceModel} as the recursion level of a recursive source (R-PROC-37, R-SRC-07): the seed
 * rows are level 0, each step of the recursion adds 1, and the field carries the level the CTE assigned the row. The
 * field is an {@code Integer}, there is one per model, and a model carrying it is <em>recursive</em>.
 *
 * <p>A recursive model's {@code source()} requires the step before it is built: the builder's
 * {@code recursive(step -> step.childKey(...).parentKey(...), maxDepth)} names the two key columns and the depth
 * limit, and the {@code where(...)} predicate selects the seed rows. A recursive model takes no {@link Window}, no
 * {@link Aggregate} and no {@link GroupBy} ({@code MQ3037}), because the database forbids them in the recursive term.
 */
@Incubating
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.CLASS)
public @interface Depth {
}
