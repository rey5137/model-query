package com.rey.modelquery.core;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
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

    private ColumnField(Class<M> model, TableField<?, T> table, String attribute, Class<C> type) {
        this.model = model;
        this.table = table;
        this.attribute = attribute;
        this.type = type;
    }

    /**
     * A column of {@code model} reading {@code attribute} on {@code table} as {@code type}. A primitive {@code type} is
     * stored as its wrapper, so {@link #type()} can always cast a value read from a row.
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
     * Resolves the column's path, joining its table through {@code ctx}. Throws {@code MQ1001} when the entity
     * attribute's type does not match {@link #type()} (INV-3).
     */
    public Path<C> path(JoinContext ctx) {
        From<?, T> from = table.resolve(Objects.requireNonNull(ctx, "ctx"));
        Path<C> path = from.get(attribute);
        Class<?> actual = path.getJavaType();
        if (actual == null || boxed(actual) != type) {
            throw new ModelQueryDefinitionException(MqCode.MQ1001, String.format(
                    "%s.%s: declared %s, entity attribute %s.%s is %s",
                    model.getSimpleName(), attribute, type.getSimpleName(), from.getJavaType().getSimpleName(),
                    attribute, actual == null ? "of unknown type" : actual.getSimpleName()));
        }
        return path;
    }

    /** The same attribute and type as a column of {@code model} on {@code table}, for re-rooting under a join. */
    public <M2> ColumnField<M2, T, C> withTable(Class<M2> model, TableField<?, T> table) {
        return of(model, table, attribute, type);
    }

    @Override
    public Class<C> type() {
        return type;
    }

    /** The entity attribute the column reads. */
    @Override
    public String name() {
        return attribute;
    }

    /** Returns {@link #path(JoinContext)}, with the same type check. */
    @Override
    public Expression<C> expression(JoinContext ctx) {
        return path(ctx);
    }

    TableField<?, T> table() {
        return table;
    }

    private static Class<?> boxed(Class<?> type) {
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
        return Objects.hash(model, table.key(), attribute, type);
    }

    @Override
    public String toString() {
        return model.getSimpleName() + "." + attribute;
    }
}
