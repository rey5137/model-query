package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Caller code a {@link FetchPlan} runs once per page, declaring the columns it reads so the query selects them
 * (R-FCH-02). Immutable (INV-9).
 *
 * @param <M> the model it fills
 * @implSpec R-FCH-08
 */
@Incubating
public final class Enricher<M> {

    private final List<ColumnField<M, ?, ?>> columns;
    /** What {@link #of} was given, or {@code null} for a {@code byKey}/{@code byKeys} enricher. */
    private final UnaryOperator<List<M>> page;
    /** What {@code byKey}/{@code byKeys} was given, or {@code null} for {@link #of}. */
    private final KeysEngine<M, ?, ?> byKeys;

    private Enricher(List<ColumnField<M, ?, ?>> columns, UnaryOperator<List<M>> page, KeysEngine<M, ?, ?> byKeys) {
        this.columns = columns;
        this.page = page;
        this.byKeys = byKeys;
    }

    /**
     * An enricher that reads {@code key} from each model, passes the distinct non-null keys to {@code lookup} in one
     * call, and copies each model with its value through {@code with}; a model whose key is absent from the result is
     * left as is. It reads {@code columns}. It is the one-key case of {@link #byKeys} (R-FCH-15).
     */
    @SafeVarargs
    public static <M, K, V> Enricher<M> byKey(Function<? super M, ? extends K> key,
            Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup,
            BiFunction<? super M, ? super V, ? extends M> with, ColumnField<M, ?, ?>... columns) {
        return Enricher.<M, K, V>byKeys(lookup).key(key, with).reading(columns);
    }

    /**
     * An enricher over several keys per model: each {@link Keys#key} routes its key's value into its own field. The
     * distinct non-null keys across models and keys go to one lookup call per run, or one per {@link Keys#batchSize
     * chunk}, and every value found is passed to each key's setter. Java cannot infer {@code M} through a builder
     * chain of implicit lambdas, so this takes explicit type witnesses (R-FCH-15, D-114).
     */
    public static <M, K, V> Keys<M, K, V> byKeys(
            Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup) {
        return new Keys<>(Objects.requireNonNull(lookup, "lookup"), List.of(), 0);
    }

    /**
     * An enricher that takes a page of models and returns it filled: one model per position, in the page's order, since
     * the model at position {@code i} of the result replaces the one at position {@code i} of the page; a reordered
     * result attaches data to the wrong parent. It reads {@code columns}.
     */
    @SafeVarargs
    public static <M> Enricher<M> of(UnaryOperator<List<M>> page, ColumnField<M, ?, ?>... columns) {
        return new Enricher<>(checked(columns), Objects.requireNonNull(page, "page"), null);
    }

    /** The columns it reads, in order, as a list that throws on mutation. */
    public List<ColumnField<M, ?, ?>> columns() {
        return columns;
    }

    /**
     * Runs the enricher once over {@code page}, a page's models in order, and returns the page it filled.
     * {@link #byKey} calls its lookup only when a model has a non-null key, and keeps the size and order;
     * {@link #of} gets a modifiable copy of the page. {@code owner} names the plan's model, or the join path, in a
     * message. Exceptions of the caller's code propagate unwrapped.
     *
     * @throws ModelQueryExecutionException {@code MQ2602} when an {@link #of} enricher returns {@code null}, a page
     *     of another size or a page with a {@code null} model; {@code MQ2606} when a {@code byKey} or {@code byKeys}
     *     lookup returns {@code null}
     */
    @EngineFacing
    public List<M> enrich(String owner, List<M> page) {
        if (byKeys != null) {
            return byKeys.enrich(owner, page);
        }
        List<M> result = this.page.apply(new ArrayList<>(page));
        if (result == null || result.size() != page.size()) {
            throw new ModelQueryExecutionException(MqCode.MQ2602, owner + ": an Enricher.of returned "
                    + (result == null ? "null" : "a page of " + result.size()) + " for a page of " + page.size()
                    + " models; return one model per model of the page, filled");
        }
        // A loop, not indexOf(null): an immutable list's indexOf rejects null.
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) == null) {
                throw new ModelQueryExecutionException(MqCode.MQ2602, owner + ": an Enricher.of returned null at "
                        + "position " + i + " of the page; return one model per model of the page, filled");
            }
        }
        return result;
    }

    private static <M> List<ColumnField<M, ?, ?>> checked(ColumnField<M, ?, ?>[] columns) {
        var result = new ArrayList<ColumnField<M, ?, ?>>();
        for (ColumnField<M, ?, ?> column : Objects.requireNonNull(columns, "columns")) {
            result.add(Objects.requireNonNull(column, "columns element"));
        }
        return List.copyOf(result);
    }

    /**
     * The builder of a {@link #byKeys} enricher: immutable, so every {@link #key} and {@link #batchSize} returns a
     * copy, and {@link #reading} turns it into the {@link Enricher}. A Java type declaration cannot be partially
     * applied, so {@code M}, {@code K} and {@code V} are fixed when {@link #byKeys} is called (R-FCH-15).
     *
     * @param <M> the model it fills
     * @param <K> the key it looks up
     * @param <V> the value the lookup returns
     */
    @Incubating
    public static final class Keys<M, K, V> {

        private final Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup;
        private final List<Binding<M, K, V>> bindings;
        /** {@code 0} for unset (one lookup per run), else the largest chunk {@link #batchSize} was given. */
        private final int batchSize;

        private Keys(Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup, List<Binding<M, K, V>> bindings,
                int batchSize) {
            this.lookup = lookup;
            this.bindings = bindings;
            this.batchSize = batchSize;
        }

        /** Routes {@code key}'s looked-up value into {@code with}'s field. Returns a copy with the binding added. */
        public Keys<M, K, V> key(Function<? super M, ? extends K> key,
                BiFunction<? super M, ? super V, ? extends M> with) {
            var added = new ArrayList<>(bindings);
            added.add(new Binding<>(Objects.requireNonNull(key, "key"), Objects.requireNonNull(with, "with")));
            return new Keys<>(lookup, List.copyOf(added), batchSize);
        }

        /**
         * Caps a lookup call at {@code maxKeysPerLookup} keys, splitting the run's distinct keys into consecutive
         * chunks in first-seen order: {@code ceil(distinct / maxKeysPerLookup)} calls, one after another on the
         * calling thread. A value below 1 is {@code MQ1707}. Returns a copy.
         */
        public Keys<M, K, V> batchSize(int maxKeysPerLookup) {
            if (maxKeysPerLookup < 1) {
                throw new ModelQueryDefinitionException(MqCode.MQ1707, "Enricher.Keys.batchSize(" + maxKeysPerLookup
                        + ") is below 1; a lookup call holds at least one key, so leave batchSize unset for one call "
                        + "per run or set it to the largest chunk the lookup accepts");
            }
            return new Keys<>(lookup, bindings, maxKeysPerLookup);
        }

        /**
         * The columns the enricher reads, selected per R-FCH-02, and the finished enricher.
         *
         * @throws ModelQueryDefinitionException {@code MQ1706} when no {@link #key} was declared
         */
        @SafeVarargs
        public final Enricher<M> reading(ColumnField<M, ?, ?>... columns) {
            if (bindings.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1706, "Enricher.byKeys(...).reading(...) declares no "
                        + "key(...); add one key(key, with) per field the enricher fills");
            }
            return new Enricher<>(checked(columns), null, new KeysEngine<>(lookup, bindings, batchSize));
        }
    }

    /** One key of a {@link Keys} enricher and the field its looked-up value is routed to. */
    private record Binding<M, K, V>(Function<? super M, ? extends K> key,
            BiFunction<? super M, ? super V, ? extends M> with) {}

    /** The parts of a {@code byKey}/{@code byKeys} enricher, run by {@link Enricher#enrich}. */
    private static final class KeysEngine<M, K, V> {

        private final Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup;
        private final List<Binding<M, K, V>> bindings;
        private final int batchSize;

        KeysEngine(Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup, List<Binding<M, K, V>> bindings,
                int batchSize) {
            this.lookup = lookup;
            this.bindings = bindings;
            this.batchSize = batchSize;
        }

        List<M> enrich(String owner, List<M> page) {
            // Each model's keys, read once from the model as the page holds it: a binding whose with() changes a field
            // another binding's key reads must not change that key, so the fill loop below uses these, not the model.
            var keysByModel = new ArrayList<List<K>>(page.size());
            // The distinct non-null keys across models and keys, in first-seen order.
            var distinct = new LinkedHashSet<K>();
            for (M model : page) {
                var keys = new ArrayList<K>(bindings.size());
                for (Binding<M, K, V> binding : bindings) {
                    K key = binding.key().apply(model);
                    keys.add(key);
                    if (key != null) {
                        distinct.add(key);
                    }
                }
                keysByModel.add(keys);
            }
            if (distinct.isEmpty()) {
                return page;
            }
            List<K> ordered = new ArrayList<>(distinct);
            int chunkSize = batchSize == 0 ? ordered.size() : batchSize;
            Map<K, V> found = new HashMap<>();
            for (int from = 0; from < ordered.size(); from += chunkSize) {
                int to = Math.min(from + chunkSize, ordered.size());
                Set<K> chunk = Collections.unmodifiableSet(new LinkedHashSet<>(ordered.subList(from, to)));
                Map<K, ? extends V> result = lookup.apply(chunk);
                if (result == null) {
                    throw new ModelQueryExecutionException(MqCode.MQ2606, owner + ": an Enricher.byKey or byKeys "
                            + "lookup returned null; return an empty map for no value");
                }
                for (K key : chunk) {
                    V value = result.get(key);
                    if (value != null) {
                        found.put(key, value);
                    }
                }
            }
            var enriched = new ArrayList<M>(page.size());
            for (int i = 0; i < page.size(); i++) {
                M filled = page.get(i);
                List<K> keys = keysByModel.get(i);
                for (int b = 0; b < bindings.size(); b++) {
                    K key = keys.get(b);
                    if (key == null) {
                        continue;
                    }
                    V value = found.get(key);
                    if (value != null) {
                        filled = bindings.get(b).with().apply(filled, value);
                        if (filled == null) {
                            throw new ModelQueryExecutionException(MqCode.MQ2602, owner + ": an Enricher.byKey or "
                                    + "byKeys with() returned null at key index " + b + "; return the model it was "
                                    + "given, filled");
                        }
                    }
                }
                enriched.add(filled);
            }
            return enriched;
        }
    }
}
