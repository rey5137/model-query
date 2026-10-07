package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A node in a join path: an immutable definition, not a join. It becomes a Criteria {@link From} only through a
 * {@link JoinContext}, so one constant serves any number of concurrent queries.
 *
 * <p>A join may carry a property: the name of the model field the join fills, which a sort property's path is
 * matched against (R-QRY-14). A generated {@code @Join} table has one; a hand-written join has none unless given one
 * by {@link #named}. It is not part of the join's key.
 *
 * @param <P> the parent table's entity type
 * @param <T> the entity type this node reaches
 * @implSpec R-COL-01, D-55
 */
@Incubating
public final class TableField<P, T> {

    private final Class<T> rootEntity;
    private final TableField<?, P> parent;
    private final String attribute;
    private final JoinType type;
    private final String alias;
    private final BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition;
    /** The key whose non-null value means this join matched a row, or {@code null}; not part of {@link #key}. */
    private final PrimaryKey<?, ?> presenceKey;
    /** The model field the join fills, or {@code null}; not part of {@link #key}. */
    private final String property;
    private final JoinKey key;

    private TableField(
            Class<T> rootEntity,
            TableField<?, P> parent,
            String attribute,
            JoinType type,
            String alias,
            BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition,
            PrimaryKey<?, ?> presenceKey,
            String property) {
        this.rootEntity = rootEntity;
        this.parent = parent;
        this.attribute = attribute;
        this.type = type;
        this.alias = alias;
        this.condition = condition;
        this.presenceKey = presenceKey;
        this.property = property;
        this.key = rootEntity != null
                ? JoinKey.root(rootEntity)
                : new JoinKey(parent.key, attribute, type, alias);
    }

    /** The query's root table. */
    public static <T> TableField<T, T> root(Class<T> entity) {
        return new TableField<>(Objects.requireNonNull(entity, "entity"), null, null, null, "", null, null, null);
    }

    /** A join from {@code parent} along {@code attribute}. */
    public static <P, T> TableField<P, T> join(TableField<?, P> parent, String attribute, JoinType type) {
        return new TableField<>(
                null,
                Objects.requireNonNull(parent, "parent"),
                Objects.requireNonNull(attribute, "attribute"),
                Objects.requireNonNull(type, "type"),
                "",
                null,
                null,
                null);
    }

    /**
     * The same path under its own alias, which resolves to a separate join (R-COL-03).
     *
     * @throws ModelQueryDefinitionException {@code MQ1104} on a root, which is not a join
     */
    public TableField<P, T> as(String alias) {
        Objects.requireNonNull(alias, "alias");
        requireJoin("as(...)");
        return new TableField<>(rootEntity, parent, attribute, type, alias, condition, presenceKey, property);
    }

    /**
     * Adds an {@code ON} condition, applied through {@code Join#on}; requires {@link #as(String)} (R-COL-04).
     *
     * @throws ModelQueryDefinitionException {@code MQ1104} on a root, which is not a join
     */
    public TableField<P, T> on(BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition) {
        Objects.requireNonNull(condition, "condition");
        requireJoin("on(...)");
        return new TableField<>(rootEntity, parent, attribute, type, alias, condition, presenceKey, property);
    }

    /**
     * Names the key whose non-null value means this join matched a row: the primary key of the model read through
     * the join, declared on that model's own root. An ungrouped query that selects a column of the join also selects
     * the key, re-rooted under the join, so a mapper reading it through {@link Row#scoped} tells a LEFT-join miss from
     * a match whose other columns are {@code NULL}. It is kept by {@link #as}, {@link #on}, {@link #named} and
     * {@link #withParent}, and is not part of the join's identity (R-COL-15).
     *
     * @throws ModelQueryDefinitionException {@code MQ1104} on a root, which is not a join
     */
    public TableField<P, T> presentBy(PrimaryKey<?, ?> key) {
        Objects.requireNonNull(key, "key");
        requireJoin("presentBy(...)");
        return new TableField<>(rootEntity, parent, attribute, type, alias, condition, key, property);
    }

    /**
     * This node with {@code property} as the name of the model field the join fills, which a sort property's path is
     * matched against (R-QRY-14). The join key is unchanged. A root's property is never part of a path: a column of
     * the root has only its own property.
     */
    public TableField<P, T> named(String property) {
        return new TableField<>(rootEntity, parent, attribute, type, alias, condition, presenceKey,
                Objects.requireNonNull(property, "property"));
    }

    /**
     * The same node under a different parent, keeping alias, {@code ON} condition, presence key and property; a root
     * is returned as is.
     */
    public TableField<P, T> withParent(TableField<?, P> newParent) {
        if (rootEntity != null) {
            return this; // a root has no parent to replace
        }
        return new TableField<>(
                null, Objects.requireNonNull(newParent, "newParent"), attribute, type, alias, condition, presenceKey,
                property);
    }

    /**
     * Whether {@code o} is a node with the same join key: the same root or parent path, attribute, type and alias. Two
     * nodes that differ only in their {@code ON} condition, presence key or property are equal (CC-IMM-04).
     */
    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof TableField<?, ?> other && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    /**
     * Resolves this node to a {@link From}, creating the join on first use within {@code ctx}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1003} when the path starts at a root the query is not rooted
     *     at, {@code MQ1002} when a join names an attribute its entity does not have
     */
    public From<?, T> resolve(JoinContext ctx) {
        return resolve(Objects.requireNonNull(ctx, "ctx"), null);
    }

    /** {@link #resolve(JoinContext)}, naming {@code owner} (a column, or {@code null}) in its failures. */
    @SuppressWarnings("unchecked")
    From<?, T> resolve(JoinContext ctx, Object owner) {
        if (key.equals(ctx.rootKey())) {
            return (From<?, T>) ctx.root();
        }
        if (rootEntity != null) {
            Class<?> queried = ctx.rootType();
            // Otherwise a column declared on another entity's root would silently read the query root's attribute.
            if (!rootEntity.isAssignableFrom(queried)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1003, prefix(owner) + "sits on " + describe()
                        + ", but the query is rooted at " + queried.getSimpleName());
            }
            return (From<?, T>) ctx.root();
        }
        if (condition != null && alias.isEmpty()) {
            throw new ModelQueryDefinitionException(
                    MqCode.MQ1102,
                    describe() + ": on(...) has no alias; call as(...) so the condition can be compared");
        }
        From<?, P> parentFrom = parent.resolve(ctx, owner);
        return (From<?, T>) ctx.join(key, parentFrom, attribute, type, condition, prefix(owner) + describe());
    }

    /** The entity of a root node, or {@code null} for a join. */
    Class<T> rootEntity() {
        return rootEntity;
    }

    JoinKey key() {
        return key;
    }

    /** The parent node, or {@code null} for a root. */
    TableField<?, P> parent() {
        return parent;
    }

    /** The entity of the root this path starts at. */
    Class<?> pathRoot() {
        TableField<?, ?> node = this;
        while (node.parent != null) {
            node = node.parent;
        }
        return node.rootEntity;
    }

    /**
     * The attributes joined from the root to this node, dotted: empty for a root, {@code customer.address} for a
     * join below a join. An alias and an {@code on} condition are not part of it (R-QRY-14).
     */
    String path() {
        if (parent == null) {
            return "";
        }
        String above = parent.path();
        return above.isEmpty() ? attribute : above + "." + attribute;
    }

    /**
     * The model field names from the root to this node, dotted: empty for a root, {@code customer.address} for a join
     * named {@code address} below a join named {@code customer}; {@code null} when this node or a join above it has no
     * property (R-QRY-14, D-55).
     */
    String propertyPath() {
        if (parent == null) {
            return "";
        }
        String above = parent.propertyPath();
        if (property == null || above == null) {
            return null;
        }
        return above.isEmpty() ? property : above + "." + property;
    }

    /** Whether {@link #on} gave this join a condition. */
    boolean hasCondition() {
        return condition != null;
    }

    /** The key named by {@link #presentBy}, or {@code null}. */
    PrimaryKey<?, ?> presenceKey() {
        return presenceKey;
    }

    /** This path with its root replaced by {@code join}, as {@link JoinKey#reroot} re-roots its key. */
    @SuppressWarnings("unchecked")
    TableField<?, T> under(TableField<?, ?> join) {
        // Sound for a path of the model read through join: its root is the entity join reaches.
        return rootEntity != null ? (TableField<?, T>) join : withParent((TableField<?, P>) parent.under(join));
    }

    /** Whether this node is {@code ancestor} or sits below it, comparing join keys (CC-IMM-04). */
    boolean isAtOrBelow(TableField<?, ?> ancestor) {
        for (TableField<?, ?> node = this; node != null; node = node.parent) {
            if (node.key.equals(ancestor.key)) {
                return true;
            }
        }
        return false;
    }

    /** The keys of this node and its parents, stopping below {@code top}, or below the root when it is null. */
    Set<JoinKey> keysUpTo(JoinKey top) {
        var keys = new HashSet<JoinKey>();
        for (TableField<?, ?> node = this; node.parent != null && !node.key.equals(top); node = node.parent) {
            keys.add(node.key);
        }
        return keys;
    }

    private void requireJoin(String method) {
        if (rootEntity != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1104, describe() + ": " + method
                    + " applies to a join; a root is the query's FROM, not a join");
        }
    }

    private static String prefix(Object owner) {
        return owner == null ? "" : owner + ": ";
    }

    /**
     * The root entity's simple name, or the root entity, the dotted path, the join type and the alias of a join, such
     * as {@code OrderEntity.items (LEFT, alias 'i')}; not API, and it prints no values.
     */
    @Override
    public String toString() {
        if (rootEntity != null) {
            return rootEntity.getSimpleName();
        }
        return pathRoot().getSimpleName() + "." + path() + " (" + type
                + (alias.isEmpty() ? "" : ", alias '" + alias + "'") + ")";
    }

    String describe() {
        if (rootEntity != null) {
            return "root " + rootEntity.getSimpleName();
        }
        return "join '" + attribute + "' (" + type + (alias.isEmpty() ? "" : ", alias '" + alias + "'") + ")";
    }
}
