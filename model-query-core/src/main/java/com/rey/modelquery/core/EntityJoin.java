package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * An entity joined on its full id to columns of one joined source (R-SRC-23), or to columns of the root, of an entity
 * join bound before it, and of their to-one paths (R-SRC-25), with an optional static {@link #when} term. Immutable, so
 * a constant (INV-9).
 *
 * @param <M> the outer model
 * @param <E> the joined entity
 * @implSpec R-SRC-23, R-SRC-24, R-SRC-25, D-193, D-197, D-215
 */
@Incubating
public final class EntityJoin<M, E> {

    private final String name;
    private final Class<E> entity;
    private final JoinKind kind;
    private final List<On<M>> on;
    private final TableField<E, E> root;
    private final When<M> when;

    private EntityJoin(String name, Class<E> entity, JoinKind kind, List<On<M>> on, When<M> when,
            TableField<?, ?> within) {
        this.name = name;
        this.entity = entity;
        this.kind = kind;
        this.on = on;
        this.when = when;
        TableField<E, E> joined = TableField.joinedEntity(name, entity);
        this.root = within == null ? joined : joined.within(within);
    }

    /** The full basic id, checked on first resolution, when the metamodel is available (D-193 Resolution 8). */
    @Incubating
    void checkIds(EntityType<?> target) {
        Map<String, SingularAttribute<?, ?>> ids = idAttributes(target);
        Set<String> paired = new HashSet<>();
        for (On<M> pair : on) {
            SingularAttribute<?, ?> id = ids.get(pair.idAttribute());
            if (id == null || id.isAssociation() || !paired.add(pair.idAttribute())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, toString()
                        + ": pair '" + pair.idAttribute() + "' must name a distinct basic id attribute");
            }
            if (pair.anchor().attributeType() != ColumnField.boxed(id.getJavaType())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, toString()
                        + ": pair '" + pair.idAttribute() + "' has different boxed types: "
                        + pair.anchor().attributeType().getName() + " and " + id.getJavaType().getName());
            }
        }
        if (!paired.equals(ids.keySet()) || ids.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, toString()
                    + ": pairs must cover every basic id attribute " + ids.keySet());
        }
    }

    @Incubating
    static Map<String, SingularAttribute<?, ?>> idAttributes(EntityType<?> entity) {
        if (entity == null) {
            throw new ModelQueryConfigurationException(MqCode.MQ4014,
                    "The provider exposes no entity metamodel for the joined entity");
        }
        Map<String, SingularAttribute<?, ?>> ids = new TreeMap<>();
        for (SingularAttribute<?, ?> attribute : entity.getSingularAttributes()) {
            if (attribute.isId()) {
                idAttributes(attribute.getName(), attribute, ids);
            }
        }
        return ids;
    }

    @Incubating
    private static void idAttributes(String path, SingularAttribute<?, ?> attribute,
            Map<String, SingularAttribute<?, ?>> ids) {
        if (attribute.getType().getPersistenceType() == Type.PersistenceType.EMBEDDABLE) {
            for (SingularAttribute<?, ?> part : ((ManagedType<?>) attribute.getType()).getSingularAttributes()) {
                idAttributes(path + "." + part.getName(), part, ids);
            }
        } else {
            ids.put(path, attribute);
        }
    }

    /**
     * The join {@code name} of {@code entity}, of {@code kind}, on the id pairs in declaration order (R-SRC-23).
     *
     * @throws ModelQueryDefinitionException {@code MQ1911} for a blank name or no pairs
     */
    @Incubating
    public static <M, E> EntityJoin<M, E> of(String name, Class<E> entity, JoinKind kind, List<On<M>> on) {
        Objects.requireNonNull(name, "name");
        List<On<M>> pairs = List.copyOf(Objects.requireNonNull(on, "on"));
        if (name.isBlank() || pairs.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911,
                    "entity join '" + name + "' needs a name and at least one on(...) pair");
        }
        return new EntityJoin<>(name, Objects.requireNonNull(entity, "entity"), Objects.requireNonNull(kind, "kind"),
                pairs, null, null);
    }

    /**
     * This join with the static term {@code column = value} ANDed into its one ON, as a statement parameter: a
     * polymorphic reference's type column, so an id that collides across tables matches only its own (R-SRC-25,
     * D-197). The value is a definition constant (INV-9).
     *
     * @throws ModelQueryDefinitionException {@code MQ1911} for a second term, a converted column, a column off the
     *     root, an entity join and their to-one paths (on a source-anchored join, an entity join's column too), or a
     *     value that is not a bindable instance of the column's boxed attribute type
     */
    @Incubating
    public <C> EntityJoin<M, E> when(ColumnField<M, ?, C> column, C value) {
        Objects.requireNonNull(column, "column");
        String where = this + ": when(" + column.name() + ", ...)";
        if (when != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": the join already has the term on "
                    + when.column().name() + "; a join takes one when(...) term");
        }
        if (column.isConverted()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": the column is converted; the term "
                    + "compares database values");
        }
        Class<?> type = ColumnField.boxed(column.attributeType());
        if (value == null || !type.isInstance(value)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": the value must be a non-null "
                    + type.getSimpleName() + (value == null ? "" : ", not a " + value.getClass().getSimpleName()));
        }
        if (Expr.unbindable(value)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": a " + value.getClass().getSimpleName()
                    + " value does not bind as one statement parameter (R-COL-18); compare a String, number, "
                    + "boolean or temporal column");
        }
        checkColumnAnchor(where, column, !anchoredOnSource());
        if (root.within() != null) {
            requireReadUnder(this, column, root.within());
        }
        return new EntityJoin<>(name, entity, kind, on, new When<>(column, value), root.within());
    }

    /**
     * This join registered under {@code join}, the {@code @Join} field whose nested model declared it: a column of
     * {@link #root()} then also selects the presence key of {@code join} and of every join above it, and a
     * {@link JoinScope} maps the nested model's join to this one (D-216). The link is not part of the root's key.
     * Pass the {@code @Join} field's own constant, whose {@code presentBy} the walk reads (a copy with an equal key
     * but no {@code presentBy} selects no key, and a scope refuses it), and build this join's columns from the
     * {@link #root()} returned here (a column built from the root before {@code within} still selects the link's keys,
     * as the presence walk reads {@code within} from the join the query binds, D-220).
     *
     * @throws ModelQueryDefinitionException {@code MQ1107} for a join already registered under one, or an anchor or
     *     {@link #when} column that is not read at or below {@code join}
     */
    @Incubating
    public EntityJoin<M, E> within(TableField<?, ?> join) {
        Objects.requireNonNull(join, "join");
        if (root.within() != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1107, this + ": within(" + join.describe()
                    + "): already registered within " + root.within().describe());
        }
        for (On<M> pair : on) {
            requireReadUnder(this, pair.anchor(), join);
        }
        if (when != null) {
            requireReadUnder(this, when.column(), join);
        }
        return new EntityJoin<>(name, entity, kind, on, when, join);
    }

    /** {@code column}, an anchor of {@code owner}, is read at or below {@code join}, or {@code MQ1107} (D-216). */
    static void requireReadUnder(Object owner, ColumnField<?, ?, ?> column, TableField<?, ?> join) {
        if (!column.table().isReadUnder(join)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1107, owner + ": " + column.name() + " on "
                    + column.table().describe() + " is not read at or below " + join.describe()
                    + ", the @Join the join is registered within");
        }
    }

    /** Pairs {@code anchor}, a joined source's or the root's column, with an entity id attribute path (R-SRC-23). */
    @Incubating
    public static <M> On<M> on(ColumnField<M, ?, ?> anchor, String idAttribute) {
        return new On<>(anchor, idAttribute);
    }

    /** The name, unique among the query's source and entity joins (R-SRC-23). */
    @Incubating
    public String name() {
        return name;
    }

    /** The joined entity (R-SRC-23). */
    @Incubating
    public Class<E> entity() {
        return entity;
    }

    /** LEFT keeps an unmatched outer row; INNER drops it, even when its source was unmatched (R-SRC-24). */
    @Incubating
    public JoinKind kind() {
        return kind;
    }

    /** The immutable id pairs, in declaration order (R-SRC-23). */
    @Incubating
    public List<On<M>> on() {
        return on;
    }

    /** The static term {@link #when} added, or empty (R-SRC-25). */
    @Incubating
    public Optional<When<M>> when() {
        return Optional.ofNullable(when);
    }

    /** Whether the pairs read a joined source, rather than the root and its to-one paths (R-SRC-23, R-SRC-25). */
    boolean anchoredOnSource() {
        return on.get(0).anchor.table().isJoinedSource();
    }

    /** The joined entity's table, keyed by its name, for columns and to-one paths (R-SRC-23). */
    @Incubating
    public TableField<E, E> root() {
        return root;
    }

    @Override
    @Incubating
    public String toString() {
        return "EntityJoin[" + name + ": " + kind + " " + entity.getSimpleName() + "]";
    }

    /**
     * The checks that need no metamodel, run when the query is built (D-193, D-197, Resolution 8): every pair reads
     * one bound joined source, or every pair reads the root, an entity join bound before {@code join} in
     * {@code joins}, or a to-one path from either, unconverted (D-215).
     */
    static void check(String model, EntityJoin<?, ?> join, Set<JoinKey> sources, TableField<?, ?> root,
            List<? extends EntityJoin<?, ?>> joins) {
        String where = model + ": entity join '" + join.name + "' of " + join.entity.getSimpleName();
        if (root.isSource()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": a query rooted at a source joins no "
                    + "entity; " + root.describe() + " is a source root");
        }
        boolean onSource = join.anchoredOnSource();
        JoinKey anchor = null;
        for (On<?> pair : join.on) {
            TableField<?, ?> table = pair.anchor.table();
            if (pair.anchor.isConverted()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": anchor " + pair.anchor.name()
                        + " is converted; the join compares database values");
            }
            if (table.isJoinedSource() != onSource) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": on(...) pairs mix a joined "
                        + "source's columns with the root's; all pairs must read one joined source, or the root and "
                        + "its to-one paths");
            }
            if (!onSource) {
                checkColumnAnchor(where + ": anchor " + pair.anchor.name(), pair.anchor, true);
                checkChain(where + ": anchor " + pair.anchor.name(), join, pair.anchor, joins);
                continue;
            }
            if (!sources.contains(table.key())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": anchor " + pair.anchor.name()
                        + " must read a joined source bound by this query; " + table.describe() + " is not bound");
            }
            if (anchor != null && !anchor.equals(table.key())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where
                        + ": on(...) pairs span two joined sources; all pairs must read one joined source");
            }
            anchor = table.key();
        }
        if (!onSource && join.when != null) {
            checkChain(where + ": when(" + join.when.column.name() + ", ...)", join, join.when.column, joins);
        }
    }

    /**
     * {@code MQ1911} unless {@code column}, an anchor or the {@code when} column of {@code join}, reads no entity join,
     * or one bound before {@code join} in {@code joins} that is itself column-anchored and, under an {@code INNER}
     * {@code join}, {@code INNER} too (R-SRC-25, D-215). Joins are created in registration order, so the earlier
     * join's table precedes this join's ON; a source-anchored join is created with its source, and no chain starts
     * there; an {@code INNER} join on a {@code LEFT} one would drop the outer row the {@code LEFT} join keeps.
     */
    private static void checkChain(String where, EntityJoin<?, ?> join, ColumnField<?, ?, ?> column,
            List<? extends EntityJoin<?, ?>> joins) {
        TableField<?, ?> read = column.table();
        while (read != null && !read.isJoinedEntity()) {
            read = read.parent();
        }
        if (read == null) {
            return;
        }
        String reads = where + ": reads the entity join '" + read.key().alias() + "'";
        int at = joins.indexOf(join);
        for (int i = 0; i < joins.size(); i++) {
            EntityJoin<?, ?> earlier = joins.get(i);
            if (!earlier.root.key().equals(read.key())) {
                continue;
            }
            if (i >= at) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, reads + (i == at ? ", its own root"
                        : ", bound after it; bind '" + earlier.name + "' first, as entity joins are created in "
                        + "registration order"));
            }
            if (earlier.anchoredOnSource()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, reads + ", which is anchored on a joined "
                        + "source; only a join anchored on the root's columns may anchor another");
            }
            if (join.kind == JoinKind.INNER && earlier.kind == JoinKind.LEFT) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, reads + ", which is LEFT: this INNER join "
                        + "would drop every row '" + earlier.name + "' misses; declare '" + join.name + "' LEFT, or '"
                        + earlier.name + "' INNER");
            }
            return;
        }
        throw new ModelQueryDefinitionException(MqCode.MQ1911, reads + ", which this query does not bind");
    }

    /**
     * {@code MQ1911} unless {@code column} reads the root or a to-one path from it, or, when {@code chains}, an entity
     * join or a to-one path from it (D-215; {@link #checkChain} checks which join when the query is built): not an
     * outer lift, a source, nor a treated node (R-COL-35). A to-many segment and an id-form read (R-COL-29) need the
     * metamodel, and fail at first resolution (R-SRC-25).
     */
    private static void checkColumnAnchor(String where, ColumnField<?, ?, ?> column, boolean chains) {
        if (column.isLifted()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": an Outer lift reads another statement");
        }
        for (TableField<?, ?> node = column.table(); node != null; node = node.parent()) {
            if (node.isJoinedEntity() && !chains) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": reads the entity join "
                        + node.describe() + "; a source-anchored join's term reads the root or a to-one path from "
                        + "it, not another join");
            }
            if (node.isSource()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": reads the source "
                        + node.describe() + "; an anchor reads the root or a to-one path from it");
            }
            if (node.treatedType() != null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": reads the treated "
                        + node.describe() + ", which reads NULL on other subtypes (R-COL-35)");
            }
        }
    }

    /**
     * The static term {@code column = value} of a join, ANDed into its ON and bound per statement (R-SRC-25).
     *
     * @param <M> the outer model
     */
    @Incubating
    public static final class When<M> {

        private final ColumnField<M, ?, ?> column;
        private final Object value;

        private When(ColumnField<M, ?, ?> column, Object value) {
            this.column = column;
            this.value = value;
        }

        /** The column of the root, or of a to-one path from it, the term compares (R-SRC-25). */
        @Incubating
        public ColumnField<M, ?, ?> column() {
            return column;
        }

        /** The constant the column equals, bound as a statement parameter (R-SRC-25). */
        @Incubating
        public Object value() {
            return value;
        }
    }

    /**
     * An id attribute path paired with one joined-source or root column (R-SRC-23, R-SRC-25).
     *
     * @param <M> the outer model
     */
    @Incubating
    public static final class On<M> {

        private final ColumnField<M, ?, ?> anchor;
        private final String idAttribute;

        private On(ColumnField<M, ?, ?> anchor, String idAttribute) {
            this.anchor = Objects.requireNonNull(anchor, "anchor");
            this.idAttribute = Objects.requireNonNull(idAttribute, "idAttribute");
        }

        /** The column of the anchor source, or of the root or a to-one path from it (R-SRC-23, R-SRC-25). */
        @Incubating
        public ColumnField<M, ?, ?> anchor() {
            return anchor;
        }

        /** The id attribute path of the entity, dotted for an embedded id (R-SRC-23). */
        @Incubating
        public String idAttribute() {
            return idAttribute;
        }
    }
}
