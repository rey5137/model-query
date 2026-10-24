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
import java.util.Set;
import java.util.TreeMap;

/**
 * An entity joined on its full id to columns of one joined source (R-SRC-23). Immutable, so a constant (INV-9).
 *
 * @param <M> the outer model
 * @param <E> the joined entity
 * @implSpec R-SRC-23, R-SRC-24, D-193
 */
@Incubating
public final class EntityJoin<M, E> {

    private final String name;
    private final Class<E> entity;
    private final JoinKind kind;
    private final List<On<M>> on;
    private final TableField<E, E> root;

    private EntityJoin(String name, Class<E> entity, JoinKind kind, List<On<M>> on) {
        this.name = name;
        this.entity = entity;
        this.kind = kind;
        this.on = on;
        this.root = TableField.joinedEntity(name, entity);
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
                pairs);
    }

    /** Pairs {@code anchor}, a joined source's column, with an entity id attribute path (R-SRC-23). */
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

    /** The checks that need no metamodel, run when the query is built (D-193, Resolution 8). */
    static void check(String model, EntityJoin<?, ?> join, Set<JoinKey> sources) {
        JoinKey anchor = null;
        for (On<?> pair : join.on) {
            TableField<?, ?> table = pair.anchor.table();
            String where = model + ": entity join '" + join.name + "' of " + join.entity.getSimpleName();
            if (pair.anchor.isConverted()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": anchor " + pair.anchor.name()
                        + " is converted; the join compares database values");
            }
            if (!table.isJoinedSource() || !sources.contains(table.key())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where + ": anchor " + pair.anchor.name()
                        + " must read a joined source bound by this query; " + table.describe() + " is not bound");
            }
            if (anchor != null && !anchor.equals(table.key())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1911, where
                        + ": on(...) pairs span two joined sources; all pairs must read one joined source");
            }
            anchor = table.key();
        }
    }

    /**
     * An id attribute path paired with one joined-source column (R-SRC-23).
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

        /** The column of the anchor source (R-SRC-23). */
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
