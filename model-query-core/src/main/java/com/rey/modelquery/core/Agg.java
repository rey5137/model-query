package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import java.math.BigDecimal;
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

    /** {@code count(table)}: the rows of {@code table}, or of the query's root when given the root. */
    public static <M> AggregateField<M, Long> count(TableField<?, ?> table) {
        Objects.requireNonNull(table, "table");
        String name = table.rootEntity() != null ? table.rootEntity().getSimpleName() : table.key().attribute();
        return new AggregateField<>(AggregateField.Kind.COUNT, table.key(), name, "", Long.class, null,
                (ctx, cb) -> cb.count(table.resolve(ctx)));
    }

    /**
     * {@code count(distinct column)}. A column with an {@link OrderedColumnConverter} is counted by its attribute
     * values, which the converter maps one to one.
     *
     * It takes an {@link OrderedColumnField}, so a column with any other {@link ColumnConverter} does not compile
     * (D-93).
     */
    public static <M> AggregateField<M, Long> countDistinct(OrderedColumnField<M, ?, ?> column) {
        return over(AggregateField.Kind.COUNT_DISTINCT, Objects.requireNonNull(column, "column"), Long.class,
                (ctx, cb) -> cb.countDistinct(column.path(ctx)));
    }

    /**
     * {@code sum(column)}, of the column's own type; {@code NULL} over zero rows, never {@code 0} (R-AGG-04). Only a
     * {@code BigDecimal}, {@code Double} or {@code Long} column sums to its own type; for an {@code Integer} or
     * {@code Short} column, which the database sums as a {@code Long}, use {@link #sumAsLong} (R-AGG-03).
     *
     * @throws ModelQueryDefinitionException {@code MQ1403} for any other column type (D-25)
     */
    public static <M, C extends Number> AggregateField<M, C> sum(ColumnField<M, ?, C> column) {
        return over(AggregateField.Kind.SUM, summable(column, SUM_TYPES, "sum", "use Agg.sumAsLong"), column.type(),
                (ctx, cb) -> cb.sum(column.path(ctx)));
    }

    /**
     * {@code sum(column)} read as a {@code Long}, for an {@code Integer}, {@code Short}, {@code Long} or {@code Byte}
     * column (R-AGG-03).
     *
     * @throws ModelQueryDefinitionException {@code MQ1403} for any other column type, whose sum a {@code Long} would
     *     truncate (D-25)
     */
    @SuppressWarnings("unchecked")
    public static <M> AggregateField<M, Long> sumAsLong(ColumnField<M, ?, ? extends Number> column) {
        return over(AggregateField.Kind.SUM_AS_LONG, summable(column, SUM_AS_LONG_TYPES, "sumAsLong", "use Agg.sum"),
                Long.class,
                // Unchecked: sumAsLong only declares the result type, which is the point of this function.
                (ctx, cb) -> cb.sumAsLong((Expression<Integer>) (Expression<?>) column.path(ctx)));
    }

    /** {@code avg(column)}, always a {@code Double}. */
    public static <M, C extends Number> AggregateField<M, Double> avg(ColumnField<M, ?, C> column) {
        supported(Objects.requireNonNull(column, "column"));
        return over(AggregateField.Kind.AVG, column, Double.class,
                (ctx, cb) -> cb.avg(column.path(ctx)));
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
        return extreme(AggregateField.Kind.MIN, column, (ctx, cb) -> cb.least(column.path(ctx)));
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
        return extreme(AggregateField.Kind.MAX, column, (ctx, cb) -> cb.greatest(column.path(ctx)));
    }

    /**
     * A custom aggregate expression, keyed by {@code name} because a lambda cannot be compared (R-AGG-02): two fields
     * sharing a name with different {@code expression} instances throw {@code MQ1103} when the query is built.
     * {@code expression} runs once per query build with that build's {@link JoinContext} and {@code CriteriaBuilder}
     * (D-24), so resolve columns through {@link ColumnField#path} with that context to share the query's joins. It
     * must return an expression of {@code type}, else {@code MQ1405} when the query is built. A primitive
     * {@code type} is stored as its wrapper. It is for aggregate expressions only, since any aggregate makes the query
     * grouped; a value derived per row belongs in {@code afterMap} (R-QRY-08, D-27).
     */
    @SuppressWarnings("unchecked")
    public static <M, C> AggregateField<M, C> of(
            String name, Class<C> type, BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        return new AggregateField<>(AggregateField.Kind.OF, null, Objects.requireNonNull(name, "name"), "",
                // Sound: int.class is a Class<Integer>, so its wrapper is still a Class<C>.
                (Class<C>) ColumnField.boxed(Objects.requireNonNull(type, "type")), null,
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
                    "%s: Agg.%s does not take column type %s, only %s; %s", column, function,
                    column.type().getSimpleName(), types.stream().map(Class::getSimpleName).toList(), instead));
        }
        return column;
    }

    private static <M, C> AggregateField<M, C> over(AggregateField.Kind kind, ColumnField<M, ?, ?> column,
            Class<C> type, BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        return new AggregateField<>(kind, column.table().key(), column.name(), "", type, null, expression);
    }

    /** {@code min} or {@code max}, whose result a converted column's converter maps (R-AGG-04). */
    private static <M, C> AggregateField<M, C> extreme(AggregateField.Kind kind, ColumnField<M, ?, C> column,
            BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        Objects.requireNonNull(column, "column");
        return new AggregateField<>(kind, column.table().key(), column.name(), "", column.type(),
                column.isConverted() ? column : null, expression);
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
