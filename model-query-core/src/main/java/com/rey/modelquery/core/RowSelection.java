package com.rey.modelquery.core;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The selections of one query and the {@link Row} view over the {@link Tuple} they produce. Each selection gets a
 * generated alias, and rows read by that alias only, never by tuple index (R-COL-10). Immutable and thread-safe
 * (INV-9); a selection appears once, at its first position.
 *
 * @implSpec R-COL-10
 */
@EngineFacing
public final class RowSelection {

    /**
     * A selected column's path, attribute, type and converter class: what a scoped {@link Row} matches a nested
     * model's column by.
     */
    private record PathKey(JoinKey table, String attribute, Class<?> type, Class<?> converter) {

        PathKey(JoinKey table, ColumnField<?, ?, ?> column) {
            this(table, column.name(), column.type(), column.converterClass());
        }
    }

    private final Map<SelectField<?, ?>, String> aliases;
    private final Map<PathKey, String> byPath;

    private RowSelection(Map<SelectField<?, ?>, String> aliases) {
        this.aliases = aliases;
        var paths = new HashMap<PathKey, String>();
        aliases.forEach((column, alias) -> {
            if (column instanceof ColumnField<?, ?, ?> field) {
                paths.putIfAbsent(new PathKey(field.table().key(), field), alias);
            }
        });
        this.byPath = Map.copyOf(paths);
    }

    /** A selection of {@code columns}, in order. */
    public static RowSelection of(Collection<? extends SelectField<?, ?>> columns) {
        var aliases = new LinkedHashMap<SelectField<?, ?>, String>();
        for (SelectField<?, ?> column : Objects.requireNonNull(columns, "columns")) {
            Objects.requireNonNull(column, "column");
            aliases.putIfAbsent(column, "c" + aliases.size());
        }
        return new RowSelection(Collections.unmodifiableMap(aliases));
    }

    /** The aliased Criteria selections to pass to {@code multiselect}, resolving joins through {@code ctx}. */
    public List<Selection<?>> selections(JoinContext ctx) {
        var result = new ArrayList<Selection<?>>(aliases.size());
        aliases.forEach((column, alias) -> result.add(column.expression(ctx).alias(alias)));
        return result;
    }

    /** The row view of one tuple produced by {@link #selections(JoinContext)}. */
    public Row row(Tuple tuple) {
        return new TupleRow(aliases, byPath, Objects.requireNonNull(tuple, "tuple"), null);
    }

    private record TupleRow(Map<SelectField<?, ?>, String> aliases, Map<PathKey, String> byPath, Tuple tuple,
            JoinKey scope) implements Row {

        @Override
        public <C> C get(SelectField<?, C> column) {
            Object raw = raw(column);
            ColumnField<?, ?, C> values = ColumnField.valueColumn(column);
            return values != null ? values.toModel(raw) : column.type().cast(raw);
        }

        @Override
        public Object raw(SelectField<?, ?> column) {
            String alias = alias(column);
            return alias == null ? null : tuple.get(alias);
        }

        @Override
        public boolean isSelected(SelectField<?, ?> column) {
            return alias(column) != null;
        }

        @Override
        public Row scoped(TableField<?, ?> join) {
            JoinKey key = Objects.requireNonNull(join, "join").key();
            // Inside a scope, join is a path of the nested model, so it is re-rooted too: scopes compose.
            return new TupleRow(aliases, byPath, tuple, scope == null ? key : key.reroot(scope));
        }

        private String alias(SelectField<?, ?> column) {
            Objects.requireNonNull(column, "column");
            if (scope == null) {
                return aliases.get(column);
            }
            if (!(column instanceof ColumnField<?, ?, ?> wanted)) {
                return null;
            }
            // The whole chain of join keys is re-rooted, so a column on a join below the nested root matches that
            // join's column, not the same attribute on the scope itself.
            return byPath.get(new PathKey(wanted.table().key().reroot(scope), wanted));
        }
    }
}
