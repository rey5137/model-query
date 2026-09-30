package com.rey.modelquery.core;

/**
 * One result row, read by {@link SelectField} and never by position, so adding a selection cannot shift a mapping
 * (R-COL-10). Built by {@link RowSelection#row(jakarta.persistence.Tuple)}.
 *
 * @implSpec R-COL-10, R-COL-11
 */
@Incubating
public interface Row {

    /**
     * The value of {@code column}, or {@code null} when the column is NULL or was not selected. A column with a
     * {@link ColumnConverter} returns the converted value (R-COL-14).
     */
    <C> C get(SelectField<?, C> column);

    /**
     * The value of {@code column} as read, before any {@link ColumnConverter}: the entity attribute's value, or
     * {@code null} when the column is NULL or was not selected. Primary keys, keyset cursors and export dedupe read
     * it, so a converter that maps two attribute values to one model value cannot merge two keys (R-COL-11).
     */
    Object raw(SelectField<?, ?> column);

    /** Whether {@code column} is part of this row's selection. */
    boolean isSelected(SelectField<?, ?> column);

    /**
     * A view of a nested model's columns under {@code join}: a {@link ColumnField} of the nested model, declared on
     * the nested model's own root or a join below it, is looked up as the same attribute, type and converter on that
     * path re-rooted under {@code join}. Called on a scoped view, {@code join} is a path of the nested model and is
     * re-rooted the same way, so scopes compose for nesting two levels deep. Aggregates are not visible through a
     * scoped view.
     */
    Row scoped(TableField<?, ?> join);
}
