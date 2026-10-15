package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * One set operation of a combined source, as {@link SourceQuery#union} and its siblings build it (R-SRC-05,
 * R-PROC-38). A provider renders it through its own set-operation overloads, so the enumeration is the only thing
 * {@code core} and a provider need to agree on.
 *
 * @implSpec R-SRC-05, R-SRC-06
 */
@EngineFacing
@Incubating
public enum SetOperation {

    /** Every row of both branches, with the duplicates of the whole result removed. */
    UNION,

    /** Every row of both branches, duplicates kept. */
    UNION_ALL,

    /** The rows both branches have. */
    INTERSECT,

    /** The rows the left branch has and the right one has not. */
    EXCEPT
}
