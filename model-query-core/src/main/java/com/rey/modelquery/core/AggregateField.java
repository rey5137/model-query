package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

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
 * @implSpec R-AGG-01, R-AGG-02, R-AGG-04, R-COL-23, D-3, D-84, D-121
 */
@Incubating
public final class AggregateField<M, C> implements SelectField<M, C> {

    /** The aggregate function; its name is the one used in {@link #name()}. */
    enum Kind {
        COUNT("count"),
        COUNT_DISTINCT("countDistinct"),
        SUM("sum"),
        SUM_AS_LONG("sumAsLong"),
        AVG("avg"),
        MIN("min"),
        MAX("max"),
        OF(null);

        private final String function;

        Kind(String function) {
            this.function = function;
        }
    }

    private final Kind kind;
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
    private final BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression;
    private final int hash;

    AggregateField(Kind kind, JoinKey source, String attribute, String alias, Class<C> type,
            ColumnField<?, ?, C> converted, ExpressionField<?, ?> sourceExpression,
            BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        this(kind, source, attribute, alias, type, converted, sourceExpression, null, expression);
    }

    private AggregateField(Kind kind, JoinKey source, String attribute, String alias, Class<C> type,
            ColumnField<?, ?, C> converted, ExpressionField<?, ?> sourceExpression, String property,
            BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        this.kind = kind;
        this.source = source;
        this.attribute = attribute;
        this.alias = alias;
        this.type = type;
        this.converted = converted;
        this.sourceExpression = sourceExpression;
        this.property = property;
        this.expression = expression;
        this.hash = Objects.hash(kind, source, attribute, alias, converterClass(), sourceExpression);
    }

    /**
     * The same aggregate under its own key, for when the same function over the same source is needed twice
     * (R-AGG-01). The alias also becomes its {@link #name()}.
     */
    public AggregateField<M, C> as(String alias) {
        return new AggregateField<>(kind, source, attribute, Objects.requireNonNull(alias, "alias"), type, converted,
                sourceExpression, property, expression);
    }

    /**
     * This aggregate under {@code property} as the name a sort or a {@link FieldIndex} knows it by, in place of the
     * canonical {@link #name()}; not part of {@link #equals}, so it stays equal to the same aggregate written without
     * it (R-AGG-01, R-COL-23).
     */
    public AggregateField<M, C> named(String property) {
        return new AggregateField<>(kind, source, attribute, alias, type, converted, sourceExpression,
                Objects.requireNonNull(property, "property"), expression);
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
        return kind == Kind.OF ? attribute : kind.function + "(" + attribute + ")";
    }

    /**
     * The aggregate expression, resolving the source's joins through {@code ctx}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1405} when an {@code Agg.of} function returns {@code null} or an
     *     expression whose Java type is not {@link #type()}
     */
    @Override
    public Expression<C> expression(JoinContext ctx) {
        Expression<C> result = expression.apply(Objects.requireNonNull(ctx, "ctx"), ctx.cb());
        // Only an Agg.of function can return null; failing here names it, where Criteria would fail anonymously.
        if (result == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1405,
                    "Agg.of(\"" + attribute + "\") returned no expression");
        }
        // A mismatch would otherwise surface as a ClassCastException in Row.get, far from the definition.
        Class<?> actual = result.getJavaType();
        if (kind == Kind.OF && actual != null && ColumnField.boxed(actual) != type) {
            throw new ModelQueryDefinitionException(MqCode.MQ1405, String.format(
                    "Agg.of(\"%s\"): declared %s, the expression is %s", attribute, type.getSimpleName(),
                    actual.getSimpleName()));
        }
        return result;
    }

    /** The converted column a {@code min} or {@code max} aggregates, which maps its values; else {@code null}. */
    ColumnField<?, ?, C> converted() {
        return converted;
    }

    /** The expression this aggregate is over, or {@code null} (R-AGG-13). */
    ExpressionField<?, ?> sourceExpression() {
        return sourceExpression;
    }

    /** Whether {@code a} and {@code b} are one {@code Agg.of} key defined by different functions (R-AGG-02). */
    static boolean conflict(SelectField<?, ?> a, SelectField<?, ?> b) {
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
                && Objects.equals(sourceExpression, other.sourceExpression);
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
