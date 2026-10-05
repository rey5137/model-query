package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.List;

/**
 * One join plan of a {@link FetchPlan}: the {@code @Join} field and the nested plan applied to the models it holds.
 * An executor runs the nested plan on the present nested models of a page, reading their rows under the join, and
 * sets each result back through the field; an empty field (a LEFT join that found nothing) is skipped.
 *
 * @param <M> the outer model
 * @param <N> the nested model
 * @implSpec R-FCH-07, R-FCH-08
 */
@EngineFacing
@Incubating
public final class JoinPlan<M, N> {

    private final JoinField<M, N> field;
    private final FetchPlan<N> plan;

    JoinPlan(JoinField<M, N> field, FetchPlan<N> plan) {
        this.field = field;
        this.plan = plan;
    }

    /** The join field the nested models are read from and set back through. */
    public JoinField<M, N> field() {
        return field;
    }

    /** The plan applied to the nested models. */
    public FetchPlan<N> plan() {
        return plan;
    }

    /**
     * The nested plan's selection, re-rooted under the join.
     *
     * @throws ModelQueryDefinitionException {@code MQ1705} for a nested plan selecting an aggregate or an
     *     expression
     */
    List<SelectField<M, ?>> selection() {
        var result = new ArrayList<SelectField<M, ?>>();
        for (SelectField<N, ?> column : plan.selection().fields()) {
            if (!(column instanceof ColumnField<N, ?, ?> plain)) {
                // An aggregate is over the nested model's own root and grouping, and an expression reads columns of
                // the outer vocabulary, so neither can be re-rooted under the join (R-FCH-07).
                String kind = column instanceof AggregateField<?, ?> ? "aggregate" : "expression";
                throw new ModelQueryDefinitionException(MqCode.MQ1705, this + ": the join plan selects the " + kind
                        + " " + column.name() + ", which cannot be re-rooted under the join; select the " + kind
                        + " in the outer plan");
            }
            result.add(reroot(plain));
        }
        return result;
    }

    /** The nested plan's needed columns, re-rooted under the join. */
    List<ColumnField<M, ?, ?>> needed() {
        return plan.needed().stream().<ColumnField<M, ?, ?>>map(this::reroot).toList();
    }

    /** The outer model and the join field, the way a message names the join plan. */
    @Override
    public String toString() {
        return field.model().getSimpleName() + "." + field.name();
    }

    private ColumnField<M, ?, ?> reroot(ColumnField<N, ?, ?> column) {
        return column.under(field.model(), field.table());
    }
}
