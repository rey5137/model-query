package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, ordered set of selections for one model. {@code with} and {@code without} return copies, so a shared
 * constant cannot be changed by one caller (INV-9). A selection appears at most once, at its first position.
 *
 * @param <M> the model the selections belong to
 * @implSpec R-COL-09, R-COL-21, R-COL-22
 */
@Incubating
public final class SelectSet<M> {

    /** A selection's bit mask over this set's fields and the subset it selects: one immutable memo entry. */
    private record Memo<M>(long[] mask, SelectSet<M> subset) {}

    private final List<SelectField<M, ?>> fields;
    private volatile Memo<M> memo;

    private SelectSet(Set<SelectField<M, ?>> columns) {
        this.fields = List.copyOf(columns);
    }

    /** A set of {@code columns}, in the given order; an empty set selects nothing (P-3). */
    @SafeVarargs
    public static <M> SelectSet<M> of(SelectField<M, ?>... columns) {
        return new SelectSet<>(append(new LinkedHashSet<>(), checked(columns, "columns")));
    }

    /** A set of {@code columns}, in the given order, as {@link #of} makes one. */
    static <M> SelectSet<M> copyOf(Collection<? extends SelectField<M, ?>> columns) {
        return new SelectSet<>(append(new LinkedHashSet<>(), columns));
    }

    /** A copy with {@code extra} appended; columns already present keep their position. */
    @SafeVarargs
    public final SelectSet<M> with(SelectField<M, ?>... extra) {
        return new SelectSet<>(append(new LinkedHashSet<>(fields), checked(extra, "extra")));
    }

    /** A copy with the columns of {@code other} appended; columns already present keep their position. */
    public SelectSet<M> with(SelectSet<M> other) {
        return new SelectSet<>(append(new LinkedHashSet<>(fields), Objects.requireNonNull(other, "other").fields));
    }

    /** A copy without {@code columns}. */
    @SafeVarargs
    public final SelectSet<M> without(SelectField<M, ?>... columns) {
        var result = new LinkedHashSet<>(fields);
        result.removeAll(checked(columns, "columns"));
        return new SelectSet<>(result);
    }

    /** The selections in order, as a list that throws on mutation (CC-IMM-03). */
    public List<SelectField<M, ?>> fields() {
        return fields;
    }

    /** Whether {@code field} is in the set, by field equality and never through a converter (R-COL-21). */
    @Incubating
    public boolean contains(SelectField<M, ?> field) {
        return fields.contains(Objects.requireNonNull(field, "field"));
    }

    /**
     * The fields of this set that {@code row} selected, in set order; {@code this} when all are, never {@code null}. A
     * row of the same selection gets the same instance (R-COL-22).
     */
    @Incubating
    public SelectSet<M> selectedIn(Row row) {
        Objects.requireNonNull(row, "row");
        long[] mask = new long[(fields.size() + 63) >>> 6];
        int selected = 0;
        for (int i = 0; i < fields.size(); i++) {
            if (row.isSelected(fields.get(i))) {
                mask[i >>> 6] |= 1L << i;
                selected++;
            }
        }
        if (selected == fields.size()) {
            return this;
        }
        Memo<M> seen = memo;
        if (seen != null && Arrays.equals(seen.mask(), mask)) {
            return seen.subset();
        }
        var subset = new ArrayList<SelectField<M, ?>>(selected);
        for (int i = 0; i < fields.size(); i++) {
            if ((mask[i >>> 6] & (1L << i)) != 0) {
                subset.add(fields.get(i));
            }
        }
        var result = new SelectSet<M>(new LinkedHashSet<>(subset));
        memo = new Memo<>(mask, result);
        return result;
    }

    /** Whether {@code other} is a set of the same selections, in any order (R-COL-21). */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SelectSet<?> set && fields.size() == set.fields.size()
                && new HashSet<>(fields).equals(new HashSet<>(set.fields));
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (SelectField<M, ?> field : fields) {
            hash += field.hashCode();
        }
        return hash;
    }

    /** The selections in order, like {@code [OrderView.id, OrderView.status]} (R-COL-21). */
    @Override
    public String toString() {
        return fields.toString();
    }

    /** Whether the set selects nothing (P-3). */
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /**
     * {@code into} with {@code extra} appended. An {@code Agg.of} equal to one already present but defined by a
     * different function throws {@code MQ1103} here, where keeping the first would silently drop it (R-AGG-02).
     */
    private static <M> Set<SelectField<M, ?>> append(
            Set<SelectField<M, ?>> into, Collection<? extends SelectField<M, ?>> extra) {
        for (SelectField<M, ?> column : extra) {
            if (!into.add(column)) {
                for (SelectField<M, ?> present : into) {
                    if (AggregateField.conflict(present, column)) {
                        throw AggregateField.redefined("SelectSet", column);
                    }
                }
            }
        }
        return into;
    }

    private static <M> List<SelectField<M, ?>> checked(SelectField<M, ?>[] columns, String name) {
        Objects.requireNonNull(columns, name);
        var result = new ArrayList<SelectField<M, ?>>(columns.length);
        for (SelectField<M, ?> column : columns) {
            result.add(Objects.requireNonNull(column, name + " element"));
        }
        return result;
    }
}
