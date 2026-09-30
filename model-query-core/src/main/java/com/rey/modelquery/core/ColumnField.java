package com.rey.modelquery.core;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import java.util.Map;
import java.util.Objects;

/**
 * One column of a model: an entity attribute on a {@link TableField}, with the Java type it is read as. Immutable, so a
 * constant serves any number of concurrent queries (INV-9). A column need not map to a model field (R-COL-07).
 *
 * <p>Two columns are equal when they have the same model, table join key, attribute and type (CC-IMM-04).
 *
 * @param <M> the model the column belongs to
 * @param <T> the entity type of the table the column sits on
 * @param <C> the column's Java type
 * @implSpec R-COL-07, R-COL-08
 */
@Incubating
public final class ColumnField<M, T, C> implements SelectField<M, C> {

    // A primitive attribute is read as its wrapper, so the two are the same column type (R-COL-08).
    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            short.class, Short.class,
            char.class, Character.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class);

    private final Class<M> model;
    private final TableField<?, T> table;
    private final String attribute;
    private final Class<C> type;
    /** Cached: a row looks every column up by it, once per row (R-COL-10). */
    private final int hash;

    private ColumnField(Class<M> model, TableField<?, T> table, String attribute, Class<C> type) {
        this.model = model;
        this.table = table;
        this.attribute = attribute;
        this.type = type;
        this.hash = Objects.hash(model, table.key(), attribute, type);
    }

    /**
     * A column of {@code model} reading {@code attribute} on {@code table} as {@code type}. A primitive {@code type} is
     * stored as its wrapper, so {@link #type()} can always cast a value read from a row. {@code attribute} may be a
     * dotted path through embedded values ({@code "address.city"}); an association on the way needs its own
     * {@link TableField} (R-COL-08).
     */
    @SuppressWarnings("unchecked")
    public static <M, T, C> ColumnField<M, T, C> of(
            Class<M> model, TableField<?, T> table, String attribute, Class<C> type) {
        return new ColumnField<>(
                Objects.requireNonNull(model, "model"),
                Objects.requireNonNull(table, "table"),
                Objects.requireNonNull(attribute, "attribute"),
                // Sound: int.class is a Class<Integer>, so its wrapper is still a Class<C>.
                (Class<C>) boxed(Objects.requireNonNull(type, "type")));
    }

    /**
     * Resolves the column's path, joining its table through {@code ctx}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1001} when the entity attribute's type does not match
     *     {@link #type()} (INV-3), {@code MQ1002} when the entity has no such attribute or a segment of a dotted
     *     attribute crosses an association, {@code MQ1003} when the column sits on a root the query is not rooted at
     */
    public Path<C> path(JoinContext ctx) {
        From<?, T> from = table.resolve(Objects.requireNonNull(ctx, "ctx"), this);
        Path<C> path;
        if (attribute.indexOf('.') >= 0) {
            path = embeddedPath(from);
        } else {
            try {
                path = from.get(attribute);
            } catch (IllegalArgumentException e) {
                throw new ModelQueryDefinitionException(MqCode.MQ1002, String.format(
                        "%s.%s: entity %s has no attribute '%s'",
                        model.getSimpleName(), attribute, from.getJavaType().getSimpleName(), attribute), e);
            }
        }
        Class<?> actual = path.getJavaType();
        if (actual == null || boxed(actual) != type) {
            throw new ModelQueryDefinitionException(MqCode.MQ1001, String.format(
                    "%s.%s: declared %s, entity attribute %s.%s is %s",
                    model.getSimpleName(), attribute, type.getSimpleName(), from.getJavaType().getSimpleName(),
                    attribute, actual == null ? "of unknown type" : actual.getSimpleName()));
        }
        return path;
    }

    /**
     * Walks a dotted attribute through embedded values (D-41). Every segment but the last is looked up in the
     * metamodel first: a path must not reach through an association, which the provider would join implicitly and
     * outside the join key.
     */
    @SuppressWarnings("unchecked")
    private Path<C> embeddedPath(From<?, T> from) {
        String[] segments = attribute.split("\\.", -1);
        ManagedType<?> owner = managedType(from);
        Path<?> path = from;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            Attribute<?, ?> found;
            try {
                found = owner.getAttribute(segment);
            } catch (IllegalArgumentException e) {
                throw new ModelQueryDefinitionException(MqCode.MQ1002, String.format(
                        "%s.%s: %s has no attribute '%s'",
                        model.getSimpleName(), attribute, owner.getJavaType().getSimpleName(), segment), e);
            }
            if (i < segments.length - 1) {
                if (found.isAssociation() || found.isCollection()) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1002, String.format(
                            "%s.%s: segment '%s' crosses the association %s.%s; a column's path may only go through "
                                    + "embedded values, so join the association with a TableField",
                            model.getSimpleName(), attribute, segment, owner.getJavaType().getSimpleName(), segment));
                }
                if (!(found instanceof SingularAttribute<?, ?> singular
                        && singular.getType() instanceof ManagedType<?> embeddable)) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1002, String.format(
                            "%s.%s: segment '%s' is not an embedded value, so %s.%s has no attribute '%s'",
                            model.getSimpleName(), attribute, segment, owner.getJavaType().getSimpleName(), segment,
                            segments[i + 1]));
                }
                owner = embeddable;
            }
            path = path.get(segment);
        }
        // Unchecked until the MQ1001 check in path(JoinContext) compares the type.
        return (Path<C>) path;
    }

    /** The managed type {@code from} ranges over: the root's entity, or the type a join's attribute leads to. */
    private static ManagedType<?> managedType(From<?, ?> from) {
        if (from instanceof Join<?, ?> join) {
            Attribute<?, ?> joined = join.getAttribute();
            return (ManagedType<?>) (joined instanceof PluralAttribute<?, ?, ?> plural
                    ? plural.getElementType()
                    : ((SingularAttribute<?, ?>) joined).getType());
        }
        return ((Root<?>) from).getModel();
    }

    /** The same attribute and type as a column of {@code model} on {@code table}, for re-rooting under a join. */
    public <M2> ColumnField<M2, T, C> withTable(Class<M2> model, TableField<?, T> table) {
        return of(model, table, attribute, type);
    }

    @Override
    public Class<C> type() {
        return type;
    }

    /** The entity attribute the column reads, dotted when it is read through embedded values. */
    @Override
    public String name() {
        return attribute;
    }

    /** Returns {@link #path(JoinContext)}, with the same type check. */
    @Override
    public Expression<C> expression(JoinContext ctx) {
        return path(ctx);
    }

    Class<M> model() {
        return model;
    }

    TableField<?, T> table() {
        return table;
    }

    static Class<?> boxed(Class<?> type) {
        return WRAPPERS.getOrDefault(type, type);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ColumnField<?, ?, ?> other
                && model.equals(other.model)
                && table.key().equals(other.table.key())
                && attribute.equals(other.attribute)
                && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return model.getSimpleName() + "." + attribute;
    }
}
