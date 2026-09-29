package com.rey.modelquery.core;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
@Incubating
public final class RowSelection {

    private final Map<SelectField<?, ?>, String> aliases;

    private RowSelection(Map<SelectField<?, ?>, String> aliases) {
        this.aliases = aliases;
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
        return new TupleRow(aliases, Objects.requireNonNull(tuple, "tuple"), null);
    }

    private record TupleRow(Map<SelectField<?, ?>, String> aliases, Tuple tuple, JoinKey scope) implements Row {

        @Override
        public <C> C get(SelectField<?, C> column) {
            String alias = alias(column);
            return alias == null ? null : column.type().cast(tuple.get(alias));
        }

        @Override
        public boolean isSelected(SelectField<?, ?> column) {
            return alias(column) != null;
        }

        @Override
        public Row scoped(TableField<?, ?> join) {
            return new TupleRow(aliases, tuple, Objects.requireNonNull(join, "join").key());
        }

        private String alias(SelectField<?, ?> column) {
            Objects.requireNonNull(column, "column");
            if (scope == null) {
                return aliases.get(column);
            }
            if (!(column instanceof ColumnField<?, ?, ?> wanted)) {
                return null;
            }
            for (var entry : aliases.entrySet()) {
                if (entry.getKey() instanceof ColumnField<?, ?, ?> candidate
                        && candidate.table().key().equals(scope)
                        && candidate.name().equals(wanted.name())
                        && candidate.type().equals(wanted.type())) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }
}
