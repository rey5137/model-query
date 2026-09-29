package com.rey.modelquery.core;

/**
 * When offset paging reads primary keys first and the rows by those keys second. Immutable.
 *
 * @implSpec R-QRY-03
 */
@Incubating
public final class PrimaryKeyFirst {

    private final long offsetThreshold;

    private PrimaryKeyFirst(long offsetThreshold) {
        this.offsetThreshold = offsetThreshold;
    }

    /** Use two-step paging only for an offset strictly above {@code offset}. */
    public static PrimaryKeyFirst whenOffsetAbove(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        return new PrimaryKeyFirst(offset);
    }

    /** The largest offset that still uses one statement. */
    public long offsetThreshold() {
        return offsetThreshold;
    }
}
