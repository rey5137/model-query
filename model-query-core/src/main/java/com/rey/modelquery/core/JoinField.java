package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Optional;

/**
 * A {@code @Join} field of a model, holding a nested model read through the join. The processor generates one per
 * {@code @Join}; a {@link FetchPlan#join} applies a nested plan to the models it holds.
 *
 * @param <M> the outer model
 * @param <N> the nested model
 * @implSpec R-FCH-07
 */
@Incubating
public interface JoinField<M, N> {

    /** The model field the nested model fills. */
    String name();

    /** The outer model, which a nested plan's columns are re-rooted to. */
    Class<M> model();

    /** The join the nested model is read through, from the outer model's root. */
    TableField<?, ?> table();

    /** The nested model of {@code parent}; empty when a LEFT join found nothing. */
    Optional<N> get(M parent);

    /**
     * {@code parent} with the nested model replaced by {@code nested}. A copy for a record, the same instance, set,
     * for a class.
     */
    M with(M parent, N nested);
}
