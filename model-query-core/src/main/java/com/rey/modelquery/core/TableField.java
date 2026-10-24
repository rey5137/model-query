package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.metamodel.PluralAttribute;
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
    /** Whether this root is a source's, read by field name from a CTE rather than from an entity (R-SRC-10). */
    private final boolean source;
    /** The bound source or entity join name, or {@code null} for every other node (R-SRC-21, R-SRC-23). */
    private final String joinName;
    private final JoinKey key;
    private final Class<T> subtype;
    private final TableField<?, ?> treatOrigin;

    Class<T> treatedType() {
        return subtype;
    }

    private TableField(
            Class<T> rootEntity,
            TableField<?, P> parent,
            String attribute,
            JoinType type,
            String alias,
            BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition,
            PrimaryKey<?, ?> presenceKey,
            String property,
            boolean source) {
        this(rootEntity, parent, attribute, type, alias, condition, presenceKey, property, source, null, null, null);
    }

    private TableField(Class<T> rootEntity, TableField<?, P> parent, String attribute, JoinType type, String alias,
            BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition, PrimaryKey<?, ?> presenceKey,
            String property, boolean source, Class<T> subtype, TableField<?, ?> treatOrigin, String joinName) {
        this.rootEntity = rootEntity;
        this.parent = parent;
        this.attribute = attribute;
        this.type = type;
        this.alias = alias;
        this.condition = condition;
        this.presenceKey = presenceKey;
        this.property = property;
        this.source = source;
        this.subtype = subtype;
        this.treatOrigin = treatOrigin;
        this.joinName = joinName;
        this.key = joinName != null ? source ? JoinKey.joinedSource(rootEntity, joinName)
                : JoinKey.joinedEntity(rootEntity, joinName)
                : rootEntity != null ? JoinKey.root(rootEntity)
                : new JoinKey(parent.key, subtype == null ? attribute : subtype.getName(), type, alias);
    }

    /** A named entity join, resolved only through the statement's bindings (R-SRC-23). */
    static <E> TableField<E, E> joinedEntity(String name, Class<E> entity) {
        return new TableField<>(entity, null, null, null, "", null, null, null, false, null, null, name);
    }

    /** The query's root table. */
    public static <T> TableField<T, T> root(Class<T> entity) {
        return new TableField<>(Objects.requireNonNull(entity, "entity"), null, null, null, "", null, null, null,
                false);
    }

    /**
     * This node read as a strict entity subtype, sharing its treated Criteria node per statement (R-COL-34).
     *
     * @throws ModelQueryDefinitionException {@code MQ1106} for an improper subtype, a source or another treat
     */
    @Incubating
    public <S extends T> TableField<T, S> treat(Class<S> subtype) {
        Objects.requireNonNull(subtype, "subtype");
        if (this.subtype != null || source) {
            throw new ModelQueryDefinitionException(MqCode.MQ1106,
                    describe() + ": treat(...) requires an untreated entity root or join");
        }
        if (rootEntity != null) {
            requireStrictSubtype(rootEntity, subtype);
        }
        return new TableField<>(null, this, null, null, "", null, presenceKey, property, false, subtype, this, null);
    }

    /**
     * The root table of a query over a source: {@code model} is the source model, whose fields a CTE aliases by field
     * name (R-SRC-10). It is only generated, for a {@code @QueryModel} whose {@code root} names a {@code @SourceModel};
     * a hand-written {@link ColumnField} over it is {@code MQ1905}.
     */
    @Incubating
    public static <S> TableField<S, S> source(Class<S> model) {
        return new TableField<>(Objects.requireNonNull(model, "model"), null, null, null, "", null, null, null, true);
    }

    /**
     * The table of the columns of the source joined as {@code name}, a source model's fields read by field name from
     * the join to its CTE (R-SRC-21). {@link SourceJoin#root()} returns it; it resolves to the join the statement
     * made for {@code name}, never to the query's root.
     */
    static <S> TableField<S, S> joinedSource(String name, Class<S> model) {
        return new TableField<>(model, null, null, null, "", null, null, null, true, null, null, name);
    }

    /**
     * A join from {@code parent} along {@code attribute}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1905} when {@code parent} is a source root, which has no
     *     associations to join (R-SRC-10)
     */
    public static <P, T> TableField<P, T> join(TableField<?, P> parent, String attribute, JoinType type) {
        Objects.requireNonNull(parent, "parent");
        if (parent.isSource()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1905,
                    "join(...) takes a table of an entity root; " + parent.describe()
                            + " is a source root, which the outer query reads by field name");
        }
        return new TableField<>(
                null,
                parent,
                Objects.requireNonNull(attribute, "attribute"),
                Objects.requireNonNull(type, "type"),
                "",
                null,
                null,
                null,
                false);
    }

    /**
     * The same path under its own alias, which resolves to a separate join (R-COL-03).
     *
     * @throws ModelQueryDefinitionException {@code MQ1104} on a root, which is not a join
     */
    public TableField<P, T> as(String alias) {
        Objects.requireNonNull(alias, "alias");
        requireJoin("as(...)");
        requireUntreated("as(...)");
        return new TableField<>(rootEntity, parent, attribute, type, alias, condition, presenceKey, property, false);
    }

    /**
     * Adds an {@code ON} condition, applied through {@code Join#on}; requires {@link #as(String)} (R-COL-04).
     *
     * @throws ModelQueryDefinitionException {@code MQ1104} on a root, which is not a join
     */
    public TableField<P, T> on(BiFunction<From<?, T>, CriteriaBuilder, Predicate> condition) {
        Objects.requireNonNull(condition, "condition");
        requireJoin("on(...)");
        requireUntreated("on(...)");
        return new TableField<>(rootEntity, parent, attribute, type, alias, condition, presenceKey, property, false);
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
        return new TableField<>(rootEntity, parent, attribute, type, alias, condition, key, property, false,
                subtype, treatOrigin, null);
    }

    /**
     * This node with {@code property} as the name of the model field the join fills, which a sort property's path is
     * matched against (R-QRY-14). The join key is unchanged. A root's property is never part of a path: a column of
     * the root has only its own property.
     *
     * @throws ModelQueryDefinitionException {@code MQ1106} after {@link #treat(Class)}: a treated node's path is its
     *     join's, so it is named before the treat (R-COL-34)
     */
    public TableField<P, T> named(String property) {
        Objects.requireNonNull(property, "property");
        requireUntreated("named(...)");
        return new TableField<>(rootEntity, parent, attribute, type, alias, condition, presenceKey, property,
                source, subtype, treatOrigin, joinName);
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
                property, false, subtype, treatOrigin, null);
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
    From<?, T> resolve(JoinContext ctx, Object owner) {
        return resolve(ctx, owner, owner != null);
    }

    /** {@code read} when {@code owner} reads a column of this node, rather than of a join below it (D-157). */
    @SuppressWarnings("unchecked")
    private From<?, T> resolve(JoinContext ctx, Object owner, boolean read) {
        if (subtype != null) {
            Class<?> declared = treatOrigin.declaredType(ctx.cb());
            requireStrictSubtype(declared, subtype);
            try {
                ctx.cb().createQuery().from(subtype);
            } catch (IllegalArgumentException e) {
                throw new ModelQueryDefinitionException(MqCode.MQ1106, prefix(owner) + describe()
                        + ": " + subtype.getSimpleName() + " is not a mapped entity subtype of "
                        + declared.getSimpleName(), e);
            }
            From<?, ?> reached = parent.rootEntity != null && parent.joinName == null
                    ? ctx.root() : parent.resolve(ctx, owner, false);
            return ctx.treat(key, reached, subtype, prefix(owner) + describe(), read);
        }
        if (joinName != null) {
            return (From<?, T>) (source ? ctx.joinedSource(key, prefix(owner) + describe())
                    : ctx.joinedEntity(key, prefix(owner) + describe()));
        }
        if (key.equals(ctx.rootKey())) {
            return (From<?, T>) ctx.root();
        }
        if (rootEntity != null) {
            Class<?> queried = ctx.rootType();
            // Otherwise a column declared on another entity's root would silently read the query root's attribute.
            if (!rootEntity.isAssignableFrom(queried)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1003, prefix(owner) + "sits on " + describe()
                        + ", but the query is rooted at " + queried.getSimpleName()
                        + (queried.isAssignableFrom(rootEntity)
                                ? "; read subtype columns through ROOT.treat(" + rootEntity.getSimpleName() + ".class)"
                                : ""));
            }
            return (From<?, T>) ctx.root();
        }
        if (condition != null && alias.isEmpty()) {
            throw new ModelQueryDefinitionException(
                    MqCode.MQ1102,
                    describe() + ": on(...) has no alias; call as(...) so the condition can be compared");
        }
        From<?, P> parentFrom = parent.resolve(ctx, owner, false);
        From<?, T> joined = (From<?, T>) ctx.join(key, parentFrom, attribute, type, condition,
                prefix(owner) + describe());
        TableField<?, ?> origin = parent;
        while (origin.parent != null) {
            origin = origin.parent;
        }
        if (origin.isJoinedEntity() && joined instanceof jakarta.persistence.criteria.Join<?, ?> join
                && join.getAttribute() instanceof jakarta.persistence.metamodel.PluralAttribute<?, ?, ?>) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, prefix(owner) + describe()
                    + ": a path below an entity join must be to-one");
        }
        return joined;
    }

    /** The entity of a root node, or {@code null} for a join. */
    Class<T> rootEntity() {
        return rootEntity;
    }

    /**
     * Whether this node is a source's root, whose columns an outer query reads by field name (R-SRC-10); never
     * {@code true} for a join.
     */
    boolean isSource() {
        return source;
    }

    /** Whether this node is the table of a joined source's columns (R-SRC-21), rather than the query's root. */
    boolean isJoinedSource() {
        return source && joinName != null;
    }

    /** Whether this is a named entity join rather than an entity root (R-SRC-23). */
    boolean isJoinedEntity() {
        return !source && joinName != null;
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
        if (subtype != null) {
            return parent.path();
        }
        if (parent == null) {
            return "";
        }
        String above = parent.path();
        return above.isEmpty() ? attribute : above + "." + attribute;
    }

    /**
     * The model field names from the root to this node, dotted: empty for a root, {@code customer.address} for a join
     * named {@code address} below a join named {@code customer}; {@code null} when this node or a join above it has no
     * property (R-QRY-14, D-55). Unnamed association nodes below an entity join add no property segment, so a
     * column there resolves by its model field name (R-SRC-23).
     */
    String propertyPath() {
        if (subtype != null) {
            return parent.propertyPath();
        }
        if (parent == null) {
            return "";
        }
        String above = parent.propertyPath();
        if (property == null && above != null) {
            JoinKey root = key;
            while (root.parent() != null) {
                root = root.parent();
            }
            if (root.entity()) {
                return above;
            }
        }
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
        if (joinName != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1003, describe()
                    + ": a named source or entity join cannot be re-rooted under " + join.describe());
        }
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
        if (rootEntity != null || subtype != null && parent.rootEntity != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1104, describe() + ": " + method
                    + " applies to a join; a root is the query's FROM, not a join");
        }
    }

    private void requireUntreated(String method) {
        if (subtype != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1106,
                    describe() + ": apply " + method + " to the join before treat(...)");
        }
    }

    private Class<?> declaredType(CriteriaBuilder cb) {
        if (rootEntity != null || subtype != null) {
            return rootEntity != null ? rootEntity : subtype;
        }
        var entity = cb.createQuery().from(parent.declaredType(cb)).getModel();
        try {
            var target = entity.getAttribute(attribute);
            return target instanceof PluralAttribute<?, ?, ?> plural
                    ? plural.getElementType().getJavaType() : target.getJavaType();
        } catch (IllegalArgumentException e) {
            throw new ModelQueryDefinitionException(MqCode.MQ1002,
                    describe() + ": " + entity.getJavaType().getSimpleName()
                            + " has no attribute '" + attribute + "'", e);
        }
    }

    private static void requireStrictSubtype(Class<?> entity, Class<?> subtype) {
        if (entity.equals(subtype) || !entity.isAssignableFrom(subtype)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1106, "treat(" + subtype.getSimpleName()
                    + ".class) requires a strict subtype of " + entity.getSimpleName());
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
        if (subtype != null) {
            return parent + ".treat(" + subtype.getSimpleName() + ".class)";
        }
        if (joinName != null) {
            return rootEntity.getSimpleName() + " (joined as '" + joinName + "')";
        }
        if (rootEntity != null) {
            return rootEntity.getSimpleName();
        }
        return pathRoot().getSimpleName() + "." + path() + " (" + type
                + (alias.isEmpty() ? "" : ", alias '" + alias + "'") + ")";
    }

    String describe() {
        if (subtype != null) {
            return parent.describe() + ".treat(" + subtype.getSimpleName() + ".class)";
        }
        if (joinName != null) {
            return "joined " + (source ? "source" : "entity") + " '" + joinName + "' ("
                    + rootEntity.getSimpleName() + ")";
        }
        if (rootEntity != null) {
            return "root " + rootEntity.getSimpleName();
        }
        return "join '" + attribute + "' (" + type + (alias.isEmpty() ? "" : ", alias '" + alias + "'") + ")";
    }
}
