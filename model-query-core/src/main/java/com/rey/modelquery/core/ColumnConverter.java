package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * Converts between the type a model holds and the type of the entity attribute a {@link ColumnField} reads. The column
 * carries it: {@link Row#get} returns {@link #toModel} of what was read, and a value filter binds {@link #toAttribute}
 * of its value. An implementation is stateless, and neither method is ever given {@code null}.
 *
 * <p>The database compares, orders and pages by attribute values. A converter that is not a bijection is therefore
 * unsafe to filter by: two model values mapped to one attribute value match the same rows.
 *
 * @param <C> the model type, the column's {@link ColumnField#type()}
 * @param <F> the entity attribute's type
 * @implSpec R-COL-14, D-37
 */
@Incubating
public interface ColumnConverter<C, F> {

    /** The model value of {@code attribute}, a non-null value read from the database. */
    C toModel(F attribute);

    /** The attribute value to bind for {@code model}, a non-null value given to a filter. */
    F toAttribute(C model);
}
