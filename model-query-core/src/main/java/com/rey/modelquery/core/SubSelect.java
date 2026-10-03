package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * One column of another root with its own filters, for {@code in}, {@code notIn} and a correlated {@code exists}
 * (R-FLT-15). Immutable, so it may be a constant (INV-9); {@link #where} returns a new sub-select and leaves this one
 * alone. It has no order, limit, grouping, second column or aggregate, and is never skipped, even when every one of
 * its own filters was skipped: skip it with {@code when} on the filter that uses it.
 *
 * <p>{@link #of(ColumnField)} selects the column and roots the sub-select at the path its column starts at, any
 * entity, the outer root's own included. {@link #where(UnaryOperator)} adds filters on that root, recorded once, and
 * every one of their columns must start at that root, else {@code MQ1003} at definition.
 *
 * <p>It is not named {@code SubQuery}, which differs from {@code jakarta.persistence.criteria.Subquery} only by case.
 *
 * @param <S> the inner vocabulary: a generated model's columns, or the entity class for hand-written ones
 * @param <C> the selected column's Java type
 * @implSpec api/12 R-FLT-15 to R-FLT-17, D-112
 */
@Incubating
public final class SubSelect<S, C> {

    private final ColumnField<S, ?, C> column;
    private final TableField<?, ?> root;
    private final List<Filter> filters;

    private SubSelect(ColumnField<S, ?, C> column, TableField<?, ?> root, List<Filter> filters) {
        this.column = column;
        this.root = root;
        this.filters = filters;
    }

    /** The sub-select of {@code column} from the root its path starts at, with no filters. */
    public static <S, C> SubSelect<S, C> of(ColumnField<S, ?, C> column) {
        Objects.requireNonNull(column, "column");
        TableField<?, ?> root = column.table();
        while (root.parent() != null) {
            root = root.parent();
        }
        return new SubSelect<>(column, root, List.of());
    }

    /**
     * This sub-select with {@code filters} as its where, replacing any earlier ones.
     *
     * @throws ModelQueryDefinitionException {@code MQ1003} for a filter column starting at another root
     */
    public SubSelect<S, C> where(UnaryOperator<Filters<S>> filters) {
        Objects.requireNonNull(filters, "filters");
        return new SubSelect<>(column, root, FilterGroup.collectWhere(root, filters));
    }

    /** The selected column. */
    public ColumnField<S, ?, C> column() {
        return column;
    }

    /** The root the selected column's path starts at. */
    public TableField<?, ?> root() {
        return root;
    }

    /** The filters its {@code where} recorded, in order, as an immutable tree (R-INS-04). */
    public List<Condition> conditions() {
        return ConditionGroup.conditions(filters);
    }

    /** The recorded filters, which render the sub-query's where. */
    List<Filter> filters() {
        return filters;
    }

    /** The root entity, which the sub-query selects from. */
    Class<?> rootEntity() {
        return root.rootEntity();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SubSelect<?, ?> other
                && column.equals(other.column)
                && root.key().equals(other.root.key())
                && conditions().equals(other.conditions());
    }

    @Override
    public int hashCode() {
        return Objects.hash(column, root.key(), conditions());
    }

    @Override
    public String toString() {
        return "sub-select(" + column + " from " + root.describe()
                + (conditions().isEmpty() ? "" : " where " + conditions()) + ")";
    }
}
