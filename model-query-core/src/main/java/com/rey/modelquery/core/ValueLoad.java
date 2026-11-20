package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.PluralJoin;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One value child a {@link FetchPlan} loads: its field and its bound. Its statement is rooted at the parent's root
 * entity, joined {@code INNER} along the field's owner path and then to the element collection, a list join for an
 * {@code @OrderColumn}; it selects the parent's key and the value, and orders by the key, the {@code index()} and the
 * value where the field says so. An executor adds the key match. The rows are a bag: none is deduplicated (D-247).
 *
 * @param <M> the parent model
 * @param <E> the element type
 * @implSpec R-FCH-11, R-FCH-19, D-247
 */
@EngineFacing
@Incubating
public final class ValueLoad<M, E> {

    private final ValueChildField<M, E> field;
    private final int maxPerParent;

    ValueLoad(ValueChildField<M, E> field, ValueChildQuery<E> query) {
        this.field = field;
        this.maxPerParent = query.bound();
    }

    /** The value child field the load fills. */
    public ValueChildField<M, E> field() {
        return field;
    }

    /** The most values per parent, counted in rows, or 0 for no bound (R-FCH-11, D-247). */
    public int maxPerParent() {
        return maxPerParent;
    }

    /**
     * The statement, without the key match: rooted at the parent's entity, selecting the parent's key, then the value.
     *
     * @throws ModelQueryDefinitionException {@code MQ1713} when it joins a collection besides the element collection,
     *     whose rows would repeat the values (D-247)
     */
    public Statement build(CriteriaBuilder cb, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<?> root = query.from(field.owner().pathRoot());
        JoinContext joins = JoinContext.readOf(root, cb, query, options);
        From<?, ?> owner = field.owner().resolve(joins);
        Join<?, ?> values = field.isOrdered() ? owner.joinList(field.attribute(), JoinType.INNER)
                : owner.join(field.attribute(), JoinType.INNER);
        List<String> toMany = new ArrayList<>();
        pluralJoins(root, values, toMany);
        if (!toMany.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1713, this + ": the value child's statement joins the "
                    + "collection " + String.join(", ", toMany) + " besides the element collection '"
                    + field.attribute() + "', which would repeat its values; reach the element collection through "
                    + "to-one associations only");
        }
        Expression<?> key = field.key().path(joins);
        query.multiselect(key, values);
        List<Order> order = new ArrayList<>();
        order.add(cb.asc(key));
        if (field.isOrdered()) {
            order.add(cb.asc(((ListJoin<?, ?>) values).index()));
        }
        if (field.ordersByValue()) {
            order.add(cb.asc(values));
        }
        query.orderBy(order);
        return new Statement(query, joins, key);
    }

    /** Adds the attribute of every plural join under {@code from} other than {@code values} to {@code found}. */
    static void pluralJoins(From<?, ?> from, Join<?, ?> values, List<String> found) {
        for (Join<?, ?> join : from.getJoins()) {
            if (join != values && join instanceof PluralJoin<?, ?, ?>) {
                found.add("'" + join.getAttribute().getName() + "'");
            }
            pluralJoins(join, values, found);
        }
    }

    /**
     * {@code statement}'s key match, the parent keys' attribute values {@code IN (keys)}. Under inline value handling
     * each key is its own explicit parameter, typed by the key column's mapping (R-FLT-08, D-177).
     */
    public Predicate keyIn(Statement statement, List<Object> keys) {
        JoinContext joins = statement.joins();
        if (!joins.renderOptions().inlinesValues()) {
            return statement.key().in(keys);
        }
        ColumnField<?, ?, ?> column = field.key();
        return statement.key().in(keys.stream()
                .map(key -> ValueOperands.attributeOperand(joins, column, statement.key(), key, keyLabel()))
                .toArray(Expression<?>[]::new));
    }

    /**
     * Under inline value handling, {@code MQ1502} as {@link #keyIn} raises it, decided from the key column's type
     * alone, so an executor checks it before the parent's statement (R-FLT-08, D-177). A no-op otherwise.
     */
    public void requireBindableKeys(Statement statement) {
        RenderOptions options = statement.joins().renderOptions();
        if (options.inlinesValues()) {
            ValueOperands.requireBindable(options, statement.key(), ColumnField.boxed(field.key().attributeType()),
                    keyLabel());
        }
    }

    private String keyLabel() {
        return field.key() + ": the fetch-plan key of " + this + " under inline value handling";
    }

    /** The parent model and the field, the way a message names the load. */
    @Override
    public String toString() {
        return field.key().model().getSimpleName() + "." + field.name();
    }

    /**
     * A value child's statement: the query, selecting the parent's key at 0 and the value at 1, the joins it resolved
     * against, and the parent's key expression.
     *
     * @param query the statement, without the key match
     * @param joins its join context, whose recorded values an executor binds
     * @param key the parent's key, the attribute value before any converter (R-FCH-05)
     */
    @EngineFacing
    @Incubating
    public record Statement(CriteriaQuery<Tuple> query, JoinContext joins, Expression<?> key) {}
}
