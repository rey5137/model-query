package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * An aggregate selection: a function over a column or a table, or a named custom expression, created by {@link Agg}.
 * It resolves to an {@link Expression} rather than a path, so it can be selected, ordered, read from a {@link Row}
 * and filtered in {@code having}, but never used in {@code where} (R-COL-06). Immutable, so a constant serves any
 * number of concurrent queries (INV-9).
 *
 * <p>Two aggregates are equal when they have the same function, source (the column's join key and attribute, or the
 * table's join key) and alias, never by object identity, and a {@code min} or {@code max} the same converter class or
 * none (R-AGG-01). An {@link Agg#of} aggregate is keyed by its name instead, because a lambda cannot be compared
 * (R-AGG-02).
 *
 * @param <M> the model the selection belongs to
 * @param <C> the aggregate's result type, which is what the database returns (R-AGG-03), or the model type for a
 *     {@code min} or {@code max} over a column with an {@link OrderedColumnConverter} (R-AGG-04)
 * @implSpec R-AGG-01, R-AGG-02, R-AGG-04, R-AGG-17, R-COL-23, D-3, D-84, D-121, D-185
 */
@Incubating
public final class AggregateField<M, C> implements SelectField<M, C> {

    /**
     * The aggregate function; its name is the one used in {@link #name()}. An {@code AggregateRenderer} receives only
     * the functions that take an argument expression, never {@link #OF}, whose expression is the caller's.
     */
    @Incubating
    public enum Kind {
        COUNT("count"),
        COUNT_DISTINCT("countDistinct"),
        SUM("sum"),
        SUM_AS_LONG("sumAsLong"),
        AVG("avg"),
        /** {@code sum(x) / count(x)} typed {@code BigDecimal}, from {@link AggregateRenderer#exactAverage}. */
        AVG_EXACT("avgExact"),
        /** An {@code Agg.round} over another aggregate, which is its {@code inner}. */
        ROUND("round"),
        MIN("min"),
        MAX("max"),
        OF(null);

        private final String function;

        Kind(String function) {
            this.function = function;
        }
    }

    private final Kind kind;
    /** The aggregate a {@link Kind#ROUND} rounds; {@code null} for any other. */
    private final AggregateField<?, ?> inner;
    /** The decimals a {@link Kind#ROUND} keeps; 0 for any other. */
    private final int scale;
    /** The join key of the source column's table, or of the counted table; {@code null} for {@code Agg.of}. */
    private final JoinKey source;
    /** The source column's attribute, the counted table's name, or the {@code Agg.of} name. */
    private final String attribute;
    private final String alias;
    private final Class<C> type;
    /**
     * The converted column a {@code min} or {@code max} aggregates, whose converter maps the result read and a
     * {@code having} value bound; {@code null} when the result is read as the database returns it.
     */
    private final ColumnField<?, ?, C> converted;
    /** The expression this aggregate is over, or {@code null} for a column, a table or an {@code Agg.of} (R-AGG-13). */
    private final ExpressionField<?, ?> sourceExpression;
    /** The model field the aggregate fills, or {@code null}; not part of {@link #equals}, as in an expression. */
    private final String property;
    /** The expression the function is applied to, or {@code null} for an {@code Agg.of}. */
    private final BiFunction<JoinContext, CriteriaBuilder, Expression<?>> argument;
    /** The caller's whole expression of an {@code Agg.of}, or {@code null} for any other aggregate. */
    private final BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression;
    /** The {@link #filter} condition as recorded, which renders it; empty when unfiltered (R-AGG-17). */
    private final List<Filter> filters;
    /** The {@link #filter} condition's structure, which equality compares; empty when unfiltered (R-AGG-17). */
    private final List<Condition> conditions;
    private final int hash;

    AggregateField(Kind kind, JoinKey source, String attribute, String alias, Class<C> type,
            ColumnField<?, ?, C> converted, ExpressionField<?, ?> sourceExpression,
            BiFunction<JoinContext, CriteriaBuilder, Expression<?>> argument) {
        this(kind, source, attribute, alias, type, converted, sourceExpression, null, argument, null, List.of(),
                List.of(), null, 0);
    }

    /** An {@link Agg#of} aggregate, whose expression is the caller's. */
    static <M, C> AggregateField<M, C> custom(String name, Class<C> type,
            BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        return new AggregateField<>(Kind.OF, null, name, "", type, null, null, null, null, expression, List.of(),
                List.of(), null, 0);
    }

    /** An {@link Agg#round} aggregate: {@code inner} rounded to {@code scale} decimals. */
    static <M> AggregateField<M, BigDecimal> rounding(AggregateField<M, BigDecimal> inner, int scale) {
        return new AggregateField<>(Kind.ROUND, null, "", "", BigDecimal.class, null, null, null, null, null,
                List.of(), List.of(), inner, scale);
    }

    private AggregateField(Kind kind, JoinKey source, String attribute, String alias, Class<C> type,
            ColumnField<?, ?, C> converted, ExpressionField<?, ?> sourceExpression, String property,
            BiFunction<JoinContext, CriteriaBuilder, Expression<?>> argument,
            BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression, List<Filter> filters,
            List<Condition> conditions, AggregateField<?, ?> inner, int scale) {
        this.kind = kind;
        this.inner = inner;
        this.scale = scale;
        this.source = source;
        this.attribute = attribute;
        this.alias = alias;
        this.type = type;
        this.converted = converted;
        this.sourceExpression = sourceExpression;
        this.property = property;
        this.argument = argument;
        this.expression = expression;
        this.filters = filters;
        this.conditions = conditions;
        this.hash = Objects.hash(kind, source, attribute, alias, converterClass(), sourceExpression, conditions, inner,
                scale);
    }

    /**
     * The same aggregate under its own key, for when the same function over the same source is needed twice
     * (R-AGG-01). The alias also becomes its {@link #name()}.
     */
    public AggregateField<M, C> as(String alias) {
        return new AggregateField<>(kind, source, attribute, Objects.requireNonNull(alias, "alias"), type, converted,
                sourceExpression, property, argument, expression, filters, conditions, inner, scale);
    }

    /**
     * This aggregate over only the rows {@code condition} matches, as SQL {@code FILTER (WHERE ...)} where the provider
     * renders it and {@code fn(CASE WHEN ... THEN x END)} otherwise (R-AGG-17). A join the condition is the first to
     * need resolves {@code LEFT}, so it drops no row from the statement. Equal to another filtered aggregate when
     * their conditions are equal, and the same name: give two filtered aggregates over one source an
     * {@link #as alias}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1412} for an aggregate that is already filtered or made by
     *     {@link Agg#of}, a condition that leaves no filter, or one that reads a correlated value; at build time also
     *     one that adds a to-many join
     */
    @Incubating
    public AggregateField<M, C> filter(UnaryOperator<Filters<M>> condition) {
        Objects.requireNonNull(condition, "condition");
        if (kind == Kind.OF) {
            throw refused(this + ": Agg.of cannot be filtered, as the engine cannot place a condition inside the "
                    + "expression of a function; put the condition inside it");
        }
        if (kind == Kind.ROUND) {
            throw refused(this + ": a rounded aggregate cannot be filtered; filter the aggregate it rounds, "
                    + "Agg.round(aggregate.filter(...), scale)");
        }
        if (!conditions.isEmpty()) {
            throw refused(this + " is already filtered; combine the conditions in one filter(...)");
        }
        List<Filter> recorded = FilterGroup.collect(condition);
        List<Condition> structure = ConditionGroup.conditions(recorded);
        if (structure.isEmpty()) {
            throw refused(this + ".filter(...): the condition left no filter, so it would match every row; add a "
                    + "filter or drop the call");
        }
        for (Condition each : structure) {
            if (ExpressionField.containsCorrelated(each)) {
                throw refused(this + ".filter(...): the condition reads a correlated value, which an aggregate "
                        + "cannot filter on");
            }
        }
        return new AggregateField<>(kind, source, attribute, alias, type, converted, sourceExpression, property,
                argument, expression, List.copyOf(recorded), List.copyOf(structure), inner, scale);
    }

    private static ModelQueryDefinitionException refused(String message) {
        return new ModelQueryDefinitionException(MqCode.MQ1412, message);
    }

    /**
     * This aggregate under {@code property} as the name a sort or a {@link FieldIndex} knows it by, in place of the
     * canonical {@link #name()}; not part of {@link #equals}, so it stays equal to the same aggregate written without
     * it (R-AGG-01, R-COL-23).
     */
    public AggregateField<M, C> named(String property) {
        return new AggregateField<>(kind, source, attribute, alias, type, converted, sourceExpression,
                Objects.requireNonNull(property, "property"), argument, expression, filters, conditions, inner, scale);
    }

    /** The name given to {@link #named}; empty with none. */
    public Optional<String> property() {
        return Optional.ofNullable(property);
    }

    @Override
    public Class<C> type() {
        return type;
    }

    /** The alias when one was given, else the {@code Agg.of} name, or the function and source: {@code sum(total)}. */
    @Override
    public String name() {
        if (!alias.isEmpty()) {
            return alias;
        }
        if (sourceExpression != null) {
            return kind.function + "(" + sourceExpression.name() + ")";
        }
        if (kind == Kind.ROUND) {
            return "round(" + inner.name() + ", " + scale + ")";
        }
        return kind == Kind.OF ? attribute : kind.function + "(" + attribute + ")";
    }

    /**
     * The aggregate expression, resolving the source's joins through {@code ctx}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1405} when an {@code Agg.of} function returns {@code null} or an
     *     expression whose Java type is not {@link #type()}; {@code MQ1412} when a filter adds a to-many join
     */
    @Override
    public Expression<C> expression(JoinContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        Expression<C> result = ctx.aggregating(() -> kind == Kind.OF ? expression.apply(ctx, ctx.cb()) : apply(ctx));
        // Only an Agg.of function can return null; failing here names it, where Criteria would fail anonymously.
        if (result == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1405,
                    "Agg.of(\"" + attribute + "\") returned no expression");
        }
        // A mismatch would otherwise surface as a ClassCastException in Row.get, far from the definition.
        Class<?> actual = result.getJavaType();
        if (kind == Kind.OF && actual != null && !ColumnField.reads(type, actual)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1405, String.format(
                    "Agg.of(\"%s\"): declared %s, the expression is %s", attribute, ColumnField.name(type, actual),
                    ColumnField.name(actual, type)));
        }
        return result;
    }

    /** The function over its argument, filtered by the provider's renderer or by the engine's CASE form. */
    @SuppressWarnings("unchecked")
    private Expression<C> apply(JoinContext ctx) {
        CriteriaBuilder cb = ctx.cb();
        if (kind == Kind.ROUND) {
            return (Expression<C>) cb.round((Expression<BigDecimal>) inner.expression(ctx), scale);
        }
        Expression<?> value = argument.apply(ctx, cb);
        AggregateRenderer renderer = ctx.renderOptions().aggregateRenderer().orElse(AggregateRenderer.DEFAULT);
        if (kind == Kind.AVG_EXACT) {
            // A filter goes on the argument, before the sum and the count, so the quotient stays exact.
            Expression<?> filtered = conditions.isEmpty() ? value
                    : cb.selectCase().when(filterCondition(ctx), (Expression) value);
            return (Expression<C>) renderer.exactAverage(cb, (Expression<? extends Number>) filtered);
        }
        if (conditions.isEmpty()) {
            // A count of an identity no row has null is the row count (R-AGG-19).
            return (Expression<C>) (kind == Kind.COUNT ? RowCounts.count(ctx, value) : over(cb, kind, value));
        }
        // A row count counts a constant under the filter; any other count counts what its unfiltered form does, so a
        // LEFT-joined table's unmatched group counts 0 (R-AGG-19). A composite id is refused either way (MQ1412).
        Expression<?> counted = value;
        if (kind == Kind.COUNT) {
            counted = identifying(value);
            if (RowCounts.eligible(ctx, value)) {
                counted = cb.literal(1);
            }
        }
        Predicate condition = filterCondition(ctx);
        return (Expression<C>) renderer.filtered(cb, kind, counted, condition);
    }

    /** The {@link #filter} condition as one predicate; LEFT-joined, and refused when it adds a to-many join. */
    private Predicate filterCondition(JoinContext ctx) {
        CriteriaBuilder cb = ctx.cb();
        // LEFT, as an or/not branch's join is (R-FLT-10): an inner join made here would drop the rows the filter
        // does not match from every other column of the statement.
        int joined = ctx.toManyJoins().size();
        return ctx.leftJoining(() -> ctx.inCase(() -> {
            List<Predicate> parts = ConditionGroup.toPredicates(filters, ctx);
            if (ctx.toManyJoins().size() > joined) {
                throw refused(this + ".filter(...): the condition adds the to-many join "
                        + ctx.toManyJoins().get(joined).getAttribute().getName()
                        + ", which would multiply the aggregated rows; filter on it with exists(...)");
            }
            return parts.isEmpty() ? cb.conjunction()
                    : parts.size() == 1 ? parts.get(0) : cb.and(parts.toArray(Predicate[]::new));
        }));
    }

    /**
     * A table count's argument as a column a CASE can yield: the entity's identifier, which the unfiltered count
     * counts and is never null. Any other argument is returned as it is. Providers cannot convert an entity inside
     * {@code case when ... then <entity> end}. A joined table's model is its attribute, so its entity is read through
     * it.
     */
    private Expression<?> identifying(Expression<?> value) {
        EntityType<?> entity = value instanceof From<?, ?> from ? RowCounts.entityOf(from) : null;
        if (entity == null) {
            return value;
        }
        From<?, ?> from = (From<?, ?>) value;
        SingularAttribute<?, ?> id = null;
        if (entity.hasSingleIdAttribute()) {
            id = entity.getId(entity.getIdType().getJavaType());
        } else if (!entity.getIdClassAttributes().isEmpty()) {
            id = entity.getIdClassAttributes().iterator().next();
        }
        // An embedded id's path is no column, and a composite id has no single one to count.
        if (id == null || id.getPersistentAttributeType() == jakarta.persistence.metamodel.Attribute
                .PersistentAttributeType.EMBEDDED) {
            throw refused(this + ".filter(...): " + entity.getName()
                    + " has a composite or embedded identifier, so its rows cannot be counted under a filter; "
                    + "count a column of it instead");
        }
        return from.get(id.getName());
    }

    /** {@code kind} applied to {@code argument} with standard Criteria, the unfiltered aggregate. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Expression<?> over(CriteriaBuilder cb, Kind kind, Expression<?> argument) {
        return switch (kind) {
            case COUNT -> cb.count(argument);
            case COUNT_DISTINCT -> cb.countDistinct(argument);
            case SUM -> cb.sum((Expression) argument);
            // Only declares the result type, which is the point of sumAsLong.
            case SUM_AS_LONG -> cb.sumAsLong((Expression<Integer>) argument);
            case AVG -> cb.avg((Expression) argument);
            case AVG_EXACT, ROUND -> throw new IllegalArgumentException(kind.function + " has no standard form");
            case MIN -> cb.least((Expression) argument);
            case MAX -> cb.greatest((Expression) argument);
            case OF -> throw new IllegalArgumentException("Agg.of has no argument");
        };
    }

    /** The converted column a {@code min} or {@code max} aggregates, which maps its values; else {@code null}. */
    ColumnField<?, ?, C> converted() {
        return converted;
    }

    /** The expression this aggregate is over, or {@code null} (R-AGG-13). */
    ExpressionField<?, ?> sourceExpression() {
        return sourceExpression;
    }

    void checkSourceVocabulary(SourceVocabulary vocabulary) {
        if (kind == Kind.ROUND) {
            inner.checkSourceVocabulary(vocabulary);
            return;
        }
        if (sourceExpression != null) {
            vocabulary.field(sourceExpression);
        } else if (converted != null) {
            vocabulary.field(converted);
        } else {
            vocabulary.table(source, attribute);
        }
        conditions.forEach(vocabulary::condition);
    }

    void checkTreats(TreatChecks checks) {
        if (kind == Kind.ROUND) {
            inner.checkTreats(checks);
            return;
        }
        checks.table(source, Optional.of(attribute));
        checks.field(converted);
        checks.field(sourceExpression);
        checks.leftJoining(() -> conditions.forEach(checks::condition));
    }

    /** Whether {@code a} and {@code b} are one {@code Agg.of} key defined by different functions (R-AGG-02). */
    static boolean conflict(SelectField<?, ?> a, SelectField<?, ?> b) {
        if (a instanceof AggregateField<?, ?> x && b instanceof AggregateField<?, ?> y
                && x.kind == Kind.ROUND && x.equals(y)) {
            return conflict(x.inner, y.inner);
        }
        return a instanceof AggregateField<?, ?> x && b instanceof AggregateField<?, ?> y
                && x.kind == Kind.OF && x.equals(y) && x.expression != y.expression;
    }

    /** The {@code MQ1103} failure for a conflicting {@code Agg.of} key, found in {@code owner}. */
    static ModelQueryDefinitionException redefined(String owner, SelectField<?, ?> aggregate) {
        return new ModelQueryDefinitionException(MqCode.MQ1103, owner + "." + aggregate.name()
                + ": two Agg.of fields share this name with different expressions; reuse one constant, or give one "
                + "its own key with as(...)");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AggregateField<?, ?> other
                && kind == other.kind
                && Objects.equals(source, other.source)
                && attribute.equals(other.attribute)
                && alias.equals(other.alias)
                && Objects.equals(converterClass(), other.converterClass())
                && Objects.equals(sourceExpression, other.sourceExpression)
                && conditions.equals(other.conditions)
                && Objects.equals(inner, other.inner)
                && scale == other.scale;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /** The class of the converter that maps the result, so a min over an {@code Instant} and a {@code Date} differ. */
    private Class<?> converterClass() {
        return converted == null ? null : converted.converterClass();
    }

    @Override
    public String toString() {
        return name();
    }
}
