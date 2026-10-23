package com.rey.modelquery.core;

import jakarta.persistence.Tuple;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spike copy of the index-resolved {@link Row} for P-B.1 (RB.5): resolves tuple positions and
 * value columns once per build, calling package-private {@link ColumnField#valueColumn} and
 * {@link ColumnField#toModel} directly without reflection or dynamic invoke overhead.
 */
public final class IndexResolvedValueRow implements Row {

    private final Tuple tuple;
    private final Map<SelectField<?, ?>, Integer> indices;
    private final ColumnField<?, ?, ?>[] valueColumns;

    public static IndexResolver resolver(List<? extends SelectField<?, ?>> fields) {
        Map<SelectField<?, ?>, Integer> indices = new IdentityHashMap<>(fields.size());
        ColumnField<?, ?, ?>[] valueCols = new ColumnField<?, ?, ?>[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            SelectField<?, ?> f = fields.get(i);
            indices.put(f, i);
            valueCols[i] = ColumnField.valueColumn(f);
        }
        return new IndexResolver(indices, valueCols);
    }

    public record IndexResolver(
            Map<SelectField<?, ?>, Integer> indices,
            ColumnField<?, ?, ?>[] valueColumns
    ) {
        public IndexResolvedValueRow row(Tuple tuple) {
            return new IndexResolvedValueRow(tuple, indices, valueColumns);
        }
    }

    public IndexResolvedValueRow(Tuple tuple, Map<SelectField<?, ?>, Integer> indices,
            ColumnField<?, ?, ?>[] valueColumns) {
        this.tuple = Objects.requireNonNull(tuple, "tuple");
        this.indices = indices;
        this.valueColumns = valueColumns;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C> C get(SelectField<?, C> column) {
        Integer idx = indices.get(column);
        if (idx == null) {
            return null;
        }
        Object raw = tuple.get(idx);
        ColumnField<?, ?, ?> valCol = valueColumns[idx];
        if (valCol != null) {
            return (C) valCol.toModel(raw);
        }
        return column.type().cast(raw);
    }

    @Override
    public Object raw(SelectField<?, ?> column) {
        Integer idx = indices.get(column);
        return idx == null ? null : tuple.get(idx);
    }

    @Override
    public boolean isSelected(SelectField<?, ?> column) {
        return indices.containsKey(column);
    }

    @Override
    public Row scoped(TableField<?, ?> join) {
        throw new UnsupportedOperationException("scoped views not needed for flat projection spike");
    }
}
