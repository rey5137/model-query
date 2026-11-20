package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Map;

/**
 * A {@code @Child} field of a model holding a {@code Map<K, V>} from an {@code @ElementCollection}, reached through a
 * {@code through} path from the parent's root that crosses to-one associations only. The processor generates one per
 * such {@code @Child}. {@code K} is a basic key; {@code V} is either a basic element or an {@code @ElementModel} of
 * an embeddable element. The entries are collected into a {@code LinkedHashMap} in unspecified row order (D-248).
 *
 * @param <M> the parent model
 * @param <K> the map key type
 * @param <V> the map value type
 * @implSpec R-FCH-20, D-248
 */
@Incubating
public interface MapChildField<M, K, V> {

    /** The model field the map fills. */
    String name();

    /** The parent root's {@code @Id}, which the entries are matched on; selected by a plan (R-FCH-02). */
    ColumnField<M, ?, ?> key();

    /**
     * The entity holding the element collection: the parent's root, or the last of the to-one associations the
     * {@code through} path crosses from it, each joined {@code INNER}.
     */
    TableField<?, ?> owner();

    /** The {@code @ElementCollection} attribute of {@link #owner()}. */
    String attribute();

    /** Whether the value is an {@code @ElementModel} of an embeddable. */
    @EngineFacing
    @Incubating
    default boolean isElementModel() {
        return false;
    }

    /**
     * A builder of the element model query when {@link #isElementModel()} is true, or {@code null} when basic.
     */
    @EngineFacing
    @Incubating
    default ModelQuery.Builder<?, ?, V> elementQuery() {
        return null;
    }

    /**
     * {@code parent} with the field set to {@code entries}, an unmodifiable map in row order.
     */
    M with(M parent, Map<K, V> entries);
}
