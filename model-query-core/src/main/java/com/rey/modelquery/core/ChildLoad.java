package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * One child a {@link FetchPlan} loads: its field, the child query it runs and its bound. The child query is built
 * when the plan names the child, so it is checked once there, not per page: it selects the child plan's selection and
 * the child's {@code foreignKey}, which a load reads from each child row, and carries the {@link ChildQuery} filters
 * and order. An executor adds the key match and the primary-key tie-breaker.
 *
 * @param <M> the parent model
 * @param <C> the child model
 * @implSpec R-FCH-04, R-FCH-05, R-FCH-11
 */
@EngineFacing
@Incubating
public final class ChildLoad<M, C> {

    private final ChildField<M, C> field;
    private final ModelQuery<?, ?, C> query;
    private final int maxPerParent;

    /**
     * @throws ModelQueryDefinitionException as {@link ModelQuery.Builder#build()} does for the child query
     */
    ChildLoad(ChildField<M, C> field, FetchPlan<C> plan, ChildQuery<C> load) {
        this.field = field;
        this.query = field.query().forChildren(plan.selecting(field.foreignKey()), load.filters(), load.order())
                .build();
        this.maxPerParent = load.bound();
    }

    /** The child field the load fills. */
    public ChildField<M, C> field() {
        return field;
    }

    /**
     * The child query: the child plan's selection plus the {@code foreignKey}, the child filters and the child order,
     * without the key match nor the primary-key tie-breaker. Its fetch plan is the child plan, with that column added.
     */
    public ModelQuery<?, ?, C> query() {
        return query;
    }

    /** The most children per parent, or 0 for no bound (R-FCH-11). */
    public int maxPerParent() {
        return maxPerParent;
    }

    /** The parent model and the child field, the way a message names the load. */
    @Override
    public String toString() {
        return field.key().model().getSimpleName() + "." + field.name();
    }
}
