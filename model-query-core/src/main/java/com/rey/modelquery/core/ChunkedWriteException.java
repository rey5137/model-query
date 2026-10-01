package com.rey.modelquery.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
 * @implSpec R-WRT-20, D-63
 */
@Incubating
public class ChunkedWriteException extends ModelQueryExecutionException {

    private static final long serialVersionUID = 1L;

    private final long committedRows;
    private final Object lastCommittedKey;
    private final List<Object> inDoubtKeys;

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
    }

    /** The rows the committed chunks wrote; a chunk in doubt is not counted. */
    public long committedRows() {
        return committedRows;
    }

    /** The last key the last committed chunk wrote, to resume after; empty when no chunk committed. */
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
}
