package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;

/**
 * A {@code @Child} field of a model holding the basic values of an {@code @ElementCollection}, reached through a
 * {@code through} path from the parent's root that crosses to-one associations only. The processor generates one per
 * such {@code @Child}, on a {@code List<E>} or {@code Set<E>} field. Its rows are a bag: none is deduplicated, and a
 * {@code NULL} element is kept (D-247).
 *
 * @param <M> the parent model
 * @param <E> the element type
 * @implSpec R-FCH-19
 */
@Incubating
public interface ValueChildField<M, E> {

    /** The model field the values fill. */
    String name();

    /** The parent root's {@code @Id}, which the values are matched on; selected by a plan (R-FCH-02). */
    ColumnField<M, ?, ?> key();

    /**
     * The entity holding the element collection: the parent's root, or the last of the to-one associations the
     * {@code through} path crosses from it, each joined {@code INNER}.
     */
    TableField<?, ?> owner();

    /** The {@code @ElementCollection} attribute of {@link #owner()}. */
    String attribute();

    /**
     * The element class {@code E} names, which the plan build checks against the collection's element type, boxed
     * (INV-3, D-260).
     */
    Class<E> elementType();

    /**
     * Whether the collection carries {@code @OrderColumn}: the values are then joined as a list and read in its
     * {@code index()} order.
     */
    @EngineFacing
    boolean isOrdered();

    /** Whether the values are ordered by themselves after any {@code index()}: a basic element that is not a LOB. */
    @EngineFacing
    boolean ordersByValue();

    /**
     * {@code parent} with the field set to {@code values}, in order, {@code null}s and repeats included; a {@code Set}
     * field keeps the first of equal values. A copy for a record, the same instance, set, for a class.
     */
    M with(M parent, List<E> values);
}
