package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
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
            BiFunction<? super M, ? super V, ? extends M> with) {}
}
