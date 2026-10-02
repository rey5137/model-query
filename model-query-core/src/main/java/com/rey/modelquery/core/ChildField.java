package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;
import java.util.Optional;

/**
 * A {@code @Child} field of a model, filled from another model's rows matched on one column each side, or reached
 * through an association path from the parent's root ({@code through}). The processor generates one per
 * {@code @Child}; its methods read the child's generated class lazily, so a {@code @Child} and a back-{@code @Join}
 * between two models initialise in either order. Exactly one of {@link #foreignKey()} and {@link #through()} is
 * present.
 *
 * @param <M> the parent model
 * @param <C> the child model
 * @implSpec R-FCH-03, R-FCH-14
 */
@Incubating
public interface ChildField<M, C> {

    /** The model field the children fill. */
    String name();

    /**
     * The parent's column whose value the child's {@link #foreignKey()} equals, or, with {@link #through()}, the
     * parent root's {@code @Id}; selected by a plan (R-FCH-02).
     */
    ColumnField<M, ?, ?> key();

    /** The child's column matched against the parent's {@link #key()}; empty when {@link #through()} is present. */
    Optional<ColumnField<C, ?, ?>> foreignKey();

    /**
     * The association path from the parent's root entity to the child's, joined {@code INNER}, when the child query is
     * rooted at the parent's entity and the child model resolves under that path's last join (R-FCH-14, D-100); empty
     * when {@link #foreignKey()} is present. A {@code QueryCustomizer} of the child model then sees the parent's
     * entity in {@code query.getRoots()}, and must resolve the child's paths through its {@code JoinContext}.
     */
    Optional<TableField<?, ?>> through();

    /** Whether the field is a {@code List} of children rather than an {@code Optional} one. */
    boolean isToMany();

    /** A builder of the child's own query, which a child load selects and filters through. */
    ModelQuery.Builder<?, ?, C> query();

    /** A copy of {@code parent} with the field set to {@code children}; one child at most for a to-one field. */
    M with(M parent, List<C> children);
}
