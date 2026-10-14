package com.rey.modelquery.core;

/**
 * How an executor answers whether the to-one attribute of an entity, read by its target's id in a write, renders as
 * the entity's own foreign-key column with no join (R-COL-29, D-136). Core states it with its own answers so it
 * depends on no provider (INV-7).
 */
@EngineFacing
@FunctionalInterface
public interface ForeignKeyLookup {

    /** What the executor knows of {@code toOne}, an attribute of {@code entity}. */
    Answer answer(Class<?> entity, String toOne);

    /** The executor's answer for one to-one attribute. */
    enum Answer {

        /** The id renders as a column of the entity's own table, with no join, referencing the target's primary key. */
        LOCAL_COLUMN,

        /** The id renders through a join, a formula or another table. */
        JOINS,

        /** The executor cannot tell. */
        UNKNOWN
    }
}
