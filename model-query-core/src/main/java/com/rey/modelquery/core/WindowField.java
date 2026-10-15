package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A window-function selection of a {@code @SourceModel} (R-GEN-38, R-PROC-36). It is the constant a {@code @Window}
 * field generates in place of a {@link ColumnField}, built through {@link #of} over the source model's own constants,
 * and it is in the source's {@code ALL} like an {@link AggregateField}, so the source's CTE body selects it and an
 * outer model reads it by field name. It is not a {@link ScalarField} and not an {@code AggregateField}, so the
 * source's {@code where} and {@code having} do not accept it (R-GEN-38).
 *
 * <p>It carries the terms the window is written with, not the SQL: the CTE body builds the {@link WindowSpec} from
 * them and renders it through {@code SourceRenderer#window}, appending the source key first for an order-sensitive
 * window (R-SRC-04). Immutable, so a constant serves any number of concurrent queries (INV-9).
 *
 * @param <M> the source model the selection belongs to
 * @param <C> the field's declared type, which is the window function's result type (INV-3)
 * @implSpec R-GEN-38, R-PROC-36, R-SRC-04
 */
@Incubating
public final class WindowField<M, C> implements SelectField<M, C> {

    private final Class<M> model;
    private final WindowFunction function;
    private final Class<C> type;
    /** The expression the function reads, or {@code null} for a ranking function. */
    private final SelectField<M, ?> argument;
    private final List<SelectField<M, ?>> partitionBy;
    private final List<OrderField<M, ?>> orderBy;
    private final WindowFrame frame;
    /** The property {@link #named} set, or {@code null}; part of {@link #equals}, so two windows stay distinct. */
    private final String property;
    private final int hash;

    private WindowField(Class<M> model, WindowFunction function, Class<C> type, SelectField<M, ?> argument,
            List<SelectField<M, ?>> partitionBy, List<OrderField<M, ?>> orderBy, WindowFrame frame, String property) {
        this.model = Objects.requireNonNull(model, "model");
        this.function = Objects.requireNonNull(function, "function");
        this.type = Objects.requireNonNull(type, "type");
        this.argument = argument;
        this.partitionBy = List.copyOf(partitionBy);
        this.orderBy = List.copyOf(orderBy);
        this.frame = Objects.requireNonNull(frame, "frame");
        this.property = property;
        this.hash = Objects.hash(model, function, type, argument == null ? null : argument.name(),
                names(partitionBy), keys(orderBy), frame, property);
    }

    /**
     * A window selection over {@code model}'s own constants: {@code argument} is the column or aggregate the function
     * reads, {@code null} for a ranking function, and {@code partitionBy} and {@code orderBy} are written the same
     * way (R-GEN-38).
     */
    public static <M, C> WindowField<M, C> of(Class<M> model, WindowFunction function, Class<C> type,
            SelectField<M, ?> argument, List<? extends SelectField<M, ?>> partitionBy,
            List<? extends OrderField<M, ?>> orderBy, WindowFrame frame) {
        return new WindowField<>(model, function, type, argument, List.copyOf(partitionBy), List.copyOf(orderBy),
                frame, null);
    }

    /**
     * This window under {@code property} as the name its CTE column is aliased by, and the name the source's constant
     * is known by (R-GEN-38).
     */
    public WindowField<M, C> named(String property) {
        return new WindowField<>(model, function, type, argument, partitionBy, orderBy, frame,
                Objects.requireNonNull(property, "property"));
    }

    /** The name given to {@link #named}; empty with none. */
    public Optional<String> property() {
        return Optional.ofNullable(property);
    }

    @Override
    public Class<C> type() {
        return type;
    }

    /**
     * The property when one was given, else the function over its argument: {@code sum(total)} or {@code row_number}.
     */
    @Override
    public String name() {
        return property != null ? property : argument == null
                ? function.name().toLowerCase(java.util.Locale.ROOT)
                : function.name().toLowerCase(java.util.Locale.ROOT) + "(" + argument.name() + ")";
    }

    /**
     * The window rendered through {@code ctx}'s {@link RenderOptions} renderer, as written: the source key is
     * appended by the source's CTE body, which alone renders a window field (R-SRC-04).
     *
     * @throws ModelQueryConfigurationException {@code MQ4014} when no {@code SourceRenderer} is configured
     */
    @Override
    @SuppressWarnings("unchecked")
    public Expression<C> expression(JoinContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        SourceRenderer renderer = ctx.renderOptions().sourceRenderer()
                .orElseThrow(() -> new ModelQueryConfigurationException(MqCode.MQ4014, name()
                        + ": the factory's persistence provider has no SourceRenderer (ProviderSupport#sources())"));
        // Unchecked: the renderer declares no element type, and the field's own type is the function's result type
        // (INV-3), which the processor checks.
        return (Expression<C>) renderer.window(ctx.cb(), spec(ctx));
    }

    /**
     * The window as written, resolving its argument, partitions and order terms through {@code ctx}. The source's CTE
     * body appends the key to an order-sensitive window before rendering it (R-SRC-04).
     */
    WindowSpec spec(JoinContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        CriteriaBuilder cb = ctx.cb();
        Expression<?> resolvedArgument = argument == null ? null : argument.expression(ctx);
        List<Expression<?>> partitions = partitionBy.stream().<Expression<?>>map(field -> field.expression(ctx))
                .toList();
        var orders = new ArrayList<Order>();
        for (OrderField<M, ?> order : orderBy) {
            orders.addAll(order.toOrders(ctx, cb));
        }
        return new WindowSpec(function, resolvedArgument, partitions, orders, frame);
    }

    /** The columns this window orders by, which the key append skips, so no key column is repeated (R-SRC-04). */
    List<SelectField<M, ?>> orderedColumns() {
        var columns = new ArrayList<SelectField<M, ?>>(orderBy.size());
        for (OrderField<M, ?> order : orderBy) {
            columns.add(order.column());
        }
        return List.copyOf(columns);
    }

    private static List<String> names(List<? extends SelectField<?, ?>> fields) {
        return fields.stream().map(SelectField::name).toList();
    }

    private static List<String> keys(List<? extends OrderField<?, ?>> orders) {
        return orders.stream().map(order -> order.column().name() + (order.ascending() ? " asc" : " desc")).toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WindowField<?, ?> other
                && model.equals(other.model)
                && function == other.function
                && type.equals(other.type)
                && Objects.equals(argument == null ? null : argument.name(),
                        other.argument == null ? null : other.argument.name())
                && names(partitionBy).equals(names(other.partitionBy))
                && keys(orderBy).equals(keys(other.orderBy))
                && frame == other.frame
                && Objects.equals(property, other.property);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return name();
    }
}
