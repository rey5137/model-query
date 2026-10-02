package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Collections;
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
    /** What {@link #of} was given, or {@code null} for {@link #byKey}. */
    private final UnaryOperator<List<M>> page;
    /** What {@link #byKey} was given, or {@code null} for {@link #of}. */
    private final ByKey<M, ?, ?> byKey;

    private Enricher(List<ColumnField<M, ?, ?>> columns, UnaryOperator<List<M>> page, ByKey<M, ?, ?> byKey) {
        this.columns = columns;
        this.page = page;
        this.byKey = byKey;
    }

    /**
     * An enricher that reads {@code key} from each model, passes the distinct non-null keys to {@code lookup} in one
     * call, and copies each model with its value through {@code with}; a model whose key is absent from the result is
     * left as is. It reads {@code columns}.
     */
    @SafeVarargs
    public static <M, K, V> Enricher<M> byKey(Function<? super M, ? extends K> key,
            Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup,
            BiFunction<? super M, ? super V, ? extends M> with, ColumnField<M, ?, ?>... columns) {
        var byKey = new ByKey<M, K, V>(Objects.requireNonNull(key, "key"), Objects.requireNonNull(lookup, "lookup"),
                Objects.requireNonNull(with, "with"));
        return new Enricher<>(checked(columns), null, byKey);
    }

    /** An enricher that takes a page of models and returns it filled, of the same size. It reads {@code columns}. */
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
     * @throws ModelQueryExecutionException {@code MQ2602} when an {@link #of} enricher returns {@code null} or a page
     *     of another size
     */
    @EngineFacing
    public List<M> enrich(String owner, List<M> page) {
        if (byKey != null) {
            return byKey.enrich(owner, page);
        }
        List<M> result = this.page.apply(new ArrayList<>(page));
        if (result == null || result.size() != page.size()) {
            throw new ModelQueryExecutionException(MqCode.MQ2602, owner + ": an Enricher.of returned "
                    + (result == null ? "null" : "a page of " + result.size()) + " for a page of " + page.size()
                    + " models; return one model per model of the page, filled");
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

    /** The parts of a {@link #byKey} enricher. */
    private record ByKey<M, K, V>(Function<? super M, ? extends K> key,
            Function<? super Set<K>, ? extends Map<K, ? extends V>> lookup,
            BiFunction<? super M, ? super V, ? extends M> with) {

        List<M> enrich(String owner, List<M> page) {
            var keys = new ArrayList<K>(page.size());
            var distinct = new LinkedHashSet<K>();
            for (M model : page) {
                K value = key.apply(model);
                keys.add(value);
                if (value != null) {
                    distinct.add(value);
                }
            }
            if (distinct.isEmpty()) {
                return page;
            }
            Map<K, ? extends V> found = Objects.requireNonNull(lookup.apply(Collections.unmodifiableSet(distinct)),
                    () -> owner + ": an Enricher.byKey lookup returned null; return an empty map for no value");
            var result = new ArrayList<M>(page.size());
            for (int i = 0; i < page.size(); i++) {
                K value = keys.get(i);
                V v = value == null ? null : found.get(value);
                result.add(v == null ? page.get(i) : with.apply(page.get(i), v));
            }
            return result;
        }
    }
}
