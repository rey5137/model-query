package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Raised as {@code MQ2502} when a chunk of a {@code commitEachChunk()} bulk write fails, with the provider's exception
 * as the cause, even when the first chunk fails. The chunks committed before it stay committed: the write is not
 * atomic, so the table holds their rows written and the rest not (INV-5).
 *
 * <p>The keys are model keys, the type {@code whereKey} takes. Resuming after {@link #lastCommittedKey()} with
 * {@code chunked(options, startAfter)} writes the rest. Re-running the whole write is safe only when it is idempotent:
 * {@code total * 1.1} would apply again to the rows already committed. The same holds for resuming after a chunk in
 * doubt, which may in fact have committed: a caller whose write is not idempotent checks {@link #inDoubtKeys()}
 * against the table first, and resumes after the last of them if that chunk did commit.
 *
 * <p>For a {@code whereKey} or {@code whereKeys} write, {@link #lastCommittedKey()} is the last key of the last
 * committed run in the order given, after deduplication: every key up to and including it, in that order, was written
 * or matched no row. For an insert-select the keys are source-root ids.
 *
 * <p>An insert-values carries row positions instead of keys: {@link #nextRowIndex()} is the first row not committed,
 * and {@link #inDoubtRowCount()} the rows from it whose chunk's commit failed. Resume with
 * {@code rows.subList(nextRowIndex, rows.size())}, after checking the rows in doubt against the table (R-WRT-32).
 *
 * @implSpec R-WRT-20, R-WRT-32, D-63, D-73, D-117
 */
@Incubating
public class ChunkedWriteException extends ModelQueryExecutionException {

    private static final long serialVersionUID = 1L;

    private final long committedRows;
    private final Object lastCommittedKey;
    private final List<Object> inDoubtKeys;
    /** An insert-values write's first row not committed, or -1: {@code OptionalInt} is not serializable. */
    private final int nextRowIndex;
    private final int inDoubtRowCount;

    /**
     * Creates an exception whose message is the code followed by {@code detail}.
     *
     * @param committedRows the rows the committed chunks wrote
     * @param lastCommittedKey the last key the last committed chunk wrote, or {@code null} when none committed
     * @param inDoubtKeys the keys of the chunk whose commit failed, or empty when the chunk failed before its commit
     * @param cause the provider's exception
     */
    public ChunkedWriteException(String detail, long committedRows, Object lastCommittedKey,
            List<?> inDoubtKeys, Throwable cause) {
        super(MqCode.MQ2502, detail, cause);
        this.committedRows = committedRows;
        this.lastCommittedKey = lastCommittedKey;
        this.inDoubtKeys = Collections.unmodifiableList(new ArrayList<>(inDoubtKeys));
        this.nextRowIndex = -1;
        this.inDoubtRowCount = 0;
    }

    /**
     * Creates the exception of an insert-values write, whose message is the code followed by {@code detail}.
     *
     * @param committedRows the rows the committed chunks affected (R-WRT-35)
     * @param nextRowIndex the position of the first row not committed, not negative
     * @param inDoubtRowCount the rows from {@code nextRowIndex} whose chunk's commit failed, or 0 when the chunk failed
     *     before its commit
     * @param cause the provider's exception
     * @throws IllegalArgumentException for a negative {@code nextRowIndex} or {@code inDoubtRowCount}
     */
    public ChunkedWriteException(String detail, long committedRows, int nextRowIndex, int inDoubtRowCount,
            Throwable cause) {
        super(MqCode.MQ2502, detail, cause);
        if (nextRowIndex < 0 || inDoubtRowCount < 0) {
            throw new IllegalArgumentException("nextRowIndex " + nextRowIndex + " and inDoubtRowCount "
                    + inDoubtRowCount + " must not be negative");
        }
        this.committedRows = committedRows;
        this.lastCommittedKey = null;
        this.inDoubtKeys = List.of();
        this.nextRowIndex = nextRowIndex;
        this.inDoubtRowCount = inDoubtRowCount;
    }

    /** The rows the committed chunks wrote; a chunk in doubt is not counted. */
    public long committedRows() {
        return committedRows;
    }

    /**
     * The last key the last committed chunk wrote, to resume after; empty when no chunk committed. For a
     * {@code whereKey} or {@code whereKeys} write, the last key of the last committed run in the order given.
     */
    public Optional<Object> lastCommittedKey() {
        return Optional.ofNullable(lastCommittedKey);
    }

    /**
     * The keys of the chunk whose commit itself failed, whose outcome is therefore unknown; empty when the chunk
     * failed before its commit and rolled back.
     */
    public List<Object> inDoubtKeys() {
        return inDoubtKeys;
    }

    /**
     * For an insert-values write, the position in the call's rows of the first row not committed, to resume from;
     * empty for every other write, which resumes after {@link #lastCommittedKey()}.
     */
    public OptionalInt nextRowIndex() {
        return nextRowIndex < 0 ? OptionalInt.empty() : OptionalInt.of(nextRowIndex);
    }

    /**
     * For an insert-values write, the rows of the chunk whose commit itself failed, contiguous from
     * {@link #nextRowIndex()}, whose outcome is therefore unknown; 0 when the chunk rolled back, and for every other
     * write, which lists {@link #inDoubtKeys()} instead.
     */
    public int inDoubtRowCount() {
        return inDoubtRowCount;
    }
}
