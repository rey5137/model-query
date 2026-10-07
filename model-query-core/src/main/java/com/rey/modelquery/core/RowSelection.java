package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The selections of one query and the {@link Row} view over the {@link Tuple} they produce. Each selection gets a
 * generated alias, and rows read by that alias only, never by tuple index (R-COL-10). Immutable and thread-safe
 * (INV-9); a selection appears once, at its first position. Columns over one attribute of one table share the first
 * one's alias, so the attribute is selected once and each column converts the value it reads.
 *
 * @implSpec R-COL-10
 */
@EngineFacing
@Incubating
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

    /** A selected column's attribute: columns sharing one are one selection, read under one alias. */
    private record AttributeKey(JoinKey table, String attribute) {}

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
        var byAttribute = new HashMap<AttributeKey, String>();
        int next = 0;
        for (SelectField<?, ?> column : Objects.requireNonNull(columns, "columns")) {
            Objects.requireNonNull(column, "column");
            if (aliases.containsKey(column)) {
                continue;
            }
            AttributeKey key = column instanceof ColumnField<?, ?, ?> field
                    ? new AttributeKey(field.table().key(), field.name()) : null;
            String alias = key == null ? null : byAttribute.get(key);
            if (alias == null) {
                alias = "c" + next++;
                if (key != null) {
                    byAttribute.put(key, alias);
                }
            }
            aliases.put(column, alias);
        }
        return new RowSelection(Collections.unmodifiableMap(aliases));
    }

    /**
     * The selected fields, in selection order, for a paging check that must resolve each of them: order keys and
     * group keys count as selected, and an expression reads columns a rendered selection alone cannot walk (R-PAG-13).
     */
    @EngineFacing
    public List<SelectField<?, ?>> fields() {
        return List.copyOf(aliases.keySet());
    }

    /**
     * The aliased Criteria selections to pass to {@code multiselect}, resolving joins through {@code ctx}. A column
     * sharing another's alias is resolved, for its joins and its type check, but not selected again: a provider may
     * hand back one path object per attribute, as Hibernate does, and aliasing it twice renames the first selection.
     */
    public List<Selection<?>> selections(JoinContext ctx) {
        var result = new ArrayList<Selection<?>>(aliases.size());
        var selected = new HashSet<String>();
        aliases.forEach((column, alias) -> {
            Expression<?> expression = column.expression(ctx);
            if (selected.add(alias)) {
                result.add(expression.alias(alias));
            }
        });
        return result;
    }

    /** The row view of one tuple produced by {@link #selections(JoinContext)}. */
    public Row row(Tuple tuple) {
        return new ValueRow(aliases, byPath, Objects.requireNonNull(tuple, "tuple")::get, null);
    }

    /**
     * The row view of the values {@code values} gives each selected column, as read before any converter, the way a
     * tuple holds them; asked once per alias, so columns sharing an attribute share its value (R-WRT-48).
     */
    Row row(Function<? super SelectField<?, ?>, Object> values) {
        Objects.requireNonNull(values, "values");
        var byAlias = new HashMap<String, Object>();
        aliases.forEach((column, alias) -> {
            if (!byAlias.containsKey(alias)) {
                byAlias.put(alias, values.apply(column));
            }
        });
        return new ValueRow(aliases, byPath, byAlias::get, null);
    }

    /** A row reading each column's value by its alias from {@code values}: a tuple's, or values read elsewhere. */
    private record ValueRow(Map<SelectField<?, ?>, String> aliases, Map<PathKey, String> byPath,
            Function<String, Object> values, JoinKey scope) implements Row {

        @Override
        public <C> C get(SelectField<?, C> column) {
            Object raw = raw(column);
            ColumnField<?, ?, C> values = ColumnField.valueColumn(column);
            return values != null ? values.toModel(raw) : column.type().cast(raw);
        }

        @Override
        public Object raw(SelectField<?, ?> column) {
            String alias = alias(column);
            return alias == null ? null : values.apply(alias);
        }

        @Override
        public boolean isSelected(SelectField<?, ?> column) {
            return alias(column) != null;
        }

        @Override
        public Row scoped(TableField<?, ?> join) {
            JoinKey key = Objects.requireNonNull(join, "join").key();
            // Inside a scope, join is a path of the nested model, so it is re-rooted too: scopes compose.
            return new ValueRow(aliases, byPath, values, scope == null ? key : key.reroot(scope));
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
