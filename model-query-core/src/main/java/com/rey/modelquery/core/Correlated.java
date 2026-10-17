package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Per-row scalar sub-queries over a sub-select and its recorded correlation, immutable as any expression (INV-9).
 *
 * @implSpec R-COL-31, D-150
 */
@Incubating
public final class Correlated {

    private Correlated() {}

    /** The non-null selected values in the matching rows, or zero when none match (R-COL-31). */
    @Incubating
    public static <M, S, C> ExpressionField<M, Long> count(Class<M> model, SubSelect<S, C> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation) {
        return field(model, rows, correlation, Terminal.COUNT, Long.class);
    }

    /** Whether any row matches the correlation, always true or false, never null (R-COL-31). */
    @Incubating
    public static <M, S, C> ExpressionField<M, Boolean> exists(Class<M> model, SubSelect<S, C> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation) {
        return field(model, rows, correlation, Terminal.EXISTS, Boolean.class);
    }

    /** The least matching value, or null when no non-null value matches (R-COL-31). */
    @Incubating
    public static <M, S, C extends Comparable<? super C>> ExpressionField<M, C> min(Class<M> model,
            SubSelect<S, C> rows, BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation) {
        Objects.requireNonNull(rows, "rows");
        Expr.checkFields(rows.column());
        return field(model, rows, correlation, Terminal.MIN, rows.column().type());
    }

    /** The greatest matching value, or null when no non-null value matches (R-COL-31). */
    @Incubating
    public static <M, S, C extends Comparable<? super C>> ExpressionField<M, C> max(Class<M> model,
            SubSelect<S, C> rows, BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation) {
        Objects.requireNonNull(rows, "rows");
        Expr.checkFields(rows.column());
        return field(model, rows, correlation, Terminal.MAX, rows.column().type());
    }

    /**
     * The sum of the matching values, or null when none match; its type follows {@link Agg#sum(ColumnField)}
     * (R-COL-31, R-AGG-03).
     */
    @Incubating
    public static <M, S, N extends Number> ExpressionField<M, N> sum(Class<M> model, SubSelect<S, N> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation) {
        Objects.requireNonNull(rows, "rows");
        Agg.summable(rows.column(), "Correlated.sum", "use a Long, BigDecimal or Double column");
        return field(model, rows, correlation, Terminal.SUM, rows.column().type());
    }

    /** The first matching value in {@code order}, breaking ties by the inner root's id (R-COL-32). */
    @SafeVarargs
    @Incubating
    public static <M, S, C> ExpressionField<M, C> first(Class<M> model, SubSelect<S, C> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation, OrderField<S, ?> order,
            OrderField<S, ?>... more) {
        Objects.requireNonNull(rows, "rows");
        var orders = new ArrayList<OrderField<S, ?>>();
        orders.add(Objects.requireNonNull(order, "order"));
        orders.addAll(List.of(more));
        FilterGroup.checkSubSelectOrders(rows, orders);
        return field(model, rows, correlation, Terminal.FIRST, rows.column().type(), new ArrayList<>(orders));
    }

    private static <M, S, C> ExpressionField<M, C> field(Class<M> model, SubSelect<S, ?> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation, Terminal terminal, Class<C> type) {
        return field(model, rows, correlation, terminal, type, List.of());
    }

    private static <M, S, C> ExpressionField<M, C> field(Class<M> model, SubSelect<S, ?> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation, Terminal terminal, Class<C> type,
            List<OrderField<?, ?>> orders) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(rows, "rows");
        var filters = FilterGroup.collectCorrelation(rows, correlation, null);
        return new ExpressionField<>(new ExpressionField.CorrelatedValue(rows, filters,
                ConditionGroup.conditions(filters), terminal, type, List.copyOf(orders)), type, null);
    }

    enum Terminal { COUNT, EXISTS, MIN, MAX, SUM, FIRST }
}
