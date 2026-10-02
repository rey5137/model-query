package com.rey.modelquery.annotations;

/**
 * How {@link Join} or {@link FilterColumn} joins an association. It stands in for the JPA join type, which this module
 * cannot name because it depends on nothing but the JDK.
 */
@Incubating
public enum JoinKind {

    /** An inner join. */
    INNER,

    /** A left outer join. */
    LEFT
}
