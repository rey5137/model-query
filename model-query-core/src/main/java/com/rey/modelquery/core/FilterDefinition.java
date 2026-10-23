package com.rey.modelquery.core;

import java.util.function.UnaryOperator;

import com.rey.modelquery.annotations.Incubating;

/**
 * A named condition an aggregate is filtered by: {@code @Aggregate(filter = Def.class)} names the implementing class,
 * exactly as {@code @Aggregate(expression)} names an {@link ExpressionDefinition} (R-AGG-17, R-PROC-45).
 *
 * <p>{@link #condition()} is called once, while the generated model class initialises its constants, so the same
 * rule as {@link ExpressionDefinition} applies: never build it from a static constant the generated class itself
 * initialises (D-115).
 *
 * @param <M> the model the aggregate belongs to
 * @implSpec R-AGG-17, R-PROC-45
 */
@Incubating
public interface FilterDefinition<M> {

    /** The condition, built once when the generated model's constants initialise. */
    UnaryOperator<Filters<M>> condition();
}
