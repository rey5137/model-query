package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Factories for {@link AggregateField}s. Each result type is what the database returns, not the source column's type
 * (R-AGG-03): {@code count} is a {@code Long}, {@code avg} a {@code Double}, and {@code sum} over a 32-bit column
 * goes through {@link #sumAsLong}. Selecting an aggregate makes the query grouped (R-AGG-07).
 *
 * <p>{@link #sum}, {@link #sumAsLong} and {@link #avg} throw {@code MQ1408} for a column that has a
 * {@link ColumnConverter}: the database aggregates attribute values, which the converter cannot be applied to.
 * {@link #of} aggregates such an attribute (R-AGG-04). {@link #min}, {@link #max} and {@link #countDistinct} take an
 * {@link OrderedColumnField}, a column with no converter or an {@link OrderedColumnConverter}, since they commute
 * with it, and a column with any other converter does not compile (D-93): {@code min} and {@code max} return the
 * converter's {@code toModel} of what the database returned, and a {@code having} value is bound as
 * {@code toAttribute} of it.
 *
 * @implSpec api/13 §1, R-AGG-03, R-AGG-04, D-84, D-93
 */
@Incubating
public final class Agg {

    /** Columns the database sums to their own type (R-AGG-03). */
    private static final List<Class<?>> SUM_TYPES = List.of(BigDecimal.class, Double.class, Long.class);
    /** Integral columns, whose sum the database returns as a {@code Long} or wider (R-AGG-03). */
    private static final List<Class<?>> SUM_AS_LONG_TYPES = List.of(Integer.class, Short.class, Long.class, Byte.class);

    private Agg() {}

    /**
     * {@code count(table)}: the rows of {@code table}, or of the query's root when given the root. A source root is
     * the CTE's, whose derived model part has no identifying parts to count or expand, so its rows are counted by a
     * constant (R-SRC-12); count works on every source (RFC 0013 §7). A joined source's table
     * ({@link SourceJoin#root()}) is counted by its first key column, the row count through an INNER join and
     * {@code 0} for an outer row a LEFT join matched no source row for (R-AGG-19, R-SRC-21).
     */
    public static <M> AggregateField<M, Long> count(TableField<?, ?> table) {
        Objects.requireNonNull(table, "table");
        String name = table.rootEntity() != null ? table.rootEntity().getSimpleName() : table.key().attribute();
        if (table.treatedType() != null) {
            String subtype = table.treatedType().getSimpleName();
            name = Character.toLowerCase(subtype.charAt(0)) + subtype.substring(1);
        }
        boolean joined = table.isJoinedSource();
        boolean source = table.isSource();
        String owner = "count(" + table.describe() + ")";
        return new AggregateField<>(AggregateField.Kind.COUNT, table.key(), name, "", Long.class, null, null,
                (ctx, cb) -> joined ? ctx.joinedSourceKey(table.key(), owner)
                        : source ? cb.literal(1) : table.resolve(ctx));
    }

    /**
     * {@code count(distinct column)}. A column with an {@link OrderedColumnConverter} is counted by its attribute
     * values, which the converter maps one to one.
     *
     * It takes an {@link OrderedColumnField}, so a column with any other {@link ColumnConverter} does not compile
     * (D-93).
     */
    public static <M> AggregateField<M, Long> countDistinct(OrderedColumnField<M, ?, ?> column) {
        return over(AggregateField.Kind.COUNT_DISTINCT, Objects.requireNonNull(column, "column"), Long.class);
    }

    /**
     * {@code sum(column)}, of the column's own type; {@code NULL} over zero rows, never {@code 0} (R-AGG-04). Only a
     * {@code BigDecimal}, {@code Double} or {@code Long} column sums to its own type; for an {@code Integer} or
     * {@code Short} column, which the database sums as a {@code Long}, use {@link #sumAsLong} (R-AGG-03).
     *
     * @throws ModelQueryDefinitionException {@code MQ1403} for any other column type (D-25)
     */
    public static <M, C extends Number> AggregateField<M, C> sum(ColumnField<M, ?, C> column) {
        return over(AggregateField.Kind.SUM, summable(column, "Agg.sum", "use Agg.sumAsLong"), column.type());
    }

    /** A column whose sum keeps its type, shared by grouped and correlated sums (R-AGG-03). */
    static <T extends ColumnField<?, ?, ?>> T summable(T column, String function, String instead) {
        return summable(column, SUM_TYPES, function, instead);
    }

    /**
     * {@code sum(column)} read as a {@code Long}, for an {@code Integer}, {@code Short}, {@code Long} or {@code Byte}
     * column (R-AGG-03).
     *
     * @throws ModelQueryDefinitionException {@code MQ1403} for any other column type, whose sum a {@code Long} would
     *     truncate (D-25)
     */
    public static <M> AggregateField<M, Long> sumAsLong(ColumnField<M, ?, ? extends Number> column) {
        return over(AggregateField.Kind.SUM_AS_LONG,
                summable(column, SUM_AS_LONG_TYPES, "Agg.sumAsLong", "use Agg.sum"),
                Long.class);
    }

    /** {@code avg(column)}, always a {@code Double}. */
    public static <M, C extends Number> AggregateField<M, Double> avg(ColumnField<M, ?, C> column) {
        supported(Objects.requireNonNull(column, "column"));
        return over(AggregateField.Kind.AVG, column, Double.class);
    }

    /**
     * The exact average of a {@code BigDecimal}, {@code Long}, {@code Integer} or {@code Short} column,
     * {@code sum(x) / count(x)} read as a {@code BigDecimal}; NULL over no rows (R-AGG-18). Its scale follows the
     * database, so {@link #round} it for money.
     *
     * @throws ModelQueryDefinitionException {@code MQ1411} for any other column type, {@code Double} and
     *     {@code Float} included, whose average is inexact; {@code MQ1408} for a converted column
     */
    @Incubating
    public static <M> AggregateField<M, BigDecimal> avgExact(ColumnField<M, ?, ? extends Number> column) {
        supported(Objects.requireNonNull(column, "column"));
        requireExactType(column.type(), column);
        return over(AggregateField.Kind.AVG_EXACT, column, BigDecimal.class);
    }

    /** The exact average of an expression, as {@link #avgExact(ColumnField)} (R-AGG-18). */
    @Incubating
    public static <M> AggregateField<M, BigDecimal> avgExact(ExpressionField<M, ? extends Number> expression) {
        Objects.requireNonNull(expression, "expression");
        requireExactType(expression.type(), expression);
        return expressionOver(AggregateField.Kind.AVG_EXACT, expression, BigDecimal.class);
    }

    /**
     * {@code value} rounded half away from zero to {@code scale} decimals, still an aggregate for {@code having},
     * {@code orderBy} and the grouping checks. It cannot be filtered: filter {@code value} instead (R-AGG-18).
     *
     * @throws ModelQueryDefinitionException {@code MQ1411} for a {@code scale} outside 0 to 30; {@code MQ1408} for a
     *     {@code min} or {@code max} over a converted column, whose values the rounding would not convert
     */
    @Incubating
    public static <M> AggregateField<M, BigDecimal> round(AggregateField<M, BigDecimal> value, int scale) {
        Objects.requireNonNull(value, "value");
        if (value.converted() != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1408, value + ": round cannot take an aggregate over a "
                    + "converted column, as the database rounds attribute values, not the model's");
        }
        requireScale(scale);
        return AggregateField.rounding(value, scale);
    }

    /**
     * The value of {@code value} on the first row of each group in the order {@code order}, {@code more}, with the
     * root's id appended ascending so a tie picks the same row everywhere (R-AGG-16). It is the value's own type and
     * its converter applies; a group with no row is {@code NULL}. A filtered one ({@link AggregateField#filter}) uses
     * the database's {@code FILTER}, and the provider must render it. Null precedence follows R-COL-12: say
     * {@code nullsFirst()} or {@code nullsLast()}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1412} for an order key that is itself an aggregate or window;
     *     when the query is built, also a value or order key read through a to-many join, whose rows the root's id
     *     does not order. At build time too, {@code MQ4015} when the target's vendor has no form of it and
     *     {@code MQ4014} when the provider cannot render it
     */
    @Incubating
    @SafeVarargs
    public static <M, C> AggregateField<M, C> firstBy(ScalarField<M, C> value, OrderField<M, ?> order,
            OrderField<M, ?>... more) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(more, "more");
        var orders = new ArrayList<OrderField<?, ?>>();
        orders.add(order);
        for (OrderField<M, ?> each : more) {
            orders.add(Objects.requireNonNull(each, "order"));
        }
        for (OrderField<?, ?> each : orders) {
            if (each.column() instanceof ExpressionField<?, ?> key) {
                checkCorrelated(key);
            }
            if (!(each.column() instanceof ScalarField<?, ?>)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1412, "Agg.firstBy: the order key " + each.column()
                        + " is an aggregate or a window, which cannot order the rows of an aggregate; order by a "
                        + "column or expression");
            }
        }
        if (value instanceof ColumnField<M, ?, C> column) {
            return AggregateField.first(column.table().key(), column.name(), column.type(),
                    column.isConverted() ? column : null, null, (ctx, cb) -> column.expression(ctx), orders);
        }
        ExpressionField<M, C> expression = (ExpressionField<M, C>) value;
        checkCorrelated(expression);
        return AggregateField.first(null, "", expression.type(), null, expression,
                (ctx, cb) -> expression.expression(ctx), orders);
    }

    private static final List<Class<?>> EXACT_TYPES =
            List.of(BigDecimal.class, Long.class, Integer.class, Short.class);

    private static void requireExactType(Class<?> type, Object source) {
        if (!EXACT_TYPES.contains(ColumnField.boxed(type))) {
            throw new ModelQueryDefinitionException(MqCode.MQ1411, source + ": avgExact takes a BigDecimal, Long, "
                    + "Integer or Short input, not " + type.getSimpleName() + ", whose average is inexact; use "
                    + "Agg.avg for an approximate one");
        }
    }

    /** {@code MQ1411} for a scale outside 0 to 30, the smallest maximum of the Tier-1 databases (R-AGG-18). */
    static void requireScale(int scale) {
        if (scale < 0 || scale > 30) {
            throw new ModelQueryDefinitionException(MqCode.MQ1411, "round(..., " + scale
                    + "): the scale must be 0 to 30, as MySQL clamps a larger one to 30");
        }
    }

    /**
     * {@code min(column)}, of the column's own type. Over a column with an {@link OrderedColumnConverter} it is the
     * least attribute value, converted to the model's type.
     *
     * It takes an {@link OrderedColumnField}, so a column with any other {@link ColumnConverter} does not compile
     * (D-93).
     */
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> min(
            OrderedColumnField<M, ?, C> column) {
        return extreme(AggregateField.Kind.MIN, column);
    }

    /**
     * {@code max(column)}, of the column's own type. Over a column with an {@link OrderedColumnConverter} it is the
     * greatest attribute value, converted to the model's type.
     *
     * It takes an {@link OrderedColumnField}, so a column with any other {@link ColumnConverter} does not compile
     * (D-93).
     */
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> max(
            OrderedColumnField<M, ?, C> column) {
        return extreme(AggregateField.Kind.MAX, column);
    }

    /**
     * {@code count(value)}: the non-null values of a column or expression (R-AGG-13). A conditional count is
     * {@code count(cases(...).when(condition, 1).orNull())}.
     */
    public static <M> AggregateField<M, Long> count(ScalarField<M, ?> value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof ColumnField<?, ?, ?> column) {
            return new AggregateField<>(AggregateField.Kind.COUNT, column.table().key(), column.name(), "",
                    Long.class, null, null, (ctx, cb) -> column.expression(ctx));
        }
        ExpressionField<M, ?> expression = (ExpressionField<M, ?>) value;
        return expressionOver(AggregateField.Kind.COUNT, expression, Long.class);
    }

    /** {@code count(distinct expression)} (R-AGG-13). */
    public static <M> AggregateField<M, Long> countDistinct(ExpressionField<M, ?> expression) {
        Objects.requireNonNull(expression, "expression");
        return expressionOver(AggregateField.Kind.COUNT_DISTINCT, expression, Long.class);
    }

    /**
     * {@code sum(expression)}, of the expression's type; {@code NULL} over zero rows (R-AGG-04, R-AGG-13).
     *
     * @throws ModelQueryDefinitionException {@code MQ1403} for any expression type other than {@code BigDecimal},
     *     {@code Double} or {@code Long}
     */
    public static <M, C extends Number> AggregateField<M, C> sum(ExpressionField<M, C> expression) {
        Objects.requireNonNull(expression, "expression");
        checkCorrelated(expression);
        requireSumType(expression.type(), SUM_TYPES, "sum", "use Agg.sumAsLong", expression);
        return expressionOver(AggregateField.Kind.SUM, expression, expression.type());
    }

    /**
     * {@code sum(expression)} read as a {@code Long}, for an integral expression (R-AGG-03, R-AGG-13).
     *
     * @throws ModelQueryDefinitionException {@code MQ1403} for any other expression type
     */
    public static <M> AggregateField<M, Long> sumAsLong(ExpressionField<M, ? extends Number> expression) {
        Objects.requireNonNull(expression, "expression");
        checkCorrelated(expression);
        requireSumType(expression.type(), SUM_AS_LONG_TYPES, "sumAsLong", "use Agg.sum", expression);
        return expressionOver(AggregateField.Kind.SUM_AS_LONG, expression, Long.class);
    }

    /** {@code avg(expression)}, always a {@code Double} (R-AGG-13). */
    public static <M, C extends Number> AggregateField<M, Double> avg(ExpressionField<M, C> expression) {
        Objects.requireNonNull(expression, "expression");
        return expressionOver(AggregateField.Kind.AVG, expression, Double.class);
    }

    /** {@code min(expression)}, of the expression's type (R-AGG-13). */
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> min(ExpressionField<M, C> expression) {
        Objects.requireNonNull(expression, "expression");
        return expressionOver(AggregateField.Kind.MIN, expression, expression.type());
    }

    /** {@code max(expression)}, of the expression's type (R-AGG-13). */
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> max(ExpressionField<M, C> expression) {
        Objects.requireNonNull(expression, "expression");
        return expressionOver(AggregateField.Kind.MAX, expression, expression.type());
    }

    /** A custom aggregate expression, keyed by {@code name} because a lambda cannot be compared (R-AGG-02): two fields
     * sharing a name with different {@code expression} instances throw {@code MQ1103} when the query is built.
     * {@code expression} runs once per query build with that build's {@link JoinContext} and {@code CriteriaBuilder}
     * (D-24), so resolve columns through {@link ColumnField#path} with that context to share the query's joins. It
     * must return an expression of {@code type}, else {@code MQ1405} when the query is built. A primitive
     * {@code type} is stored as its wrapper. It is for aggregate expressions only, since any aggregate makes the query
     * grouped; a value derived per row belongs in {@code afterMap} (R-QRY-08, D-27). A correlated value resolved
     * through the context is refused with {@code MQ1510} (R-COL-33).
     */
    @SuppressWarnings("unchecked")
    public static <M, C> AggregateField<M, C> of(
            String name, Class<C> type, BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        return AggregateField.custom(Objects.requireNonNull(name, "name"),
                // Sound: int.class is a Class<Integer>, so its wrapper is still a Class<C>.
                (Class<C>) ColumnField.boxed(Objects.requireNonNull(type, "type")),
                Objects.requireNonNull(expression, "expression"));
    }

    /**
     * {@code column}, checked to be of one of {@code types}. Java cannot overload on the column's type argument, so
     * the check the signature cannot make happens here, when the factory is called (D-25).
     */
    private static <T extends ColumnField<?, ?, ?>> T summable(T column, List<Class<?>> types, String function,
            String instead) {
        // Before the type check: no sum takes a converted column, whatever its type (R-AGG-04).
        supported(Objects.requireNonNull(column, "column"));
        if (!types.contains(column.type())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1403, String.format(
                    "%s: %s does not take column type %s, only %s; %s", column, function,
                    column.type().getSimpleName(), types.stream().map(Class::getSimpleName).toList(), instead));
        }
        return column;
    }

    /** {@link #summable}'s type check for an expression, whose type is all that is known at definition (R-AGG-13). */
    private static void requireSumType(Class<?> type, List<Class<?>> types, String function, String instead,
            Object source) {
        if (!types.contains(ColumnField.boxed(type))) {
            throw new ModelQueryDefinitionException(MqCode.MQ1403, String.format(
                    "%s: Agg.%s does not take expression type %s, only %s; %s", source, function,
                    type.getSimpleName(), types.stream().map(Class::getSimpleName).toList(), instead));
        }
    }

    private static <M, C> AggregateField<M, C> over(AggregateField.Kind kind, ColumnField<M, ?, ?> column,
            Class<C> type) {
        return new AggregateField<>(kind, column.table().key(), column.name(), "", type, null, null,
                (ctx, cb) -> column.path(ctx));
    }

    /** {@code min} or {@code max}, whose result a converted column's converter maps (R-AGG-04). */
    private static <M, C> AggregateField<M, C> extreme(AggregateField.Kind kind, ColumnField<M, ?, C> column) {
        Objects.requireNonNull(column, "column");
        return new AggregateField<>(kind, column.table().key(), column.name(), "", column.type(),
                column.isConverted() ? column : null, null, (ctx, cb) -> column.path(ctx));
    }

    /** An aggregate over an expression, keyed structurally by the expression (R-AGG-13). */
    private static <M, C> AggregateField<M, C> expressionOver(AggregateField.Kind kind,
            ExpressionField<M, ?> expression, Class<C> type) {
        checkCorrelated(expression);
        return new AggregateField<>(kind, null, "", "", type, null, expression,
                (ctx, cb) -> expression.expression(ctx));
    }

    private static void checkCorrelated(ExpressionField<?, ?> expression) {
        if (expression.correlated()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1510,
                    expression + ": a correlated value cannot be aggregated; select it through @Computed");
        }
    }

    /**
     * Refuses a converted column with {@code MQ1408}: {@code sum}, {@code sumAsLong} and {@code avg} do not commute
     * with a converter, even an {@link OrderedColumnConverter} (R-AGG-04, D-84). {@code min}, {@code max} and
     * {@code countDistinct} take an {@link OrderedColumnField}, which the type system keeps to columns they commute
     * with (D-93).
     */
    private static void supported(ColumnField<?, ?, ?> column) {
        if (column.isConverted()) {
            // The database aggregates attribute values, which the converter cannot be applied to.
            throw new ModelQueryDefinitionException(MqCode.MQ1408, column + ": an aggregate function does not take "
                    + "a column that has a ColumnConverter, since the database computes over attribute values; "
                    + "aggregate the attribute with Agg.of");
        }
    }
}
