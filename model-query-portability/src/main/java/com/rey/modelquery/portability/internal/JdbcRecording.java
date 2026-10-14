package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The JDBC access of one boot or one call, recorded by the stub data source on the thread that opened it. The
 * recording, not the exception a provider throws, decides whether JDBC was reached: a provider may swallow the stub's
 * failure. Opening one replaces the thread's current recording until it closes; closing one out of order, or twice,
 * never revives a closed recording, and a closed recording, even one closed on another thread, records nothing more.
 *
 * @implSpec R-INS-10
 */
@Incubating
public final class JdbcRecording implements AutoCloseable {

    /** Whether the stub data source hands out a connection: only to a call, never to a boot. */
    public enum Phase {
        BOOT,
        CALL
    }

    private static final ThreadLocal<JdbcRecording> CURRENT = new ThreadLocal<>();

    private final Phase phase;
    private final JdbcRecording previous;
    private final List<String> accesses = new ArrayList<>();
    private final List<String> statements = new ArrayList<>();
    private final List<String> deniedBeforeStatement = new ArrayList<>();
    private volatile boolean closed;

    private JdbcRecording(Phase phase) {
        this.phase = phase;
        this.previous = CURRENT.get();
        CURRENT.set(this);
    }

    /** Opens the recording of a boot on this thread: any connection is denied and recorded. */
    public static JdbcRecording boot() {
        return new JdbcRecording(Phase.BOOT);
    }

    /** Opens the recording of a call on this thread: a connection is handed out, and its statements recorded. */
    public static JdbcRecording call() {
        return new JdbcRecording(Phase.CALL);
    }

    /** This thread's open recording, or empty. */
    static Optional<JdbcRecording> current() {
        return Optional.ofNullable(open(CURRENT.get()));
    }

    /** {@code recording}, or the nearest recording it replaced, that is still open; null for none. */
    private static JdbcRecording open(JdbcRecording recording) {
        JdbcRecording open = recording;
        while (open != null && open.closed) {
            open = open.previous;
        }
        return open;
    }

    /** The phase. */
    public Phase phase() {
        return phase;
    }

    /** Every JDBC access the stub recorded, in order: a denied call or a statement reached. */
    public List<String> accesses() {
        return Collections.unmodifiableList(new ArrayList<>(accesses));
    }

    /** The SQL of each statement reached, in order. */
    public List<String> statements() {
        return Collections.unmodifiableList(new ArrayList<>(statements));
    }

    /** The denied calls recorded before the first statement, which a call's classification cannot account for. */
    List<String> deniedBeforeStatement() {
        return List.copyOf(deniedBeforeStatement);
    }

    void denied(String call) {
        accesses.add(call);
        if (statements.isEmpty()) {
            deniedBeforeStatement.add(call);
        }
    }

    void statement(String call, String sql) {
        accesses.add(call + ": " + sql);
        statements.add(sql);
    }

    /** Restores the open recording this one replaced, if this one is the thread's current. */
    @Override
    public void close() {
        closed = true;
        if (CURRENT.get() != this) {
            // Closed out of order or on another thread: current() skips it, and the inner one's close restores past it.
            return;
        }
        JdbcRecording restored = open(previous);
        if (restored == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(restored);
        }
    }
}
