package com.rey.modelquery.core;


/**
 * When offset paging reads primary keys first and the rows by those keys second. Immutable.
 *
 * @implSpec R-QRY-03
 */
public final class PrimaryKeyFirst {

    private final long offsetThreshold;

    private PrimaryKeyFirst(long offsetThreshold) {
        this.offsetThreshold = offsetThreshold;
    }

    /**
     * Use two-step paging only for an offset strictly above {@code offset}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1204} for a negative {@code offset}
     */
    public static PrimaryKeyFirst whenOffsetAbove(long offset) {
        if (offset < 0) {
            throw new ModelQueryDefinitionException(MqCode.MQ1204,
                    "PrimaryKeyFirst.whenOffsetAbove(" + offset + "): the offset must not be negative");
        }
        return new PrimaryKeyFirst(offset);
    }

    /** The largest offset that still uses one statement. */
    public long offsetThreshold() {
        return offsetThreshold;
    }
}
