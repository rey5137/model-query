package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;

/**
 * A {@code @Child} field of a model, filled from another model's rows matched on one column each side. The processor
 * generates one per {@code @Child}; its methods read the child's generated class lazily, so a {@code @Child} and a
 * back-{@code @Join} between two models initialise in either order.
 *
 * @param <M> the parent model
 * @param <C> the child model
 * @implSpec R-FCH-03
 */
@Incubating
public interface ChildField<M, C> {

    /** The model field the children fill. */
    String name();

    /** The parent's column whose value the child's {@link #foreignKey()} equals; selected by a plan (R-FCH-02). */
    ColumnField<M, ?, ?> key();

    /** The child's column matched against the parent's {@link #key()}. */
    ColumnField<C, ?, ?> foreignKey();

    /** Whether the field is a {@code List} of children rather than an {@code Optional} one. */
    boolean isToMany();

    /** A builder of the child's own query, which a child load selects and filters through. */
    ModelQuery.Builder<?, ?, C> query();

    /** A copy of {@code parent} with the field set to {@code children}; one child at most for a to-one field. */
    M with(M parent, List<C> children);
}
