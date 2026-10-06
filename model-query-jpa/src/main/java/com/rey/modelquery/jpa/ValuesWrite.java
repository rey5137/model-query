package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ChunkedWriteException;
import jakarta.persistence.EntityManager;

/**
 * The statement loop of an insert-values: its rows in list order, in runs of at most {@code n} rows, one statement
 * each, on the caller's {@code EntityManager}, or with {@code commitEachChunk()} each in a new transaction of the
 * configured {@link ChunkTransactions}, reporting by row position where to resume when one fails (R-WRT-29,
 * R-WRT-32). Holds no state between runs.
 */
final class ValuesWrite {

    /** One statement over rows {@code from} (inclusive) to {@code to} (exclusive), run on {@code on}. */
    @FunctionalInterface
    interface Statement {

        /** Writes the rows and returns the rows the statement affected. */
        int write(EntityManager on, int from, int to);
    }

    private final String label;
    private final EntityManager caller;
    private final ChunkTransactions transactions;

    /**
     * @param label the written entity, for the failure's message
     * @param caller the caller's {@code EntityManager}, which runs every statement without {@code transactions}
     * @param transactions runs each statement in a new transaction, for {@code commitEachChunk()}, or {@code null}
     */
    ValuesWrite(String label, EntityManager caller, ChunkTransactions transactions) {
        this.label = label;
        this.caller = caller;
        this.transactions = transactions;
    }

    /**
     * Writes rows 0 to {@code rows} in runs of at most {@code n} and returns the summed rows affected.
     *
     * @throws ChunkedWriteException {@code MQ2502} when a run fails with {@code commitEachChunk()}: the runs before it
     *     stay committed, {@code nextRowIndex()} is the failed run's first row, and {@code inDoubtRowCount()} its rows
     *     when its commit threw, else 0 (R-WRT-20, R-WRT-32)
     */
    long run(int rows, int n, Statement statement) {
        long written = 0;
        int committed = 0;
        // Each run ends at start + min(n, rows left), never start + n, which overflows for a profile without limits.
        for (int from = 0; from < rows; from += Math.min(n, rows - from)) {
            int start = from;
            int end = start + Math.min(n, rows - start);
            if (transactions == null) {
                written += statement.write(caller, start, end);
                continue;
            }
            boolean[] ran = new boolean[1];
            Integer result;
            try {
                result = transactions.inNewTransaction(caller.getEntityManagerFactory(), on -> {
                    int affected = statement.write(on, start, end);
                    ran[0] = true;
                    return affected;
                });
            } catch (RuntimeException e) {
                throw failed(committed, written, start, ran[0] ? end - start : 0, e);
            }
            if (!ran[0]) {
                throw failed(committed, written, start, 0, new IllegalStateException(
                        "ChunkTransactions.inNewTransaction returned without running the chunk"));
            }
            written += result;
            committed++;
        }
        return written;
    }

    /**
     * The exception for the run from row {@code next} that threw {@code cause}: {@code inDoubt} rows when the run
     * itself completed, so its commit failed and whether they were written is unknown, else 0, as it rolled back.
     */
    private ChunkedWriteException failed(int committed, long written, int next, int inDoubt, RuntimeException cause) {
        String detail = label + ": chunk " + (committed + 1) + " of a bulk insert committing each chunk failed; the "
                + committed + " chunks before it stay committed, " + written + " rows, and the next row is " + next
                + (inDoubt == 0 ? ", as the failed chunk rolled back"
                        : ", but the failed chunk's commit threw, so whether its " + inDoubt + " rows were written is "
                                + "unknown");
        return new ChunkedWriteException(detail, written, next, inDoubt, cause);
    }
}
