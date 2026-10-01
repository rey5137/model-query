package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One column of a model: an entity attribute on a {@link TableField}, with the Java type it is read as. Immutable, so a
 * constant serves any number of concurrent queries (INV-9). A column need not map to a model field (R-COL-07).
 *
 * <p>A column built with a {@link ColumnConverter} has the model's type as its {@link #type()} and reads an attribute
 * of another type: a {@link Row} converts what it read, and a value filter binds the converted value (R-COL-14).
 *
 * <p>A column may carry a property: the name of the model field it fills, which a sort property is matched against
 * (R-QRY-14). A generated column has one; a hand-written column has none unless given one by {@link #named}.
 *
 * <p>Two columns are equal when they have the same model, table join key, attribute and type, and converters of the
 * same class or none (CC-IMM-04); the property is not part of it.
 *
 * @param <M> the model the column belongs to
 * @param <T> the entity type of the table the column sits on
 * @param <C> the column's Java type
 * @implSpec R-COL-07, R-COL-08, R-COL-14, D-55
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
    /** The entity attribute's type: {@link #type} itself unless the column has a converter. */
    private final Class<?> attributeType;
    /** Converts between {@link #type} and {@link #attributeType}, or {@code null}. */
    private final ColumnConverter<C, Object> converter;
    /** The model field the column fills, or {@code null}; not part of {@link #equals}. */
    private final String property;
    /** Cached: a row looks every column up by it, once per row (R-COL-10). */
    private final int hash;

    private ColumnField(Class<M> model, TableField<?, T> table, String attribute, Class<C> type,
            Class<?> attributeType, ColumnConverter<C, Object> converter, String property) {
        this.model = model;
        this.table = table;
        this.attribute = attribute;
        this.type = type;
        this.attributeType = attributeType;
        this.converter = converter;
        this.property = property;
        this.hash = Objects.hash(model, table.key(), attribute, type, converterClass());
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
        // Sound: int.class is a Class<Integer>, so its wrapper is still a Class<C>.
        Class<C> boxed = (Class<C>) boxed(Objects.requireNonNull(type, "type"));
        return new ColumnField<>(
                Objects.requireNonNull(model, "model"),
                Objects.requireNonNull(table, "table"),
                Objects.requireNonNull(attribute, "attribute"),
                boxed, boxed, null, null);
    }

    /**
     * A converted column of {@code model}: it reads {@code attribute}, of type {@code attributeType}, on
     * {@code table}, and its {@link #type()} is the model's {@code type}. A {@link Row} returns
     * {@code converter.toModel} of a non-null value read, a value filter binds {@code converter.toAttribute} of its
     * value, and {@link Row#raw} returns the attribute value as read (R-COL-14). An aggregate function does not take
     * a converted column ({@code MQ1408}), except {@code min}, {@code max} and {@code countDistinct} over one whose
     * converter is an {@link OrderedColumnConverter} (R-AGG-04).
     */
    @SuppressWarnings("unchecked")
    public static <M, T, C, F> ColumnField<M, T, C> of(
            Class<M> model, TableField<?, T> table, String attribute, Class<C> type, Class<F> attributeType,
            ColumnConverter<C, F> converter) {
        return new ColumnField<>(
                Objects.requireNonNull(model, "model"),
                Objects.requireNonNull(table, "table"),
                Objects.requireNonNull(attribute, "attribute"),
                (Class<C>) boxed(Objects.requireNonNull(type, "type")),
                boxed(Objects.requireNonNull(attributeType, "attributeType")),
                // Sound: the converter is only given values checked to be of attributeType.
                (ColumnConverter<C, Object>) Objects.requireNonNull(converter, "converter"),
                null);
    }

    /**
     * Resolves the column's path, joining its table through {@code ctx}. On a converted column it is the attribute's
     * path, of the attribute's type although typed as {@code Path<C>}: a custom predicate on it compares attribute
     * values (D-37).
     *
     * @throws ModelQueryDefinitionException {@code MQ1001} when the entity attribute's type does not match
     *     {@link #type()}, or the attribute type of a converted column (INV-3), {@code MQ1002} when the entity has
     *     no such attribute or a segment of a dotted attribute crosses an association, {@code MQ1003} when the column
     *     sits on a root the query is not rooted at
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
        if (actual == null || boxed(actual) != attributeType) {
            throw new ModelQueryDefinitionException(MqCode.MQ1001, String.format(
                    "%s.%s: declared %s, entity attribute %s.%s is %s",
                    model.getSimpleName(), attribute,
                    converter == null ? type.getSimpleName()
                            : "attribute type " + attributeType.getSimpleName() + " for "
                                    + converter.getClass().getSimpleName(),
                    from.getJavaType().getSimpleName(),
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
        if (owner == null) {
            // A join over a collection of basic values: its elements have no attribute to walk into.
            throw new ModelQueryDefinitionException(MqCode.MQ1002, String.format(
                    "%s.%s: %s is a basic value, so it has no attribute '%s'",
                    model.getSimpleName(), attribute, from.getJavaType().getSimpleName(), segments[0]));
        }
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

    /**
     * The managed type {@code from} ranges over: the root's entity, or the type a join's attribute leads to;
     * {@code null} for a join over a collection of basic values.
     */
    private static ManagedType<?> managedType(From<?, ?> from) {
        if (from instanceof Join<?, ?> join) {
            Attribute<?, ?> joined = join.getAttribute();
            Type<?> target = joined instanceof PluralAttribute<?, ?, ?> plural
                    ? plural.getElementType()
                    : ((SingularAttribute<?, ?>) joined).getType();
            return target instanceof ManagedType<?> managed ? managed : null;
        }
        return ((Root<?>) from).getModel();
    }

    /**
     * This column with {@code property} as the name of the model field it fills, which a sort property is matched
     * against (R-QRY-14). The column is otherwise the same, and equal to this one: the property is not part of
     * {@link #equals}.
     */
    public ColumnField<M, T, C> named(String property) {
        return new ColumnField<>(model, table, attribute, type, attributeType, converter,
                Objects.requireNonNull(property, "property"));
    }

    /**
     * The name of the model field the column fills, as given to {@link #named}; empty for a column with none. It is
     * the model's own field name, which may differ from {@link #name()}: {@code customerId} for {@code customer},
     * {@code city} for {@code address.city}. A change set's validation looks the field's constraints up by it.
     *
     * @implSpec D-55, R-WRT-21, D-71
     */
    public Optional<String> property() {
        return Optional.ofNullable(property);
    }

    /**
     * The same attribute, type, converter and property as a column of {@code model} on {@code table}, for
     * re-rooting under a join.
     */
    public <M2> ColumnField<M2, T, C> withTable(Class<M2> model, TableField<?, T> table) {
        return new ColumnField<>(Objects.requireNonNull(model, "model"), Objects.requireNonNull(table, "table"),
                attribute, type, attributeType, converter, property);
    }

    /**
     * This column as a column of {@code model} under {@code join}: its table's root is replaced by {@code join}, the
     * way a nested model's column sits in an outer model (D-38).
     */
    @SuppressWarnings("unchecked")
    <M2> ColumnField<M2, ?, ?> under(Class<M2> model, TableField<?, ?> join) {
        // Sound: re-rooting keeps the entity each node reaches.
        return withTable(model, (TableField<?, T>) table.under(join));
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

    /**
     * The attribute path from the query's root: {@link #name()} for a root column, {@code customer.fullName} for a
     * column of a joined table. A sort property is matched against it after the property path (R-QRY-14).
     */
    String path() {
        String above = table.path();
        return above.isEmpty() ? attribute : above + "." + attribute;
    }

    /**
     * The model field names from the query's root: the property for a root column, {@code customer.name} for a
     * column named {@code name} of a join named {@code customer}; {@code null} when the column or a join on the way
     * has no property. A sort property is matched against it first (R-QRY-14, D-55).
     */
    String propertyPath() {
        String above = table.propertyPath();
        if (property == null || above == null) {
            return null;
        }
        return above.isEmpty() ? property : above + "." + property;
    }

    Class<M> model() {
        return model;
    }

    TableField<?, T> table() {
        return table;
    }

    /** The entity attribute's type: {@link #type()} unless the column has a {@link ColumnConverter}. */
    Class<?> attributeType() {
        return attributeType;
    }

    /** Whether the column has a {@link ColumnConverter}. */
    boolean isConverted() {
        return converter != null;
    }

    /** Whether the column's converter is an {@link OrderedColumnConverter}, so it keeps order both ways (D-84). */
    boolean isOrdered() {
        return converter instanceof OrderedColumnConverter;
    }

    /**
     * The column whose {@link #toModel} and {@link #toAttribute} map {@code field}'s values: the column itself, or the
     * converted column a {@code min} or {@code max} aggregates (R-AGG-04); {@code null} for any other selection,
     * whose values are read and bound as they are.
     */
    static <C> ColumnField<?, ?, C> valueColumn(SelectField<?, C> field) {
        if (field instanceof ColumnField<?, ?, C> column) {
            return column;
        }
        return field instanceof AggregateField<?, C> aggregate ? aggregate.converted() : null;
    }

    /** The converter's class, which two equal columns share, or {@code null} without a converter (R-COL-14). */
    Class<?> converterClass() {
        return converter == null ? null : converter.getClass();
    }

    /** The model value of {@code raw}, a value read from this column's path; {@code null} stays {@code null}. */
    C toModel(Object raw) {
        if (raw == null) {
            return null;
        }
        return type.cast(converter == null ? raw : converter.toModel(attributeType.cast(raw)));
    }

    /** The value a filter binds for {@code value}, which is not {@code null}: the attribute value when converted. */
    Object toAttribute(C value) {
        return converter == null ? value : Objects.requireNonNull(converter.toAttribute(value),
                () -> this + ": " + converter.getClass().getSimpleName() + ".toAttribute returned null");
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
                && type.equals(other.type)
                && Objects.equals(converterClass(), other.converterClass());
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
