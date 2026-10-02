package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * A {@link ColumnConverter} that preserves order both ways: {@code a < b} exactly when
 * {@code toModel(a) < toModel(b)}, and the same for {@link #toAttribute}. It is therefore also injective. The
 * database's {@code min}, {@code max} and {@code countDistinct} over the attribute, with {@code toModel} applied to the
 * result, then equal the same aggregate over the model values, so {@link Agg} accepts a column carrying one (R-AGG-04).
 * {@code sum} and {@code avg} still refuse it ({@code MQ1408}): a converter that keeps order need not keep sums.
 *
 * <p>The interface adds no method: implementing it is the promise. {@link InstantTimestampConverter} and
 * {@link DateTimestampConverter} are the built-in ones.
 *
 * @param <C> the model type, the column's {@link ColumnField#type()}
 * @param <F> the entity attribute's type
 * @implSpec R-COL-14, R-AGG-04, D-84
 */
@Incubating
public interface OrderedColumnConverter<C, F> extends ColumnConverter<C, F> {}
