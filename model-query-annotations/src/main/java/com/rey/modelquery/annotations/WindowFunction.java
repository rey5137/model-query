package com.rey.modelquery.annotations;

/**
 * The function of a {@link Window}, evaluated over a window of the source's rows (R-PROC-36).
 */
@Incubating
public enum WindowFunction {

    /** The 1-based position of the row in its partition, in the window's order. */
    ROW_NUMBER,

    /** The rank of the row in its partition, with ties sharing a rank and leaving gaps. */
    RANK,

    /** The rank of the row in its partition, with ties sharing a rank and no gaps. */
    DENSE_RANK,

    /** The argument of the row one position before this one, in the window's order. */
    LAG,

    /** The argument of the row one position after this one, in the window's order. */
    LEAD,

    /** The argument of the first row of the window frame. */
    FIRST_VALUE,

    /** The argument of the last row of the window frame. */
    LAST_VALUE,

    /** The sum of the argument over the window frame. */
    SUM,

    /** The average of the argument over the window frame. */
    AVG,

    /** The smallest argument over the window frame. */
    MIN,

    /** The largest argument over the window frame. */
    MAX,

    /** The count of the argument's non-null values over the window frame. */
    COUNT
}
