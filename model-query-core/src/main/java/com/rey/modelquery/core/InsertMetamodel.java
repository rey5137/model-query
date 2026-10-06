package com.rey.modelquery.core;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The checks of an insert definition against the JPA metamodel, which {@code build()} cannot see: an executor runs
 * them on the definition's first execution per factory, before any statement (D-61, D-117).
 */
final class InsertMetamodel {

    private InsertMetamodel() {
    }

    /** The first segment of a dotted attribute name, the name itself when it has none. */
    static String head(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * Whether {@code written}, the attributes an insert writes, hold {@code entity}'s whole id: the {@code @Id}, the
     * {@code @EmbeddedId} or each of its components, or every {@code @IdClass} attribute.
     *
     * @throws ModelQueryDefinitionException {@code MQ1802} when they hold only part of it (R-WRT-26)
     */
    static boolean writesId(EntityType<?> entity, List<String> written, String insert) {
        Set<String> ids = WriteRendering.idNames(entity);
        Set<String> whole = new HashSet<>();
        Set<String> parts = new HashSet<>();
        for (String name : written) {
            int dot = name.indexOf('.');
            String head = head(name);
            if (ids.contains(head)) {
                (dot < 0 ? whole : parts).add(name);
            }
        }
        for (String id : ids) {
            if (!whole.contains(id) && coversComponents(entity, id, parts)) {
                whole.add(id);
            }
        }
        if (whole.isEmpty() && parts.isEmpty()) {
            return false;
        }
        if (!whole.containsAll(ids)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1802, insert + ": writes part of the id " + ids + " of "
                    + entity.getJavaType().getSimpleName() + "; a row carries the whole id, or none when it is "
                    + "generated");
        }
        return true;
    }

    /** Whether {@code parts} names every component of the embeddable id attribute {@code id}. */
    private static boolean coversComponents(EntityType<?> entity, String id, Set<String> parts) {
        Attribute<?, ?> attribute = entity.getAttribute(id);
        if (!(attribute instanceof SingularAttribute<?, ?> singular
                && singular.getType() instanceof EmbeddableType<?> embeddable)) {
            return false;
        }
        return embeddable.getAttributes().stream().allMatch(part -> parts.contains(id + "." + part.getName()));
    }

    /**
     * Checks that every {@code map} of an insert-select reads a column of {@code source}, the entity it reads, and
     * copies it to an attribute of the same type: the database copies attribute values, and the read path would
     * otherwise read another root's column from it. A to-one attribute and its target's id count as one type.
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} otherwise (R-WRT-27)
     */
    static void checkMappings(Metamodel metamodel, EntityType<?> target, Class<?> source,
            List<InsertDraft.Mapped> mappings) {
        EntityType<?> read = metamodel.entity(source);
        for (InsertDraft.Mapped mapping : mappings) {
            requireOnSource(mapping.source(), source, "map(...) copies");
            Attribute<?, ?> written = attribute(target, mapping.target().name());
            Attribute<?, ?> copied = attribute(read, mapping.source().path());
            if (written != null && copied != null && !sameType(written, copied)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, mapping.target() + ": mapped from "
                        + mapping.source() + ", whose attribute is a " + describe(copied) + ", but written to a "
                        + describe(written) + "; the database copies attribute values, so both need one type");
            }
        }
    }

    /**
     * Checks that every column {@code conditions} read starts at {@code source}: the select runs on it. A sub-select's
     * own columns are its query's.
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} otherwise (R-WRT-27)
     */
    static void checkWhere(Class<?> source, List<Condition> conditions) {
        for (Condition condition : conditions) {
            switch (condition.kind()) {
                case CUSTOM, EXISTS_SUBSELECT, NOT_EXISTS_SUBSELECT -> {
                }
                case EXISTS, NOT_EXISTS -> {
                    condition.path().ifPresent(path -> {
                        if (!path.pathRoot().isAssignableFrom(source)) {
                            throw new ModelQueryDefinitionException(MqCode.MQ1801, "where(...) reads "
                                    + path.describe() + ", but the insert-select reads " + source.getSimpleName());
                        }
                    });
                    checkWhere(source, condition.children());
                }
                default -> {
                    condition.column().ifPresent(field -> requireOnSource(field, source, "where(...) reads"));
                    condition.right().ifPresent(field -> requireOnSource(field, source, "where(...) reads"));
                    checkWhere(source, condition.children());
                }
            }
        }
    }

    /**
     * Checks that {@code keyType}, the key type an insert's generated method fixed, is {@code entity}'s boxed id type
     * (D-117). {@code Object}, where the processor saw no id, passes, and so does any type where the metamodel reports
     * no id type, as Hibernate 6 does for an {@code @IdClass}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1807} otherwise (R-WRT-33, R-WRT-39)
     */
    static void checkKeyType(EntityType<?> entity, Class<?> keyType, String insert) {
        Type<?> id = entity.getIdType();
        if (keyType == Object.class || id == null) {
            return;
        }
        Class<?> idType = ColumnField.boxed(id.getJavaType());
        if (!keyType.equals(idType)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1807, insert + ": its keys are typed "
                    + keyType.getSimpleName() + ", but the id of " + entity.getJavaType().getSimpleName() + " is a "
                    + idType.getSimpleName() + " in the persistence unit, which an orm.xml mapping can change unseen "
                    + "by the processor; correct the mapping or the entity, and regenerate");
        }
    }

    /**
     * Checks that every embeddable on the way to {@code path}, an attribute of {@code entity} that {@code persist}
     * sets, can be instantiated with a no-arg constructor, as {@code persist} instantiates it (R-WRT-39).
     *
     * @throws ModelQueryDefinitionException {@code MQ1805} for a record or an embeddable with no no-arg constructor
     */
    static void checkInstantiable(EntityType<?> entity, String path, String persist) {
        ManagedType<?> owner = entity;
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length - 1 && owner != null; i++) {
            Type<?> type = attribute(owner, segments[i]) instanceof SingularAttribute<?, ?> singular
                    ? singular.getType() : null;
            if (type instanceof EmbeddableType<?> embeddable) {
                Class<?> java = embeddable.getJavaType();
                if (java.isRecord() || !hasNoArgConstructor(java)) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1805, persist + ": " + path + " is set on "
                            + java.getSimpleName() + ", " + (java.isRecord() ? "a record" : "an embeddable with no "
                            + "no-arg constructor") + ", which persist cannot instantiate and set; write it with "
                            + "insert, or give the embeddable a no-arg constructor");
                }
            }
            owner = type instanceof ManagedType<?> managed ? managed : null;
        }
    }

    private static boolean hasNoArgConstructor(Class<?> type) {
        try {
            type.getDeclaredConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * {@code name}, an attribute of {@code entity} an insert-select writes, with the target's id attribute appended
     * when it is a to-one: the select copies the id, as {@code checkMappings} lets a to-one take one (R-WRT-27).
     */
    static String toOneIdPath(EntityType<?> entity, String name) {
        if (name.indexOf('.') < 0 && attribute(entity, name) instanceof SingularAttribute<?, ?> singular
                && singular.isAssociation() && singular.getType() instanceof IdentifiableType<?> target
                && target.hasSingleIdAttribute()) {
            return name + "." + singleId(target).getName();
        }
        return name;
    }

    /**
     * {@code entity}'s id as a key of plain columns on {@code root}, ordered by attribute name: the {@code @Id}, each
     * component of an {@code @EmbeddedId} ({@code id.part}), or each {@code @IdClass} attribute (R-WRT-28).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static PrimaryKey<Object, ?> idKey(EntityType<?> entity, TableField<?, ?> root) {
        var columns = new ArrayList<ColumnField<Object, ?, ?>>();
        if (entity.hasSingleIdAttribute()) {
            SingularAttribute<?, ?> id = singleId(entity);
            if (id.getType() instanceof EmbeddableType<?> embeddable) {
                embeddable.getSingularAttributes().stream().sorted(Comparator.comparing(Attribute::getName))
                        .forEach(part -> columns.add(ColumnField.of(Object.class, (TableField) root,
                                id.getName() + "." + part.getName(), part.getJavaType())));
            } else {
                columns.add(ColumnField.of(Object.class, (TableField) root, id.getName(), id.getJavaType()));
            }
        } else {
            entity.getIdClassAttributes().stream().sorted(Comparator.comparing(Attribute::getName))
                    .forEach(part -> columns.add(ColumnField.of(Object.class, (TableField) root, part.getName(),
                            part.getJavaType())));
        }
        if (columns.size() == 1) {
            return PrimaryKey.of((ColumnField) columns.get(0));
        }
        return PrimaryKey.composite(columns.get(0), columns.get(1),
                columns.subList(2, columns.size()).toArray(ColumnField[]::new));
    }

    /** The one {@code @Id} or {@code @EmbeddedId} attribute of {@code type}, found by flag, whatever its type. */
    private static SingularAttribute<?, ?> singleId(IdentifiableType<?> type) {
        return type.getSingularAttributes().stream().filter(SingularAttribute::isId).findFirst()
                .orElseThrow(() -> new IllegalStateException(type.getJavaType().getName() + " has no single id"));
    }

    private static void requireOnSource(SelectField<?, ?> field, Class<?> source, String what) {
        if (field instanceof ExpressionField<?, ?> expression) {
            expression.columns().forEach(column -> requireOnSource(column, source, what));
        } else if (field instanceof ColumnField<?, ?, ?> column) {
            Class<?> root = column.table().pathRoot();
            if (!root.isAssignableFrom(source)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, column + ": " + what + " a column of "
                        + root.getSimpleName() + ", but the insert-select reads " + source.getSimpleName());
            }
        }
    }

    /** The attribute {@code path} names from {@code type}, dotted through joins and embeddables, or {@code null}. */
    private static Attribute<?, ?> attribute(ManagedType<?> type, String path) {
        ManagedType<?> owner = type;
        Attribute<?, ?> found = null;
        for (String segment : path.split("\\.")) {
            if (owner == null) {
                return null;
            }
            try {
                found = owner.getAttribute(segment);
            } catch (IllegalArgumentException e) {
                return null; // rendering the column reports MQ1002
            }
            Type<?> next = found instanceof PluralAttribute<?, ?, ?> plural ? plural.getElementType()
                    : ((SingularAttribute<?, ?>) found).getType();
            owner = next instanceof ManagedType<?> managed ? managed : null;
        }
        return found;
    }

    /** Whether the two attributes hold one type, a to-one beside a basic attribute counting as its target's id. */
    private static boolean sameType(Attribute<?, ?> a, Attribute<?, ?> b) {
        Class<?> aId = toOneId(a);
        Class<?> bId = toOneId(b);
        if ((aId == null) == (bId == null)) {
            return ColumnField.boxed(a.getJavaType()).equals(ColumnField.boxed(b.getJavaType()));
        }
        return aId != null ? aId.equals(ColumnField.boxed(b.getJavaType()))
                : bId.equals(ColumnField.boxed(a.getJavaType()));
    }

    /** A to-one attribute's boxed target id type, or {@code null} for any other attribute. */
    private static Class<?> toOneId(Attribute<?, ?> attribute) {
        if (attribute instanceof SingularAttribute<?, ?> singular && singular.isAssociation()
                && singular.getType() instanceof IdentifiableType<?> target && target.getIdType() != null) {
            return ColumnField.boxed(target.getIdType().getJavaType());
        }
        return null;
    }

    private static String describe(Attribute<?, ?> attribute) {
        return attribute.getJavaType().getSimpleName() + " (" + attribute.getDeclaringType().getJavaType()
                .getSimpleName() + "." + attribute.getName() + ")";
    }
}
