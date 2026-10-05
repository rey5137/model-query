package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * A named expression a generated model field maps to: {@code @Computed(Def.class)} or
 * {@code @Aggregate(expression = Def.class)} names the implementing class, exactly as {@code @Column(converter)}
 * names a {@link ColumnConverter} (R-PROC-21, R-PROC-22, D-115).
 *
 * <p>{@link #expression()} is called once, while the generated model class initialises its constants. Build the
 * expression there, or from the generated {@code Q<Model>} constants, and never from a static constant the generated
 * class itself initialises: that constant would still be unset when the expression is built, which is the
 * class-initialisation cycle D-115 rejects.
 *
 * @param <M> the model the expression belongs to
 * @param <C> the expression's Java type
 * @implSpec R-PROC-21, D-115
 */
@Incubating
public interface ExpressionDefinition<M, C> {

    /** The expression, built once when the generated model's constants initialise. */
    ExpressionField<M, C> expression();
}
