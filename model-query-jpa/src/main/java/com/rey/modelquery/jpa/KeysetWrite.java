package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * The keyset loop that key-first and chunked bulk writes share (D-63). Each round selects the keys the write chooses,
 * then writes exactly those keys: over the definition's own keys a run at a time, else the next {@code n} keys in key
 * order after the last round's, or after the write's {@code startAfter} key. It stops on the number of rows a select
 * returned, never on the rows a write affected, so an update that leaves its rows matching cannot loop, and a delete
 * never re-reads what it removed; the cursor only moves forward because a write never assigns a key column
 * ({@code MQ1605}). With {@code commitEachChunk()} each round, its key select and its write, runs in a new transaction
 * of the configured {@link ChunkTransactions}. Holds no state between runs.
 *
 * @implSpec R-WRT-11, R-WRT-17, R-WRT-19, R-WRT-20, R-PAG-14, D-63
 */
final class KeysetWrite {

    /**
     * One bulk write as the loop runs it.
     *
     * @param label what the write belongs to, for messages
     * @param key the root's primary key, whose columns the key select reads
     * @param distinctKeys the definition's distinct keys, or empty when it chose its rows without keys
     * @param keySelect the key select over a run of {@code distinctKeys}, or over every row the definition chooses
     *     for {@code null}
     * @param write the write, on the given {@code EntityManager}, over a non-empty run of the keys a key select chose
     * @param startAfter the attribute-value key the first key select starts after, or empty to start at the first
     * @param modelKey the model key of an attribute-value key, as {@link ChunkedWriteException} reports keys
     * @param <M> the model whose key columns the key select reads
     */
    record Keyed<M>(Object label, PrimaryKey<M, ?> key, Optional<List<Object>> distinctKeys,
            Function<List<Object>, BuiltQuery<M>> keySelect, BiFunction<EntityManager, List<Object>, Query> write,
            Optional<Object> startAfter, UnaryOperator<Object> modelKey) {}

    /** Creates a key select's statement, told its keyset and cursor (both {@code null} without one, D-82). */
    @FunctionalInterface
    interface Select {
        TypedQuery<Tuple> create(EntityManager on, CriteriaQuery<Tuple> query, Keyset<?> keyset, Object[] cursor);
    }

    private final CriteriaBuilder cb;
    private final Select select;
    private final ToIntFunction<Query> execute;
    private final EntityManager caller;
    /** Runs each round in a new transaction on {@link #emf}, or {@code null} to run them all on the caller's. */
    private final ChunkTransactions transactions;
    private final EntityManagerFactory emf;

    /**
     * @param select creates a key select's query on the given {@code EntityManager}, with the configured timeout
     *     applied
     * @param execute runs a write statement, with the configured timeout applied, and returns the rows it affected
     * @param caller the caller's {@code EntityManager}, which every round runs on without {@code transactions}
     * @param transactions runs each round in a new transaction, for {@code commitEachChunk()}, or {@code null}
     */
    KeysetWrite(CriteriaBuilder cb, Select select,
            ToIntFunction<Query> execute, EntityManager caller, ChunkTransactions transactions) {
        this.cb = cb;
        this.select = select;
        this.execute = execute;
        this.caller = caller;
        this.transactions = transactions;
        this.emf = caller.getEntityManagerFactory();
    }

    /**
     * Runs {@code write} in rounds of at most {@code n} keys and returns the summed rows affected. With
     * {@code lockKeys} each key select takes {@code LockModeType.PESSIMISTIC_WRITE}, so a concurrent change to a
     * selected row waits for the write (R-WRT-11).
     *
     * @throws ModelQueryExecutionException {@code MQ2205} when a key select returns a key the round before it wrote
     * @throws ChunkedWriteException {@code MQ2502} when a round fails with {@code commitEachChunk()}: the rounds
     *     before it stay committed (R-WRT-20)
     */
    <M> long run(Keyed<M> write, int n, boolean lockKeys) {
        var rounds = new Rounds<>(write);
        if (write.distinctKeys().isPresent()) {
            List<Object> all = write.distinctKeys().get();
            for (int from = 0; from < all.size(); from += n) {
                List<Object> run = all.subList(from, Math.min(all.size(), from + n));
                rounds.run(on -> {
                    // The run bounds the keys, so the select needs no limit and no cursor.
                    BuiltQuery<M> built = write.keySelect().apply(run);
                    List<Tuple> rows = rows(on, built, 0, lockKeys, null, null, null);
                    Set<Object> distinct = keysOf(write, built, rows, Set.of());
                    List<Object> keys = new ArrayList<>(distinct);
                    return new Round(keys, distinct, rows.size(), null, writeKeys(on, write, keys));
                }, run.get(run.size() - 1));
            }
            return rounds.written;
        }
        Keyset<M> keyset = Keyset.ofKey(write.key());
        // The last key selected and the round's keys: the only state carried from round to round, bounded by n.
        Object[] cursor = write.startAfter().map(KeysetWrite::cursorOf).orElse(null);
        Set<Object> previous = Set.of();
        while (true) {
            Object[] after = cursor;
            Set<Object> before = previous;
            Round round = rounds.run(on -> {
                BuiltQuery<M> built = write.keySelect().apply(null);
                Keyset.Beyond past = null;
                if (after != null) {
                    past = keyset.after(after, built.joins(), cb);
                    Predicate own = built.query().getRestriction();
                    built.query().where(own == null ? past.predicate() : cb.and(own, past.predicate()));
                }
                keyset.appendOrder(built, cb);
                List<Tuple> rows = rows(on, built, n, lockKeys, keyset, after, past);
                if (rows.isEmpty()) {
                    return Round.NONE;
                }
                Set<Object> distinct = keysOf(write, built, rows, before);
                List<Object> keys = new ArrayList<>(distinct);
                Object[] next = keyset.cursor(built.selection().row(rows.get(rows.size() - 1)));
                return new Round(keys, distinct, rows.size(), next, writeKeys(on, write, keys));
            });
            if (round.selected() < n) {
                return rounds.written;
            }
            cursor = round.next();
            previous = round.keySet();
        }
    }

    /** The rows of {@code built}, at most {@code max} unless it is zero, locked with {@code lockKeys}. */
    private List<Tuple> rows(EntityManager on, BuiltQuery<?> built, int max, boolean lockKeys, Keyset<?> keyset,
            Object[] cursor, Keyset.Beyond beyond) {
        TypedQuery<Tuple> query = select.create(on, built.query(), keyset, cursor);
        if (beyond != null) {
            beyond.bindTo(query);
        }
        if (max > 0) {
            query.setMaxResults(max);
        }
        if (lockKeys) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return query.getResultList();
    }

    /**
     * The distinct keys of {@code rows}, in their order: a predicate's to-many join repeats a root's key within a
     * select, and that repeat is dropped (R-PAG-02).
     *
     * @throws ModelQueryExecutionException {@code MQ2205} for a key of {@code previous}, the round before: a cursor
     *     value did not survive being bound, which can skip rows as well as repeat them (R-PAG-14)
     */
    private static <M> Set<Object> keysOf(Keyed<M> write, BuiltQuery<M> built, List<Tuple> rows,
            Set<Object> previous) {
        Set<Object> keys = new LinkedHashSet<>();
        for (Tuple tuple : rows) {
            Row row = built.selection().row(tuple);
            Object key = Keys.keyOf(write.label(), write.key(), row);
            if (previous.contains(key)) {
                throw new ModelQueryExecutionException(MqCode.MQ2205, write.label() + ": a bulk write's key select "
                        + "returned key " + key + ", which the round before already wrote: a key value did not "
                        + "survive being bound");
            }
            keys.add(key);
        }
        return keys;
    }

    private <M> long writeKeys(EntityManager on, Keyed<M> write, List<Object> keys) {
        return keys.isEmpty() ? 0 : execute.applyAsInt(write.write().apply(on, keys));
    }

    /** A cursor of the key values of {@code attributeKey}, as {@link Keys#keyOf} returns a key. */
    private static Object[] cursorOf(Object attributeKey) {
        return attributeKey instanceof List<?> components ? components.toArray() : new Object[] {attributeKey};
    }

    /**
     * What one round selected and wrote.
     *
     * @param keys the distinct keys selected, in the select's order
     * @param keySet the same keys, which the next round checks its own against; not changed after the round
     * @param selected the rows the key select returned, a repeated key included
     * @param next the cursor after the round's last key, or {@code null} for a round over a run of keys
     * @param written the rows the write affected
     */
    private record Round(List<Object> keys, Set<Object> keySet, int selected, Object[] next, long written) {

        static final Round NONE = new Round(List.of(), Set.of(), 0, null, 0);
    }

    /**
     * The rounds of one run: each on the caller's {@code EntityManager}, or with {@code commitEachChunk()} each in a
     * new transaction, counting what the committed ones wrote (R-WRT-19, R-WRT-20).
     */
    private final class Rounds<M> {

        private final Keyed<M> write;
        private long written;
        private int committed;
        /** The last key of the last committed round that selected any, as an attribute value. */
        private Object lastKey;

        Rounds(Keyed<M> write) {
            this.write = write;
        }

        Round run(Function<EntityManager, Round> body) {
            return run(body, null);
        }

        /**
         * Runs a round, then records {@code lastOfRun} as the last committed key when it is not {@code null}: a run of
         * the caller's keys is written in the order given, so its last key, not the last the select returned, is
         * where to resume, and a run that matched no row still passed its keys (D-73).
         */
        Round run(Function<EntityManager, Round> body, Object lastOfRun) {
            if (transactions == null) {
                Round round = body.apply(caller);
                written += round.written();
                return round;
            }
            Round[] ran = new Round[1];
            try {
                transactions.inNewTransaction(emf, on -> ran[0] = body.apply(on));
            } catch (RuntimeException e) {
                throw failed(ran[0], e);
            }
            if (ran[0] == null) {
                throw failed(null, new IllegalStateException(
                        "ChunkTransactions.inNewTransaction returned without running the chunk"));
            }
            Round round = ran[0];
            written += round.written();
            committed++;
            if (lastOfRun != null) {
                lastKey = lastOfRun;
            } else if (!round.keys().isEmpty()) {
                lastKey = round.keys().get(round.keys().size() - 1);
            }
            return round;
        }

        /**
         * The exception for a chunk that threw {@code cause}: when the chunk itself ran, {@code ran}, its commit
         * failed, so whether its keys were written is unknown; otherwise it rolled back.
         */
        private ChunkedWriteException failed(Round ran, RuntimeException cause) {
            List<Object> inDoubt = ran == null ? List.of() : ran.keys().stream().map(write.modelKey()).toList();
            Object last = lastKey == null ? null : write.modelKey().apply(lastKey);
            String detail = write.label() + ": chunk " + (committed + 1) + " of a bulk write committing each chunk "
                    + "failed; the " + committed + " chunks before it stay committed, " + written + " rows"
                    + (last == null ? "" : ", up to key " + last)
                    + (inDoubt.isEmpty() ? ", and the failed chunk rolled back"
                            : ", and the failed chunk's commit threw, so whether its " + inDoubt.size()
                                    + " keys were written is unknown: " + inDoubt);
            return new ChunkedWriteException(detail, written, last, inDoubt, cause);
        }
    }
}
