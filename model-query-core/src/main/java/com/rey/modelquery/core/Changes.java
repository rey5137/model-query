package com.rey.modelquery.core;

import java.util.List;

/**
 * A change set: the columns of an update model a caller set, each to a value or to NULL. "Set to NULL" and "not set"
 * are different states and stay different through {@link #assignments()}. A change set is mutable by design, so it is
 * never a {@code static final} constant; an update copies its assignments when given it (R-WRT-05).
 *
 * @param <M> the update model
 * @implSpec R-WRT-02, R-WRT-07, D-60
 */
@Incubating
public interface Changes<M> {

    /** Whether {@code column} was set, to a value or to NULL. */
    boolean isSet(ColumnField<M, ?, ?> column);

    /** Drops {@code column} again, so it is not written; returns this change set. */
    Changes<M> unset(ColumnField<M, ?, ?> column);

    /** Whether no column was set: an update given only this change set writes nothing (R-WRT-07). */
    boolean isEmpty();

    /** The set columns in declaration order, as model values or NULL; a list that throws on mutation. */
    List<Assignment<M, ?>> assignments();
}
