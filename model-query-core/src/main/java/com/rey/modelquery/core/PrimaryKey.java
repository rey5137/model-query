package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
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

    /**
     * {@code key IN (keys)} over attribute-value keys, as {@link #columns()} paths in {@code ctx}; a composite key is
     * an OR of per-key conjunctions, since JPA has no row-value IN (P-4). With {@code collapseSingle}, one key renders
     * as an equality, with no {@code OR} around one conjunction, as a write does; without it, as a one-element
     * {@code IN}, as a read by keys does.
     */
    @EngineFacing
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Predicate in(List<Object> keys, JoinContext ctx, CriteriaBuilder cb, boolean collapseSingle) {
        if (columns.size() == 1) {
            Path path = columns.get(0).path(ctx);
            return collapseSingle && keys.size() == 1 ? cb.equal(path, keys.get(0)) : path.in(keys);
        }
        Predicate[] each = new Predicate[keys.size()];
        for (int i = 0; i < each.length; i++) {
            List<?> values = (List<?>) keys.get(i);
            Predicate[] equal = new Predicate[columns.size()];
            for (int c = 0; c < equal.length; c++) {
                equal[c] = cb.equal(columns.get(c).path(ctx), values.get(c));
            }
            each[i] = cb.and(equal);
        }
        return collapseSingle && each.length == 1 ? each[0] : cb.or(each);
    }
}
