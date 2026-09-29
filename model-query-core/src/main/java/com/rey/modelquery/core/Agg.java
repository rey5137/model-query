package com.rey.modelquery.core;

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
 * @implSpec api/13 §1, R-AGG-03
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
        return new AggregateField<>(AggregateField.Kind.COUNT, table.key(), name, "", Long.class,
                (ctx, cb) -> cb.count(table.resolve(ctx)));
    }

    /** {@code count(distinct column)}. */
    public static <M> AggregateField<M, Long> countDistinct(ColumnField<M, ?, ?> column) {
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
        return over(AggregateField.Kind.AVG, Objects.requireNonNull(column, "column"), Double.class,
                (ctx, cb) -> cb.avg(column.path(ctx)));
    }

    /** {@code min(column)}, of the column's own type. */
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> min(ColumnField<M, ?, C> column) {
        return over(AggregateField.Kind.MIN, Objects.requireNonNull(column, "column"), column.type(),
                (ctx, cb) -> cb.least(column.path(ctx)));
    }

    /** {@code max(column)}, of the column's own type. */
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> max(ColumnField<M, ?, C> column) {
        return over(AggregateField.Kind.MAX, Objects.requireNonNull(column, "column"), column.type(),
                (ctx, cb) -> cb.greatest(column.path(ctx)));
    }

    /**
     * A custom aggregate expression, keyed by {@code name} because a lambda cannot be compared (R-AGG-02): two fields
     * sharing a name with different {@code expression} instances throw {@code MQ1103} when the query is built.
     * {@code expression} runs once per query build with that build's {@link JoinContext} and {@code CriteriaBuilder}
     * (D-24), so resolve columns through {@link ColumnField#path} with that context to share the query's joins. It
     * must return an expression of {@code type}. A primitive {@code type} is stored as its wrapper.
     */
    @SuppressWarnings("unchecked")
    public static <M, C> AggregateField<M, C> of(
            String name, Class<C> type, BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        return new AggregateField<>(AggregateField.Kind.OF, null, Objects.requireNonNull(name, "name"), "",
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
        Objects.requireNonNull(column, "column");
        if (!types.contains(column.type())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1403, String.format(
                    "%s: Agg.%s does not take column type %s, only %s; %s", column, function,
                    column.type().getSimpleName(), types.stream().map(Class::getSimpleName).toList(), instead));
        }
        return column;
    }

    private static <M, C> AggregateField<M, C> over(AggregateField.Kind kind, ColumnField<M, ?, ?> column,
            Class<C> type, BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression) {
        return new AggregateField<>(kind, column.table().key(), column.name(), "", type, expression);
    }
}
