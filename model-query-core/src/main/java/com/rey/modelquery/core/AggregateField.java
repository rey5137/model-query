package com.rey.modelquery.core;

import jakarta.persistence.criteria.Expression;
import java.util.function.Function;

/**
 * An aggregate selection (spec api/13). Instances are created only by the aggregate factories of api/13; this type
 * exists so that {@link SelectField} can name its permitted subtypes.
 *
 * @param <M> the model the selection belongs to
 * @param <C> the aggregate's result type
 * @implSpec D-3
 */
@Incubating
public final class AggregateField<M, C> implements SelectField<M, C> {

    private final Class<C> type;
    private final String name;
    private final Function<JoinContext, Expression<C>> expression;

    private AggregateField(Class<C> type, String name, Function<JoinContext, Expression<C>> expression) {
        this.type = type;
        this.name = name;
        this.expression = expression;
    }

    @Override
    public Class<C> type() {
        return type;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Expression<C> expression(JoinContext ctx) {
        return expression.apply(ctx);
    }
}
