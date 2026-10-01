package com.rey.modelquery.core;

import java.util.OptionalInt;

/**
 * The most rows a {@code list} returns: a number, possibly zero, or no bound at all. Immutable.
 *
 * @implSpec R-EXE-01, R-EXE-06
 */
public final class Limit {

    private static final Limit UNLIMITED = new Limit(OptionalInt.empty());

    private final OptionalInt max;

    private Limit(OptionalInt max) {
        this.max = max;
    }

    /**
     * At most {@code max} rows; {@code 0} returns an empty list without a query.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a negative {@code max}
     */
    public static Limit of(int max) {
        if (max < 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "Limit.of(" + max + "): a limit must not be negative");
        }
        return new Limit(OptionalInt.of(max));
    }

    /** No {@code maxResults} is applied. */
    public static Limit unlimited() {
        return UNLIMITED;
    }

    /** The bound, empty when unlimited. */
    public OptionalInt maxRows() {
        return max;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Limit other && max.equals(other.max);
    }

    @Override
    public int hashCode() {
        return max.hashCode();
    }

    @Override
    public String toString() {
        return max.isPresent() ? "Limit.of(" + max.getAsInt() + ")" : "Limit.unlimited()";
    }
}
