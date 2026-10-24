package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
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
 * <p>A column with no converter, or one with an {@link OrderedColumnConverter}, is an {@link OrderedColumnField},
 * the only kind {@link Agg#min}, {@link Agg#max} and {@link Agg#countDistinct} take (D-93). Every other converter
 * gives a plain {@code ColumnField}. The hierarchy is closed.
 *
 * @param <M> the model the column belongs to
 * @param <T> the entity type of the table the column sits on
 * @param <C> the column's Java type
 * @implSpec R-COL-07, R-COL-08, R-COL-14, D-55
 */
@Incubating
public sealed class ColumnField<M, T, C> implements ScalarField<M, C> permits OrderedColumnField {

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
    /**
     * The outer column an {@link Outer} lift reads, or {@code null} for a column of the query's own vocabulary. Part
     * of {@link #equals}: a lift never equals the plain column it reads, so a correlation that records one and a
     * matcher that names the other do not match (R-INS-08).
     */
    private final ColumnField<?, ?, ?> liftedFrom;
    /** Cached: a row looks every column up by it, once per row (R-COL-10). */
    private final int hash;

    ColumnField(Class<M> model, TableField<?, T> table, String attribute, Class<C> type,
            Class<?> attributeType, ColumnConverter<C, Object> converter, String property) {
        this(model, table, attribute, type, attributeType, converter, property, null);
    }

    private ColumnField(Class<M> model, TableField<?, T> table, String attribute, Class<C> type,
            Class<?> attributeType, ColumnConverter<C, Object> converter, String property,
            ColumnField<?, ?, ?> liftedFrom) {
        this.model = model;
        this.table = table;
        this.attribute = attribute;
        this.type = type;
        this.attributeType = attributeType;
        this.converter = converter;
        this.property = property;
        this.liftedFrom = liftedFrom;
        this.hash = Objects.hash(model, table.key(), attribute, type, converterClass(), liftedFrom);
    }

    /**
     * A column of the inner vocabulary {@code S} that reads {@code outer}'s entity attribute from the enclosing query:
     * what {@link Outer#column(ColumnField)} returns. It keeps {@code outer}'s model, table, attribute and converter,
     * but is not {@link #equals} {@code outer}: two lifts are equal only when the outer columns they read are equal.
     * Its {@link #path} resolves through the correlation's outer context (R-FLT-17, R-INS-08).
     */
    @SuppressWarnings("unchecked")
    static <S, T, C> ColumnField<S, T, C> lifted(ColumnField<?, T, C> outer) {
        return new ColumnField<>((Class<S>) outer.model, outer.table, outer.attribute, outer.type,
                outer.attributeType, outer.converter, outer.property, outer);
    }

    /** The outer column this lift reads, or empty for a column of the query's own vocabulary. */
    Optional<ColumnField<?, ?, ?>> liftedFrom() {
        return Optional.ofNullable(liftedFrom);
    }

    /** Whether this column is an {@link Outer} lift. */
    boolean isLifted() {
        return liftedFrom != null;
    }

    /**
     * A column of {@code model} reading {@code attribute} on {@code table} as {@code type}. A primitive {@code type} is
     * stored as its wrapper, so {@link #type()} can always cast a value read from a row. {@code attribute} may be a
     * dotted path through embedded values ({@code "address.city"}); an association on the way needs its own
     * {@link TableField} (R-COL-08).
     */
    @SuppressWarnings("unchecked")
    public static <M, T, C> OrderedColumnField<M, T, C> of(
            Class<M> model, TableField<?, T> table, String attribute, Class<C> type) {
        requireNotSourceRoot(table);
        // Sound: int.class is a Class<Integer>, so its wrapper is still a Class<C>.
        Class<C> boxed = (Class<C>) boxed(Objects.requireNonNull(type, "type"));
        return new OrderedColumnField<>(
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
     * a converted column ({@code MQ1408}), except {@code min}, {@code max} and {@code countDistinct}, which take only
     * an {@link OrderedColumnField}: a column whose converter is an {@link OrderedColumnConverter} is one, built by
     * the overload below or, given such a converter through a wider static type, by this one (R-AGG-04, D-93).
     */
    public static <M, T, C, F> ColumnField<M, T, C> of(
            Class<M> model, TableField<?, T> table, String attribute, Class<C> type, Class<F> attributeType,
            ColumnConverter<C, F> converter) {
        requireNotSourceRoot(table);
        return create(model, table, attribute, type, attributeType, converter);
    }

    /**
     * A converted column like {@link #of(Class, TableField, String, Class, Class, ColumnConverter)} whose converter
     * keeps order both ways, so it is an {@link OrderedColumnField}: {@code min}, {@code max} and
     * {@code countDistinct} take it (D-84, D-93).
     */
    public static <M, T, C, F> OrderedColumnField<M, T, C> of(
            Class<M> model, TableField<?, T> table, String attribute, Class<C> type, Class<F> attributeType,
            OrderedColumnConverter<C, F> converter) {
        requireNotSourceRoot(table);
        return (OrderedColumnField<M, T, C>) create(model, table, attribute, type, attributeType, converter);
    }

    /**
     * A column of the outer model {@code model} reading {@code field} of the source rooted at {@code root}: what a
     * {@code @QueryModel} rooted at a {@code @SourceModel} generates (R-SRC-10, R-GEN-37). It reads the CTE column the
     * source aliases {@code field} by — {@code field}'s property, which the source's QModel names — and keeps
     * {@code field}'s type and converter, so the outer column narrows and converts as the source's does.
     *
     * <p>The result is the column {@code field} is, so {@code over(TopCustomer.class, ROOT, QOrderTotals.ID)} is an
     * {@link OrderedColumnField} and composes with {@link #named} the way the source's own constant would.
     *
     * @throws ModelQueryDefinitionException {@code MQ1905} when {@code root} is not a source root, or {@code field}
     *     has no property
     */
    @SuppressWarnings("unchecked")
    public static <M, S, C> OrderedColumnField<M, S, C> over(
            Class<M> model, TableField<S, S> root, OrderedColumnField<S, ?, C> field) {
        Objects.requireNonNull(field, "field");
        requireSourceRoot(root);
        return (OrderedColumnField<M, S, C>) overColumn(model, root, field);
    }

    /**
     * A converted column of the outer model {@code model} over a source, as
     * {@link #over(Class, TableField, OrderedColumnField)}: the source column's converter is copied, so the outer
     * column is an {@link OrderedColumnField} exactly when the source's is (D-84, D-93).
     *
     * @throws ModelQueryDefinitionException {@code MQ1905} when {@code root} is not a source root, or {@code field}
     *     has no property
     */
    public static <M, S, C> ColumnField<M, S, C> over(Class<M> model, TableField<S, S> root,
            ColumnField<S, ?, C> field) {
        Objects.requireNonNull(field, "field");
        requireSourceRoot(root);
        return overColumn(model, root, field);
    }

    /**
     * A column of the outer model {@code model} over a source field that is no {@link ColumnField}: a computed
     * expression or an aggregate, which has no converter and orders as the database value does. A converted column
     * goes through {@link #over(Class, TableField, ColumnField)}, which keeps its converter.
     *
     * @throws ModelQueryDefinitionException {@code MQ1905} when {@code root} is not a source root, {@code field} has
     *     no property, or {@code field} is a converted column whose converter is not ordered
     */
    @SuppressWarnings("unchecked")
    public static <M, S, C> OrderedColumnField<M, S, C> over(Class<M> model, TableField<S, S> root,
            SelectField<S, C> field) {
        Objects.requireNonNull(field, "field");
        if (field instanceof ColumnField<?, ?, ?> converted) {
            requireSourceRoot(root);
            String alias = aliasOf(converted, root);
            // A converted column keeps its converter, which the ordered result needs ordered (D-93).
            if (converted.converter != null && !(converted.converter instanceof OrderedColumnConverter)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1905, "over(...) takes " + root.describe() + "."
                        + alias + ", whose converter " + converted.converterClass().getSimpleName()
                        + " does not order, so the outer column is not an OrderedColumnField; use the over(...) "
                        + "that takes the column itself");
            }
            return (OrderedColumnField<M, S, C>) overColumn(model, root, (ColumnField<S, ?, C>) converted);
        }
        requireSourceRoot(root);
        return new OrderedColumnField<>(Objects.requireNonNull(model, "model"), root, aliasOf(field, root),
                field.type(), field.type(), null, null);
    }

    /** The outer column of {@code field}, on an already-checked source root. */
    private static <M, S, C> ColumnField<M, S, C> overColumn(
            Class<M> model, TableField<S, S> root, ColumnField<S, ?, C> field) {
        return copy(Objects.requireNonNull(model, "model"), root, aliasOf(field, root), field.type,
                field.attributeType, field.converter, null);
    }

    /**
     * The CTE column {@code field} reads, which the source aliases it by: its property as the source's QModel sets it
     * with {@code named} (R-SRC-10). A window field is read as an ordinary column, by its name (R-GEN-38).
     */
    private static String aliasOf(SelectField<?, ?> field, TableField<?, ?> root) {
        return SourceQuery.columnName(SourceQuery.propertyOf(field).orElseThrow(() -> new ModelQueryDefinitionException(
                MqCode.MQ1905, "over(...) takes a generated constant of a source model; " + field.name() + " on "
                        + root.describe() + " has no property to alias the CTE column by")));
    }

    /** {@code MQ1905} for a hand-written column over a source root, which no metamodel checks (R-SRC-10). */
    private static void requireNotSourceRoot(TableField<?, ?> table) {
        Objects.requireNonNull(table, "table");
        if (table.isSource()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1905, "ColumnField.of(...) takes a table of an entity "
                    + "root; " + table.describe() + " is a source root, whose columns an outer model reads with "
                    + "ColumnField.over(...)");
        }
    }

    /** {@code MQ1905} when {@code root} is not the source root the outer column reads. */
    private static void requireSourceRoot(TableField<?, ?> root) {
        Objects.requireNonNull(root, "root");
        if (!root.isSource()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1905, "over(...) takes a TableField.source(...); "
                    + root.describe() + " is an entity root, whose columns come from ColumnField.of(...)");
        }
    }

    /** An {@link OrderedColumnField} exactly when {@code converter} is an {@link OrderedColumnConverter}. */
    @SuppressWarnings("unchecked")
    private static <M, T, C, F> ColumnField<M, T, C> create(
            Class<M> model, TableField<?, T> table, String attribute, Class<C> type, Class<F> attributeType,
            ColumnConverter<C, F> converter) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(attribute, "attribute");
        Class<C> boxedType = (Class<C>) boxed(Objects.requireNonNull(type, "type"));
        Class<?> boxedAttribute = boxed(Objects.requireNonNull(attributeType, "attributeType"));
        // Sound: the converter is only given values checked to be of attributeType.
        ColumnConverter<C, Object> erased = (ColumnConverter<C, Object>) Objects.requireNonNull(converter, "converter");
        return copy(model, table, attribute, boxedType, boxedAttribute, erased, null);
    }

    /** A column of the subtype its {@code converter} calls for: ordered with none or an ordered one (D-93). */
    private static <M, T, C> ColumnField<M, T, C> copy(Class<M> model, TableField<?, T> table, String attribute,
            Class<C> type, Class<?> attributeType, ColumnConverter<C, Object> converter, String property) {
        return converter == null || converter instanceof OrderedColumnConverter
                ? new OrderedColumnField<>(model, table, attribute, type, attributeType, converter, property)
                : new ColumnField<>(model, table, attribute, type, attributeType, converter, property);
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
        if (liftedFrom != null) {
            return ctx.liftedColumn(this);
        }
        From<?, T> from = table.resolve(Objects.requireNonNull(ctx, "ctx"), this);
        if (table.isSource()) {
            // The CTE column is aliased by name and holds the source column's own type, checked by the processor
            // against the source model (R-SRC-10): a provider reports a tuple element's Java type, which is not the
            // mapped attribute's, so INV-3's runtime check has nothing to compare here.
            return cteColumn(from);
        }
        Path<C> path;
        if (attribute.indexOf('.') >= 0) {
            path = embeddedPath(from);
        } else {
            try {
                path = attributePath(from, ctx);
            } catch (IllegalArgumentException e) {
                throw new ModelQueryDefinitionException(MqCode.MQ1002, String.format(
                        "%s.%s: entity %s has no attribute '%s'",
                        model.getSimpleName(), attribute, from.getJavaType().getSimpleName(), attribute), e);
            }
        }
        Class<?> actual = path.getJavaType();
        if (actual == null || !reads(attributeType, actual)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1001, String.format(
                    "%s.%s: declared %s, entity attribute %s.%s is %s",
                    model.getSimpleName(), attribute,
                    converter == null ? name(type, actual)
                            : "attribute type " + name(attributeType, actual) + " for "
                                    + converter.getClass().getSimpleName(),
                    from.getJavaType().getSimpleName(),
                    attribute, (actual == null ? "of unknown type" : name(actual, attributeType))
                            + idNamed(from, ctx)));
        }
        return path;
    }

    /** The CTE element {@code from} aliases {@code attribute} by, which a source's outer column reads (R-SRC-10). */
    @SuppressWarnings("unchecked")
    private Path<C> cteColumn(From<?, T> from) {
        try {
            return (Path<C>) from.get(attribute);
        } catch (IllegalArgumentException e) {
            throw new ModelQueryDefinitionException(MqCode.MQ1002, model.getSimpleName() + "." + attribute
                    + ": the source " + table.describe() + " has no field '" + attribute + "'", e);
        }
    }

    /**
     * The path of an undotted attribute. A to-one is read through its {@code LEFT} join in a read (D-124), and by its
     * target's id when this column's attribute type is that id's (R-COL-29, D-136): through the join in a read, so a
     * {@code NULL} foreign key reads back {@code null}, and as the foreign-key column in a write, which takes no join.
     */
    @SuppressWarnings("unchecked")
    private Path<C> attributePath(From<?, T> from, JoinContext ctx) {
        SingularAttribute<?, ?> toOne = toOne(from, ctx);
        if (toOne == null) {
            return from.get(attribute);
        }
        SingularAttribute<?, ?> id = idOf(toOne);
        if (id != null && ctx.readsToOneLeft()) {
            ctx.checkJoinFilterId(this, new JoinKey(table.key(), attribute, jakarta.persistence.criteria.JoinType.LEFT,
                    ""));
        }
        Path<?> association = ctx.readsToOneLeft() ? leftJoined(ctx) : from.get(attribute);
        if (id == null) {
            return (Path<C>) association; // unchecked until the MQ1001 check in path(JoinContext) compares the type
        }
        if (!ctx.readsToOneLeft()) {
            ctx.readByForeignKey(this, from.getJavaType()); // the executor asks the provider for its join (MQ1004)
        }
        return (Path<C>) association.get(id.getName());
    }

    /**
     * The attribute when it is a singular association of {@code from}'s type, whose path would join it inner. A
     * treated join's attribute names its declared target, which lacks the subtype's own attributes: one of those is
     * read as {@code from.get(...)} resolves it on the subtype. A subtype's to-one there is {@code MQ1001}, whatever
     * the column's type: its path would join it {@code INNER} and drop the rows with a {@code NULL} foreign key, where
     * a read joins a to-one {@code LEFT} (D-124); join it with a {@code TableField} (R-COL-34).
     */
    private SingularAttribute<?, ?> toOne(From<?, T> from, JoinContext ctx) {
        ManagedType<?> owner = managedType(from);
        Attribute<?, ?> found;
        try {
            found = owner == null ? null : owner.getAttribute(attribute);
        } catch (IllegalArgumentException e) {
            if (owner.getJavaType() == from.getJavaType()) {
                throw e;
            }
            if (subtypeAttribute(from, ctx) instanceof SingularAttribute<?, ?> own && own.isAssociation()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1001, String.format(
                        "%s.%s: %s.%s is a to-one of the subtype a treated join reads, which its path would join "
                                + "INNER; join it with a TableField (R-COL-34, D-124)", model.getSimpleName(),
                        attribute, from.getJavaType().getSimpleName(), attribute));
            }
            return null;
        }
        return found instanceof SingularAttribute<?, ?> singular && singular.isAssociation() ? singular : null;
    }

    /** {@code attribute} on the subtype a treated join reads, or {@code null} when the subtype has none. */
    private Attribute<?, ?> subtypeAttribute(From<?, T> from, JoinContext ctx) {
        try {
            return ctx.cb().createQuery().from(from.getJavaType()).getModel().getAttribute(attribute);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The id this column reads {@code association} by: its target's single {@code BASIC} id when this column's
     * attribute type, its own or its converter's, is that id's type (R-COL-29); else {@code null}.
     */
    SingularAttribute<?, ?> idOf(Attribute<?, ?> association) {
        SingularAttribute<?, ?> id = association instanceof SingularAttribute<?, ?> toOne && toOne.isAssociation()
                ? basicId(toOne.getType()) : null;
        return id != null && reads(attributeType, id.getJavaType()) ? id : null;
    }

    /** The single id of {@code target}, an entity, when it is one attribute of persistence type {@code BASIC}. */
    static SingularAttribute<?, ?> basicId(Type<?> target) {
        return target instanceof IdentifiableType<?> entity && entity.hasSingleIdAttribute()
                && entity.getIdType() != null
                && entity.getIdType().getPersistenceType() == Type.PersistenceType.BASIC
                ? entity.getId(entity.getIdType().getJavaType()) : null;
    }

    /** {@code MQ1001}'s mention of the id a to-one column could also read by, or {@code ""} (R-COL-29). */
    private String idNamed(From<?, T> from, JoinContext ctx) {
        SingularAttribute<?, ?> toOne = attribute.indexOf('.') < 0 ? toOne(from, ctx) : null;
        SingularAttribute<?, ?> id = toOne == null ? null : basicId(toOne.getType());
        return id == null ? "" : ", or its id " + id.getJavaType().getSimpleName();
    }

    /**
     * The {@code LEFT} join of the to-one association that a read selects, filters and orders by (D-124). A
     * {@code @Join} of the same association shares it; the context keys it, so every clause gets the same {@code From}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Path<C> leftJoined(JoinContext ctx) {
        return (Path<C>) ((TableField) TableField.join((TableField) table, attribute, JoinType.LEFT))
                .resolve(ctx, this);
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
        if (from.getModel() instanceof ManagedType<?> managed) {
            return managed;
        }
        if (from instanceof Join<?, ?> join) {
            Attribute<?, ?> joined = join.getAttribute();
            Type<?> target = joined instanceof PluralAttribute<?, ?, ?> plural
                    ? plural.getElementType()
                    : ((SingularAttribute<?, ?>) joined).getType();
            return target instanceof ManagedType<?> managed ? managed : null;
        }
        return null;
    }

    /**
     * Whether this column's attribute is an embeddable value, which row-value {@code IN} cannot portably compare
     * (R-FLT-16, INV-6): the check {@code in} and {@code notIn} over a sub-select run at first resolution.
     */
    boolean embeddableValued(JoinContext ctx) {
        if (liftedFrom != null || table.isSource()) {
            return false; // a lift's outer column is checked on the outer side; a CTE column is a scalar element
        }
        From<?, T> from = table.resolve(Objects.requireNonNull(ctx, "ctx"), this);
        if (attribute.indexOf('.') >= 0) {
            return false; // a dotted path ends at a basic leaf, never an embeddable
        }
        ManagedType<?> owner = managedType(from);
        if (owner == null) {
            return false;
        }
        Attribute<?, ?> found;
        try {
            found = owner.getAttribute(attribute);
        } catch (IllegalArgumentException e) {
            return false; // path(...) reports the missing attribute as MQ1002
        }
        return found instanceof SingularAttribute<?, ?> singular
                && singular.getType() instanceof jakarta.persistence.metamodel.EmbeddableType<?>;
    }

    /**
     * Whether this column reads a whole-entity to-one, the D-124 form, rather than its target's id: such a column has
     * no portable group key (R-AGG-05, D-137).
     */
    boolean wholeEntityToOne(JoinContext ctx) {
        if (liftedFrom != null || table.isSource() || attribute.indexOf('.') >= 0) {
            return false;
        }
        From<?, T> from = table.resolve(Objects.requireNonNull(ctx, "ctx"), this);
        ManagedType<?> owner = managedType(from);
        if (owner == null) {
            return false;
        }
        Attribute<?, ?> found;
        try {
            found = owner.getAttribute(attribute);
        } catch (IllegalArgumentException e) {
            return false; // path(...) reports the missing attribute as MQ1002
        }
        return found instanceof SingularAttribute<?, ?> singular && singular.isAssociation()
                && idOf(singular) == null;
    }

    /**
     * This column with {@code property} as the name of the model field it fills, which a sort property is matched
     * against (R-QRY-14). The column is otherwise the same, and equal to this one: the property is not part of
     * {@link #equals}.
     */
    public ColumnField<M, T, C> named(String property) {
        return copy(model, table, attribute, type, attributeType, converter,
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
        return copy(Objects.requireNonNull(model, "model"), Objects.requireNonNull(table, "table"),
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
    @EngineFacing
    public String path() {
        String above = table.path();
        return above.isEmpty() ? attribute : above + "." + attribute;
    }

    /**
     * The attribute path qualified by the entity-join name and each treated subtype name, for indexes and cursor
     * fingerprints (R-SRC-23, R-PAG-19).
     */
    @EngineFacing
    public String identityPath() {
        String above = identityPath(table.key());
        return above.isEmpty() ? attribute : above + "." + attribute;
    }

    private static String identityPath(JoinKey key) {
        if (key.parent() == null) {
            return key.entity() ? key.alias() : "";
        }
        String above = identityPath(key.parent());
        String segment = key.type() == null ? "treat(" + key.attribute() + ")" : key.attribute();
        return above.isEmpty() ? segment : above + "." + segment;
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
    @EngineFacing
    public Class<?> attributeType() {
        return attributeType;
    }

    /** Whether the column has a {@link ColumnConverter}. */
    boolean isConverted() {
        return converter != null;
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
        if (converter == null) {
            return value;
        }
        Object attributeValue;
        try {
            attributeValue = converter.toAttribute(value);
        } catch (IllegalArgumentException e) {
            throw new ModelQueryDefinitionException(MqCode.MQ1308, this + ": " + converter.getClass().getSimpleName()
                    + ".toAttribute cannot convert " + value + ": " + e.getMessage(), e);
        }
        return Objects.requireNonNull(attributeValue,
                () -> this + ": " + converter.getClass().getSimpleName() + ".toAttribute returned null");
    }

    static Class<?> boxed(Class<?> type) {
        return WRAPPERS.getOrDefault(type, type);
    }

    /**
     * Whether a value the provider reports as {@code actual}, boxed, is of the {@code declared} type. A
     * {@code java.util.Date} attribute is temporal in JPA, stored as a {@code DATE}, {@code TIME} or {@code TIMESTAMP},
     * and a provider may report the {@code java.sql} type of its storage (Hibernate does, {@code @Temporal} or not): a
     * declared {@code Date} reads all three, since each is a {@code Date} (R-COL-08, D-122).
     */
    static boolean reads(Class<?> declared, Class<?> actual) {
        Class<?> boxed = boxed(actual);
        return boxed == declared
                || declared == Date.class
                        && (boxed == java.sql.Date.class || boxed == Time.class || boxed == Timestamp.class);
    }

    /**
     * {@code type}'s simple name, or its qualified one when it agrees with {@code other}'s, as {@code java.sql.Date}'s
     * and {@code java.util.Date}'s do; {@code other} may be {@code null}.
     */
    static String name(Class<?> type, Class<?> other) {
        return other != null && type != other && type.getSimpleName().equals(other.getSimpleName()) ? type.getName()
                : type.getSimpleName();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ColumnField<?, ?, ?> other
                && model.equals(other.model)
                && table.key().equals(other.table.key())
                && attribute.equals(other.attribute)
                && type.equals(other.type)
                && Objects.equals(converterClass(), other.converterClass())
                && Objects.equals(liftedFrom, other.liftedFrom);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return (liftedFrom != null ? "outer." : "") + model.getSimpleName() + "." + attribute;
    }
}
