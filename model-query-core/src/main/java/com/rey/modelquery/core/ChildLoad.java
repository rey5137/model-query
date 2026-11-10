package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One child a {@link FetchPlan} loads: its field, the child query it runs and its bound. The child query is built
 * when the plan names the child, so it is checked once there, not per page: it selects the child plan's selection and
 * the child's {@code foreignKey}, which a load reads from each child row, and carries the {@link ChildQuery} filters
 * and order. A {@code through} child's query is rooted at the parent's entity and selects the parent's key instead
 * (R-FCH-14, D-100). An executor adds the key match and the primary-key tie-breaker.
 *
 * @param <M> the parent model
 * @param <C> the child model
 * @implSpec R-FCH-04, R-FCH-05, R-FCH-11, R-FCH-14, R-FCH-18, R-FLT-08
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
    /** How the load groups its keys, or {@code null} for no grouping (R-FCH-18). */
    private final KeyGroups<?, ?> keyGroups;

    /**
     * @throws IllegalArgumentException when {@code field} has both or neither of a foreign key and a {@code through}
     *     path
     * @throws ModelQueryDefinitionException as {@link ModelQuery.Builder#build()} does for the child query,
     *     {@code MQ1704} for a {@code through} child whose model is grouped, and {@code MQ1708} for key groups that
     *     don't match the child (R-FCH-18)
     */
    ChildLoad(ChildField<M, C> field, FetchPlan<C> plan, ChildQuery<C> load) {
        this.field = field;
        this.foreignKey = Objects.requireNonNull(field.foreignKey(), "foreignKey").orElse(null);
        this.through = Objects.requireNonNull(field.through(), "through").orElse(null);
        if ((foreignKey == null) == (through == null)) {
            throw new IllegalArgumentException(this + ": a child field needs exactly one of foreignKey() and "
                    + "through(), found " + (through == null ? "neither" : "both"));
        }
        this.keyGroups = load.groups();
        var builder = field.query().forChildren(through == null ? plan.selecting(foreignKey) : plan, load.filters(),
                load.order());
        if (keyGroups != null) {
            checkKeyGroups();
            builder = builder.perRound(keyGroups, this, toMany());
        }
        try {
            this.query = builder.build();
        } catch (ParamReads.Unbound e) {
            if (!field.query().keyGroupsBind(e.param())) {
                throw e;
            }
            // The child model's own key groups group its byKeys and one(q, key) only, so the param they bind is
            // unbound here.
            throw new ModelQueryDefinitionException(MqCode.MQ1512, e.getMessage().substring(
                    MqCode.MQ1512.code().length() + 2) + "; the child model's keyGroups apply only to its byKeys and "
                    + "one(q, key), so set ChildQuery#keyGroups for the child load", e);
        }
        if (through != null && query.isGrouped()) {
            // The processor refuses it (MQ3406): the parent's key would need a GROUP BY of its own.
            throw new ModelQueryDefinitionException(MqCode.MQ1704, this + ": a child loaded through an association "
                    + "path can't be grouped, since its rows would have to be grouped by the parent's key too");
        }
        this.maxPerParent = load.bound();
    }

    /**
     * {@code MQ1708} for key groups whose key type isn't the matched key's attribute type, or that end in
     * {@code verifyMissing} with no {@code bind} or on a to-many child, whose key isn't unique per row (R-FCH-18,
     * D-206, D-209).
     */
    private void checkKeyGroups() {
        Class<?> keyType = ColumnField.boxed(keyColumn().attributeType());
        if (!keyGroups.keyType().equals(keyType)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1708, this + ": the key groups take a "
                    + keyGroups.keyType().getSimpleName() + " key, and the key " + keyColumn() + " is a "
                    + keyType.getSimpleName() + "; group on the matched key's attribute type");
        }
        if (keyGroups.verifiesMissing() && keyGroups.params().isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1708, this + ": verifyMissing(all) on key groups that "
                    + "bind nothing, so nothing can exclude a row; end them in assumeInRange()");
        }
        if (keyGroups.verifiesMissing() && toMany()) {
            String matched = through == null ? "matched on its foreignKey " + foreignKey
                    : "loaded as a List through " + through;
            throw new ModelQueryDefinitionException(MqCode.MQ1708, this + ": verifyMissing(all) on a child " + matched
                    + ", whose key isn't unique per row, so a short list never shows as a missing key; end the key "
                    + "groups in assumeInRange() and bound the range from below only");
        }
    }

    /**
     * Whether the child's key isn't unique per row: a child matched on its foreign key, or a {@code List} child loaded
     * {@code through} an association path (D-206, D-209).
     */
    private boolean toMany() {
        return through == null || field.isToMany();
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
        return build(cb, options, Map.of());
    }

    /**
     * As {@link #build(CriteriaBuilder, RenderOptions)}, for a round of the {@link #keyGroups()} with
     * {@code values}, the round's group values from {@link KeyGroups#values} or the catch-all's, bound. Without a
     * round's values a group param renders as a parameter nothing binds: fit to check, not to run (R-FCH-18).
     */
    public BuiltQuery<C> build(CriteriaBuilder cb, RenderOptions options, Map<QueryParam<?>, Object> values) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(values, "values");
        return through == null
                ? query.buildQuery(cb, Phase.MODEL, options, values)
                : query.buildThroughQuery(cb, options, through, field.key(), values);
    }

    /**
     * {@code built}'s key match, its expression the parent keys are matched against {@code IN (keys)}, the parent keys'
     * attribute values. Under inline value handling each key is its own explicit parameter, typed by the key column's
     * mapping, else {@code MQ1502} naming the fetch key and the column before any statement (R-FLT-08, D-177).
     */
    public Predicate keyIn(BuiltQuery<C> built, List<Object> keys) {
        Expression<?> expression = keyExpression(built);
        JoinContext joins = built.joins();
        if (!joins.renderOptions().inlinesValues()) {
            return expression.in(keys);
        }
        ColumnField<?, ?, ?> column = keyColumn();
        return expression.in(keys.stream()
                .map(key -> ValueOperands.attributeOperand(joins, column, expression, key, keyLabel(column)))
                .toArray(Expression<?>[]::new));
    }

    /**
     * Under inline value handling, {@code MQ1502} as {@link #keyIn} raises it for a key of {@code built}, decided from
     * the key column's type alone: the keys bind only after the parent statement ran, so an executor checks this
     * before it (R-FLT-08, R-FLT-21, D-177). A no-op under the default setting.
     */
    public void requireBindableKeys(BuiltQuery<C> built) {
        RenderOptions options = built.joins().renderOptions();
        if (options.inlinesValues()) {
            ColumnField<?, ?, ?> column = keyColumn();
            ValueOperands.requireBindable(options, keyExpression(built), ColumnField.boxed(column.attributeType()),
                    keyLabel(column));
        }
    }

    /** The expression of {@code built} the parent keys are matched against. */
    private Expression<?> keyExpression(BuiltQuery<C> built) {
        return through == null ? foreignKey.path(built.joins()) : built.parentKey();
    }

    /** The column whose attribute values the parent keys are: the foreign key, or the parent's key column. */
    private ColumnField<?, ?, ?> keyColumn() {
        return through == null ? foreignKey : field.key();
    }

    /** {@code column} as {@code MQ1502} names the fetch key. */
    private String keyLabel(ColumnField<?, ?, ?> column) {
        return column + ": the fetch-plan key of " + this + " under inline value handling";
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

    /** How the load groups its keys into rounds, or empty when it doesn't (R-FCH-18). */
    public Optional<KeyGroups<?, ?>> keyGroups() {
        return Optional.ofNullable(keyGroups);
    }

    /** The parent model and the child field, the way a message names the load. */
    @Override
    public String toString() {
        return field.key().model().getSimpleName() + "." + field.name();
    }
}
