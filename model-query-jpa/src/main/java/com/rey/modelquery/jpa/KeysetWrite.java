package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * The keyset loop that key-first and chunked bulk writes share (D-63). Each round selects the keys the write chooses,
 * then writes exactly those keys: over the definition's own keys a run at a time, else the next {@code n} keys in key
 * order after the last round's. It stops on the number of rows a select returned, never on the rows a write affected,
 * so an update that leaves its rows matching cannot loop, and a delete never re-reads what it removed; the cursor
 * only moves forward because a write never assigns a key column ({@code MQ1605}). Holds no state between runs.
 *
 * @implSpec R-WRT-11, R-WRT-17, R-PAG-14, D-63
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
     * @param write the write over a non-empty run of the keys a key select chose
     * @param <M> the model whose key columns the key select reads
     */
    record Keyed<M>(Object label, PrimaryKey<M, ?> key, Optional<List<Object>> distinctKeys,
            Function<List<Object>, BuiltQuery<M>> keySelect, Function<List<Object>, Query> write) {}

    private final CriteriaBuilder cb;
    private final Function<CriteriaQuery<Tuple>, TypedQuery<Tuple>> select;
    private final ToIntFunction<Query> execute;

    /**
     * @param select creates a key select's query, with the configured timeout applied
     * @param execute runs a write statement, with the configured timeout applied, and returns the rows it affected
     */
    KeysetWrite(CriteriaBuilder cb, Function<CriteriaQuery<Tuple>, TypedQuery<Tuple>> select,
            ToIntFunction<Query> execute) {
        this.cb = cb;
        this.select = select;
        this.execute = execute;
    }

    /**
     * Runs {@code write} in rounds of at most {@code n} keys and returns the summed rows affected. With
     * {@code lockKeys} each key select takes {@code LockModeType.PESSIMISTIC_WRITE}, so a concurrent change to a
     * selected row waits for the write (R-WRT-11).
     *
     * @throws ModelQueryExecutionException {@code MQ2205} when a key select returns a key the round before it wrote
     */
    <M> long run(Keyed<M> write, int n, boolean lockKeys) {
        if (write.distinctKeys().isPresent()) {
            List<Object> all = write.distinctKeys().get();
            long written = 0;
            for (int from = 0; from < all.size(); from += n) {
                // The run bounds the keys, so the select needs no limit and no cursor.
                BuiltQuery<M> built = write.keySelect().apply(all.subList(from, Math.min(all.size(), from + n)));
                written += writeKeys(write, keysOf(write, built, rows(built, 0, lockKeys), Set.of()));
            }
            return written;
        }
        Keyset<M> keyset = Keyset.ofKey(write.key());
        long written = 0;
        // The last key selected and the round's keys: the only state carried from round to round, bounded by n.
        Object[] cursor = null;
        Set<Object> previous = Set.of();
        while (true) {
            BuiltQuery<M> built = write.keySelect().apply(null);
            if (cursor != null) {
                Predicate after = keyset.after(cursor, built.joins(), cb);
                Predicate own = built.query().getRestriction();
                built.query().where(own == null ? after : cb.and(own, after));
            }
            keyset.appendOrder(built, cb);
            List<Tuple> rows = rows(built, n, lockKeys);
            if (rows.isEmpty()) {
                return written;
            }
            Set<Object> keys = keysOf(write, built, rows, previous);
            cursor = keyset.cursor(built.selection().row(rows.get(rows.size() - 1)));
            written += writeKeys(write, keys);
            if (rows.size() < n) {
                return written;
            }
            previous = keys;
        }
    }

    /** The rows of {@code built}, at most {@code max} unless it is zero, locked with {@code lockKeys}. */
    private List<Tuple> rows(BuiltQuery<?> built, int max, boolean lockKeys) {
        TypedQuery<Tuple> query = select.apply(built.query());
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

    private <M> long writeKeys(Keyed<M> write, Set<Object> keys) {
        return keys.isEmpty() ? 0 : execute.applyAsInt(write.write().apply(new ArrayList<>(keys)));
    }
}
