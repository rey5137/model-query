package com.rey.modelquery.processor;

import com.rey.modelquery.processor.FilterLayout.Column;
import com.rey.modelquery.processor.JoinedTable.JoinedColumn;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of a model's filter columns its {@code fields()} index holds. A filter-only column is keyed by its attribute
 * path, which a mapped column or {@code @Computed} field may already hold as its property path, and a second filter
 * column may sit on the same path: either is left out of the index and stays a constant ({@code MQ3022}).
 * {@link ModelValidator} reports the ones left out and {@link QModelWriter} passes the rest, so both read one split.
 *
 * @param kept the filter columns the index holds, in declaration order
 * @param shadowed the filter columns it leaves out, in declaration order
 * @implSpec R-GEN-33
 */
record FilterIndex(List<Column> kept, List<Shadowed> shadowed) {

    FilterIndex {
        kept = List.copyOf(kept);
        shadowed = List.copyOf(shadowed);
    }

    /**
     * A filter column the index leaves out.
     *
     * @param column the filter column
     * @param key the key it would be held under: its path
     * @param holder what holds the key already, as a diagnostic names it
     */
    record Shadowed(Column column, String key, String holder) {}

    /**
     * Splits {@code filters} of {@code model}, whose {@code @Join}s are {@code joined}, by the select keys the index
     * holds: a column's property path, a joined column's, and a {@code @Computed} field's name.
     */
    static FilterIndex of(ModelDefinition model, List<JoinedTable> joined, FilterLayout filters) {
        var holders = new HashMap<String, String>();
        for (ModelField field : model.columns()) {
            holders.putIfAbsent(field.name(), "the column '" + field.name() + "'");
        }
        for (JoinedTable table : joined) {
            for (JoinedColumn column : table.columns()) {
                holders.putIfAbsent(column.path(), "the column '" + column.path() + "'");
            }
        }
        for (ModelField field : model.computed()) {
            holders.putIfAbsent(field.name(), "the @Computed field '" + field.name() + "'");
        }
        var kept = new ArrayList<Column>();
        var shadowed = new ArrayList<Shadowed>();
        Map<String, String> filterHolders = new HashMap<>();
        for (Column column : filters.columns()) {
            String key = column.definition().path();
            String holder = holders.get(key);
            if (holder == null) {
                holder = filterHolders.get(key);
            }
            if (holder == null) {
                filterHolders.put(key, "@FilterColumn(" + column.definition().name() + ")");
                kept.add(column);
            } else {
                shadowed.add(new Shadowed(column, key, holder));
            }
        }
        return new FilterIndex(kept, shadowed);
    }
}
