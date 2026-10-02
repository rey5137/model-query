package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * A {@link ColumnField} whose model values order as its database values do: a column with no converter, or with an
 * {@link OrderedColumnConverter}. It is the only column {@link Agg#min}, {@link Agg#max} and
 * {@link Agg#countDistinct} take, so {@code min}, {@code max} and {@code countDistinct} over the model values equal
 * the database aggregate (D-84, D-93). A column with any other {@link ColumnConverter} is a plain
 * {@code ColumnField}, which those functions refuse at compile time.
 *
 * <p>The factories of {@link ColumnField} choose the type, and {@link #named} and {@link #withTable} keep it. Equal
 * columns stay equal across the two types: equality does not look at the class (CC-IMM-04).
 *
 * @param <M> the model the column belongs to
 * @param <T> the entity type of the table the column sits on
 * @param <C> the column's Java type
 * @implSpec R-AGG-04, D-84, D-93
 */
@Incubating
public final class OrderedColumnField<M, T, C> extends ColumnField<M, T, C> {

    OrderedColumnField(Class<M> model, TableField<?, T> table, String attribute, Class<C> type,
            Class<?> attributeType, ColumnConverter<C, Object> converter, String property) {
        super(model, table, attribute, type, attributeType, converter, property);
    }

    /** As {@link ColumnField#named}, and still ordered. */
    @Override
    public OrderedColumnField<M, T, C> named(String property) {
        return (OrderedColumnField<M, T, C>) super.named(property);
    }

    /** As {@link ColumnField#withTable}, and still ordered. */
    @Override
    public <M2> OrderedColumnField<M2, T, C> withTable(Class<M2> model, TableField<?, T> table) {
        return (OrderedColumnField<M2, T, C>) super.withTable(model, table);
    }
}
