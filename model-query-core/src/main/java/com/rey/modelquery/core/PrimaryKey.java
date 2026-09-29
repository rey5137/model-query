package com.rey.modelquery.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The identifying columns of a model: one column, or several. Immutable.
 *
 * @param <M> the model the key belongs to
 * @param <K> the key's Java type; {@code List<Object>} (component values, in declaration order) for a composite key
 * @implSpec R-QRY-03, R-QRY-04
 */
@Incubating
public final class PrimaryKey<M, K> {

    private final List<ColumnField<M, ?, ?>> columns;

    private PrimaryKey(List<ColumnField<M, ?, ?>> columns) {
        this.columns = List.copyOf(columns);
    }

    /** A single-column key. */
    public static <M, K> PrimaryKey<M, K> of(ColumnField<M, ?, K> column) {
        return new PrimaryKey<>(List.of(Objects.requireNonNull(column, "column")));
    }

    /** A composite key of two or more columns, in the given order. */
    @SafeVarargs
    public static <M> PrimaryKey<M, List<Object>> composite(
            ColumnField<M, ?, ?> first, ColumnField<M, ?, ?> second, ColumnField<M, ?, ?>... more) {
        var all = new ArrayList<ColumnField<M, ?, ?>>();
        all.add(Objects.requireNonNull(first, "first"));
        all.add(Objects.requireNonNull(second, "second"));
        for (ColumnField<M, ?, ?> column : Objects.requireNonNull(more, "more")) {
            all.add(Objects.requireNonNull(column, "more element"));
        }
        return new PrimaryKey<>(all);
    }

    /** The key columns in order, as a list that throws on mutation. */
    public List<ColumnField<M, ?, ?>> columns() {
        return columns;
    }
}
