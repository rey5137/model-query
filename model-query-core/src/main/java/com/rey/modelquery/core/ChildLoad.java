package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import java.util.Objects;

/**
 * One child a {@link FetchPlan} loads: its field, the child query it runs and its bound. The child query is built
 * when the plan names the child, so it is checked once there, not per page: it selects the child plan's selection and
 * the child's {@code foreignKey}, which a load reads from each child row, and carries the {@link ChildQuery} filters
 * and order. A {@code through} child's query is rooted at the parent's entity and selects the parent's key instead
 * (R-FCH-14, D-100). An executor adds the key match and the primary-key tie-breaker.
 *
 * @param <M> the parent model
 * @param <C> the child model
 * @implSpec R-FCH-04, R-FCH-05, R-FCH-11, R-FCH-14
 */
@EngineFacing
@Incubating
public final class ChildLoad<M, C> {

    private final ChildField<M, C> field;
    /** The child's column matched against the parent's key, or {@code null} for a {@code through} child. */
    private final ColumnField<C, ?, ?> foreignKey;
    /** The path from the parent's root to the child's, or {@code null} for a child matched on its foreign key. */
    private final TableField<?, ?> through;
    private final ModelQuery<?, ?, C> query;
    private final int maxPerParent;

    /**
     * @throws IllegalArgumentException when {@code field} has both or neither of a foreign key and a {@code through}
     *     path
     * @throws ModelQueryDefinitionException as {@link ModelQuery.Builder#build()} does for the child query, and
     *     {@code MQ1704} for a {@code through} child whose model is grouped
     */
    ChildLoad(ChildField<M, C> field, FetchPlan<C> plan, ChildQuery<C> load) {
        this.field = field;
        this.foreignKey = Objects.requireNonNull(field.foreignKey(), "foreignKey").orElse(null);
        this.through = Objects.requireNonNull(field.through(), "through").orElse(null);
        if ((foreignKey == null) == (through == null)) {
            throw new IllegalArgumentException(this + ": a child field needs exactly one of foreignKey() and "
                    + "through(), found " + (through == null ? "neither" : "both"));
        }
        this.query = field.query().forChildren(through == null ? plan.selecting(foreignKey) : plan, load.filters(),
                load.order()).build();
        if (through != null && query.isGrouped()) {
            // The processor refuses it (MQ3406): the parent's key would need a GROUP BY of its own.
            throw new ModelQueryDefinitionException(MqCode.MQ1704, this + ": a child loaded through an association "
                    + "path can't be grouped, since its rows would have to be grouped by the parent's key too");
        }
        this.maxPerParent = load.bound();
    }

    /** The child field the load fills. */
    public ChildField<M, C> field() {
        return field;
    }

    /**
     * The child query: the child plan's selection plus the {@code foreignKey}, the child filters and the child order,
     * without the key match nor the primary-key tie-breaker. Its fetch plan is the child plan, with that column added.
     * A {@code through} child's query adds no column, and runs as {@link #build} builds it.
     */
    public ModelQuery<?, ?, C> query() {
        return query;
    }

    /**
     * The {@code MODEL} statement of the child query, without the key match: rooted at the child's entity, or, for a
     * {@code through} child, at the parent's, joined along the path and selecting the parent's key last (D-100).
     *
     * @throws ModelQueryDefinitionException {@code MQ1205} when the child's customizer changed the ordering or the
     *     grouping
     */
    public BuiltQuery<C> build(CriteriaBuilder cb, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        return through == null
                ? query.buildQuery(cb, Phase.MODEL, options)
                : query.buildThroughQuery(cb, options, through, field.key());
    }

    /** The expression of {@code built} the parent keys are matched against, in an {@code IN}. */
    public Expression<?> key(BuiltQuery<C> built) {
        return through == null ? foreignKey.path(built.joins()) : built.parentKey();
    }

    /**
     * The parent key {@code row}, built from {@code tuple} of {@code built}, belongs to: its attribute value, before
     * any converter (R-FCH-05).
     */
    public Object key(BuiltQuery<C> built, Tuple tuple, Row row) {
        return through == null ? row.raw(foreignKey) : built.parentKey(tuple);
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
