package com.rey.modelquery.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, ordered set of selections for one model. {@code with} and {@code without} return copies, so a shared
 * constant cannot be changed by one caller (INV-9). A selection appears at most once, at its first position.
 *
 * @param <M> the model the selections belong to
 * @implSpec R-COL-09
 */
@Incubating
public final class ColumnSet<M> {

    private final List<SelectField<M, ?>> columns;

    private ColumnSet(Set<SelectField<M, ?>> columns) {
        this.columns = List.copyOf(columns);
    }

    /** A set of {@code columns}, in the given order; an empty set selects nothing (P-3). */
    @SafeVarargs
    public static <M> ColumnSet<M> of(SelectField<M, ?>... columns) {
        return new ColumnSet<>(checked(columns, "columns"));
    }

    /** A copy with {@code extra} appended; columns already present keep their position. */
    @SafeVarargs
    public final ColumnSet<M> with(SelectField<M, ?>... extra) {
        var result = new LinkedHashSet<>(columns);
        result.addAll(checked(extra, "extra"));
        return new ColumnSet<>(result);
    }

    /** A copy with the columns of {@code other} appended; columns already present keep their position. */
    public ColumnSet<M> with(ColumnSet<M> other) {
        var result = new LinkedHashSet<>(columns);
        result.addAll(Objects.requireNonNull(other, "other").columns);
        return new ColumnSet<>(result);
    }

    /** A copy without {@code columns}. */
    @SafeVarargs
    public final ColumnSet<M> without(SelectField<M, ?>... columns) {
        var result = new LinkedHashSet<>(this.columns);
        result.removeAll(checked(columns, "columns"));
        return new ColumnSet<>(result);
    }

    /** The selections in order, as a list that throws on mutation (CC-IMM-03). */
    public List<SelectField<M, ?>> columns() {
        return columns;
    }

    private static <M> Set<SelectField<M, ?>> checked(SelectField<M, ?>[] columns, String name) {
        Objects.requireNonNull(columns, name);
        var result = new LinkedHashSet<SelectField<M, ?>>();
        for (SelectField<M, ?> column : columns) {
            result.add(Objects.requireNonNull(column, name + " element"));
        }
        return result;
    }
}
