package com.rey.modelquery.core;

/**
 * One result row, read by {@link SelectField} and never by position, so adding a selection cannot shift a mapping
 * (R-COL-10). Built by {@link RowSelection#row(jakarta.persistence.Tuple)}.
 *
 * @implSpec R-COL-10, R-COL-11
 */
@Incubating
public interface Row {

    /** The value of {@code column}, or {@code null} when the column is NULL or was not selected. */
    <C> C get(SelectField<?, C> column);

    /** Whether {@code column} is part of this row's selection. */
    boolean isSelected(SelectField<?, ?> column);

    /**
     * A view of a nested model's columns under {@code join}: a {@link ColumnField} of the nested model is looked up as
     * the same attribute and type on {@code join}. Aggregates are not visible through a scoped view.
     */
    Row scoped(TableField<?, ?> join);
}
