package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * Where NULLs sort. {@link #DEFAULT} renders no null clause and so is whatever the database does (R-COL-12).
 *
 * @implSpec R-COL-12
 */
@Incubating
public enum NullPrecedence {
    /** No null clause: the database's own order. */
    DEFAULT,
    /** NULLs before every value, on every vendor. */
    FIRST,
    /** NULLs after every value, on every vendor. */
    LAST
}
