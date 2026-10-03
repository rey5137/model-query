package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * Which keyset page to read: the first page, the page after a cursor, or the page before one, each of a given size.
 * The cursor is opaque: {@link #after(String, int)} and {@link #before(String, int)} decode it when the spec is built,
 * so a null, blank or malformed cursor throws {@code MQ2208} there and never reaches the database (R-PAG-16, R-PAG-18).
 * Immutable.
 *
 * @implSpec R-PAG-16, R-PAG-17, R-PAG-18
 */
@Incubating
public final class KeysetSpec {

    /** Where a page starts relative to its cursor. */
    public enum Direction {
        /** The first page: no cursor. */
        FIRST,
        /** The page after the cursor's boundary row. */
        AFTER,
        /** The page before the cursor's boundary row. */
        BEFORE
    }

    private static final Object[] NO_VALUES = new Object[0];
    private static final byte[] NO_FINGERPRINT = new byte[0];

    private final Direction direction;
    private final int size;
    private final byte[] fingerprint;
    private final Object[] values;

    private KeysetSpec(Direction direction, int size, byte[] fingerprint, Object[] values) {
        this.direction = direction;
        this.size = size;
        this.fingerprint = fingerprint;
        this.values = values;
    }

    /**
     * The first page of {@code size} rows.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a size outside {@code 1..Integer.MAX_VALUE - 1}
     */
    public static KeysetSpec first(int size) {
        requireSize(size);
        return new KeysetSpec(Direction.FIRST, size, NO_FINGERPRINT, NO_VALUES);
    }

    /**
     * The {@code size} rows after the boundary row {@code cursor} names.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a size outside {@code 1..Integer.MAX_VALUE - 1},
     *     {@code MQ2208} for a null, blank or malformed cursor (R-PAG-16, R-PAG-18)
     */
    public static KeysetSpec after(String cursor, int size) {
        requireSize(size);
        return withCursor(Direction.AFTER, cursor, size);
    }

    /**
     * The {@code size} rows before the boundary row {@code cursor} names.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a size outside {@code 1..Integer.MAX_VALUE - 1},
     *     {@code MQ2208} for a null, blank or malformed cursor (R-PAG-16, R-PAG-18)
     */
    public static KeysetSpec before(String cursor, int size) {
        requireSize(size);
        return withCursor(Direction.BEFORE, cursor, size);
    }

    private static KeysetSpec withCursor(Direction direction, String cursor, int size) {
        KeysetCursorCodec.Decoded decoded = KeysetCursorCodec.decode(cursor);
        return new KeysetSpec(direction, size, decoded.fingerprint(), decoded.values());
    }

    private static void requireSize(int size) {
        if (size < 1 || size > Integer.MAX_VALUE - 1) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "keyset page size " + size + " is not in 1.."
                    + (Integer.MAX_VALUE - 1) + "; the engine reads one row more than the page");
        }
    }

    /** Where the page starts: the first page, after a cursor or before one (R-PAG-16). */
    @EngineFacing
    public Direction direction() {
        return direction;
    }

    /** The most rows in the page, always in {@code 1..Integer.MAX_VALUE - 1}. */
    @EngineFacing
    public int size() {
        return size;
    }

    /** Whether a cursor was given, so {@link #fingerprint()} and {@link #values()} are meaningful. */
    @EngineFacing
    public boolean hasCursor() {
        return direction != Direction.FIRST;
    }

    /** The decoded order fingerprint of the cursor, 8 bytes, or empty for the first page. */
    @EngineFacing
    public byte[] fingerprint() {
        return fingerprint.clone();
    }

    /** The decoded values of the cursor, in keyset order; empty for the first page. */
    @EngineFacing
    public Object[] values() {
        return values.clone();
    }

    /** The direction, size and whether a cursor was given; never its values. */
    @Override
    public String toString() {
        return "KeysetSpec[" + direction + " size=" + size + (hasCursor() ? " cursor" : "") + "]";
    }
}
