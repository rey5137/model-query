package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * One result row, read by {@link SelectField} and never by position, so adding a selection cannot shift a mapping
 * (R-COL-10). Built by {@link RowSelection#row(jakarta.persistence.Tuple)}.
 *
 * @implSpec R-COL-10, R-COL-11, R-COL-41
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
     * resolved the same way, so scopes compose for nesting two levels deep. Aggregates are not visible through a
     * scoped view. The same as {@link #scoped(JoinScope)} with {@link JoinScope#of(TableField)}: a column on a
     * joined root is refused, never read as the scope's own column (D-214).
     *
     * @throws ModelQueryDefinitionException {@code MQ1107} from the view's accessors, for a column on a joined root
     */
    Row scoped(TableField<?, ?> join);

    /**
     * A view of a nested model's columns under {@code scope}, as {@link #scoped(TableField)} on its table, that also
     * reads the nested model's own source and entity joins: a column on a joined root the scope maps is looked up on
     * the outer join standing for it, re-rooting any to-one path below it. Scopes compose: inside a scoped view,
     * {@code scope}'s table and outer joins are the nested model's and are resolved through the enclosing scope.
     *
     * @throws ModelQueryDefinitionException {@code MQ1107} from the view's accessors, for a column on a joined root
     *     the scope does not map (D-214, D-216)
     */
    @Incubating
    Row scoped(JoinScope scope);
}
