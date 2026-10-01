package com.rey.modelquery.core;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * How a chunked bulk write runs: the keys selected per chunk, whether each chunk commits on its own, and whether the
 * key select locks the rows it reads. Immutable. The key to resume after is given with them, on the write's builder,
 * which knows the key's type (D-64).
 *
 * @param size the keys per chunk, positive when present; empty leaves it to the executor's configured default; either
 *     way clamped to the vendor's limits
 * @param commitsEachChunk whether each chunk runs in a new transaction, see {@link #commitEachChunk()}
 * @param locksKeys whether keys are selected with a pessimistic write lock, see {@link #lockKeys()}
 * @implSpec R-WRT-11, R-WRT-17, R-WRT-19, D-62
 */
@Incubating
public record ChunkOptions(OptionalInt size, boolean commitsEachChunk, boolean locksKeys) {

    private static final ChunkOptions DEFAULTS = new ChunkOptions(OptionalInt.empty(), false, false);

    /**
     * Validates the components.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a size that is not positive
     */
    public ChunkOptions {
        Objects.requireNonNull(size, "size");
        if (size.isPresent() && size.getAsInt() <= 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "ChunkOptions.size " + size.getAsInt()
                    + " is not positive");
        }
    }

    /** The executor's configured chunk size, in the caller's transaction, without locking (D-62). */
    public static ChunkOptions defaultSize() {
        return DEFAULTS;
    }

    /**
     * Chunks of {@code size} keys, in the caller's transaction, without locking.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a size that is not positive
     */
    public static ChunkOptions size(int size) {
        return new ChunkOptions(OptionalInt.of(size), false, false);
    }

    /**
     * A copy that runs each chunk in a new transaction, through the configured {@code ChunkTransactions}, which keeps
     * locks and undo logs short at the cost of atomicity: chunks already committed stay committed when a later one
     * fails, and a failed chunk throws {@link ChunkedWriteException} ({@code MQ2502}). Meant to run outside a
     * transaction, since rows the caller's flush locked wait until it ends. With no {@code ChunkTransactions}
     * configured, or one that cannot serve the write's factory, the write throws {@code MQ4004} before any statement
     * (R-WRT-19, R-WRT-20).
     */
    public ChunkOptions commitEachChunk() {
        return new ChunkOptions(size, true, locksKeys);
    }

    /**
     * A copy that selects each chunk's keys with {@code PESSIMISTIC_WRITE}, so a concurrent change to a selected row
     * waits for the write; on MySQL the select then also reads current rows rather than the transaction's snapshot.
     */
    public ChunkOptions lockKeys() {
        return new ChunkOptions(size, commitsEachChunk, true);
    }
}
