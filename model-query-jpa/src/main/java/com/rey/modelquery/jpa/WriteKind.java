package com.rey.modelquery.jpa;

import com.rey.modelquery.annotations.Incubating;

/**
 * Which writes a {@link WriteAssignment} applies to (R-WRT-49): {@code INSERT} for insert-values, insert-select and
 * {@code persist}, {@code UPDATE} for a bulk update, chunked or not, an entity-mode update and the {@code doUpdate}
 * branch of a conflict clause. A delete and {@code doNothing} apply none.
 *
 * @implSpec R-WRT-49
 */
@Incubating
public enum WriteKind {

    /** Insert-values, insert-select and {@code persist}. */
    INSERT,

    /** A bulk or entity-mode update, and the {@code doUpdate} branch of a conflict clause. */
    UPDATE,

    /** Both {@link #INSERT} and {@link #UPDATE}. */
    INSERT_AND_UPDATE
}
