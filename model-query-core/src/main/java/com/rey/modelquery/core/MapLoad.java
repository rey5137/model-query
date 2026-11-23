package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.MapJoin;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One map child a {@link FetchPlan} loads: its field and its bound. Its statement is rooted at the parent's root
 * entity, joined {@code INNER} along the field's owner path and then to the element collection map; it selects the
 * parent's key, the map's {@code key()} and the {@code value()} (or an element model's columns over {@code value()}),
 * and orders by the parent key only. An executor adds the key match. The entries are collected into a
 * {@code LinkedHashMap} in unspecified row order (D-248).
 *
 * @param <M> the parent model
 * @param <K> the map key type
 * @param <V> the map value type
 * @implSpec R-FCH-20, D-248
 */
@EngineFacing
@Incubating
public final class MapLoad<M, K, V> {

    private final MapChildField<M, K, V> field;
    private final MapChildQuery<K, V> query;
    private final ModelQuery<?, ?, V> elementQuery;

    MapLoad(MapChildField<M, K, V> field, MapChildQuery<K, V> query) {
        if (field.isElementModel() != (field.elementQuery() != null)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1713,
                    "map child " + field.name() + ": isElementModel() is " + field.isElementModel()
                            + " but elementQuery() " + (field.elementQuery() == null ? "is null" : "is set")
                            + "; a field overrides both or neither (D-261)");
        }
        this.field = field;
        this.query = query;
        if (!field.isElementModel() && !query.filters().isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1714, this + ": a map child's filter needs an "
                    + "@ElementModel value; this map has basic values");
        }
        this.elementQuery = field.isElementModel() ? field.elementQuery().forMapChild(query.filters()).build() : null;
    }

    /** The map child field the load fills. */
    public MapChildField<M, K, V> field() {
        return field;
    }

    /** The immutable map child query supplied to the plan (R-FCH-20). */
    public MapChildQuery<K, V> query() {
        return query;
    }

    ModelQuery<?, ?, V> elementQuery() {
        return elementQuery;
    }

    /** The most entries per parent, counted in rows, or 0 for no bound (R-FCH-11, D-248). */
    public int maxPerParent() {
        return query.bound();
    }

    /**
     * The statement, without the key match: rooted at the parent's entity, selecting the parent's key, the map key,
     * then the value (or element model columns).
     *
     * @throws ModelQueryDefinitionException {@code MQ1713} when it joins a collection besides the element collection,
     *     whose rows would repeat its entries (D-248); {@code MQ1001} when the field's {@code keyType()} or, for a
     *     basic value, {@code valueType()} is not the map's (D-260)
     */
    public Statement build(CriteriaBuilder cb, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<?> root = query.from(field.owner().pathRoot());
        JoinContext joins = JoinContext.readOf(root, cb, query,
                elementQuery == null ? options : elementQuery.mapChildOptions(options));
        From<?, ?> owner = field.owner().resolve(joins);
        MapJoin<?, ?, ?> mapJoin = (MapJoin<?, ?, ?>) owner.join(field.attribute(), JoinType.INNER);
        ValueLoad.checkType(this, "key", field.keyType(), mapJoin.key().getJavaType(), field.attribute());
        if (!field.isElementModel()) {
            ValueLoad.checkType(this, "value", field.valueType(), mapJoin.value().getJavaType(), field.attribute());
        }
        Expression<?> key = field.key().path(joins);
        Expression<?> mapKey = mapJoin.key();
        List<Selection<?>> selections = new ArrayList<>();
        selections.add(key);
        selections.add(mapKey);
        RowSelection rowSelection = null;
        Function<Row, ?> mapper = null;
        if (field.isElementModel()) {
            JoinContext child = joins.rootedAt(mapJoin, elementQuery.rootEntity());
            rowSelection = elementQuery.modelSelection();
            selections.addAll(rowSelection.selections(child));
            mapper = elementQuery.mapping();
            List<Predicate> filters = elementQuery.mapChildPredicates(child);
            if (!filters.isEmpty()) {
                query.where(filters.toArray(Predicate[]::new));
            }
            child.checkJoinFilters();
        } else {
            selections.add(mapJoin.value());
        }
        List<String> toMany = new ArrayList<>();
        ValueLoad.pluralJoins(root, mapJoin, toMany);
        if (!toMany.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1713, this + ": the map child's statement joins the "
                    + "collection " + String.join(", ", toMany) + " besides the element collection '"
                    + field.attribute() + "', which would repeat its entries; reach the element collection through "
                    + "to-one associations only");
        }
        query.multiselect(selections);
        query.orderBy(cb.asc(key));
        return new Statement(query, joins, key, rowSelection, mapper, elementQuery);
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
     * A map child's statement: the query, selecting the parent's key at 0, the map key at 1, and the value (or
     * element model columns), the joins it resolved against, and the parent's key expression.
     */
    @EngineFacing
    @Incubating
    public record Statement(CriteriaQuery<Tuple> query, JoinContext joins, Expression<?> key,
            RowSelection rowSelection, Function<Row, ?> mapper, ModelQuery<?, ?, ?> elementQuery) {}
}
