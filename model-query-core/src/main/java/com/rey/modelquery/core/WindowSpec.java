package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One window definition at render time: the function, its argument ({@code null} for a ranking function), the
 * partition terms, the order terms and the frame (R-SRC-04, R-PROC-36). Built by {@link WindowField} from a source
 * model's constants and handed to {@code SourceRenderer#window}, which renders the provider's {@code OVER (...)}
 * clause.
 *
 * @param function the window function
 * @param argument the expression the function reads, or {@code null} for {@code ROW_NUMBER}, {@code RANK} and
 *     {@code DENSE_RANK}
 * @param partitionBy the expressions that split the rows, in order; empty for one partition
 * @param orderBy the order terms, in order; empty for an unordered window
 * @param frame the frame, or {@link WindowFrame#NONE} for the database's default
 * @implSpec R-SRC-04, R-PROC-36
 */
@EngineFacing
@Incubating
public record WindowSpec(WindowFunction function, Expression<?> argument, List<Expression<?>> partitionBy,
        List<Order> orderBy, WindowFrame frame) {

    /** Validates the components. */
    public WindowSpec {
        Objects.requireNonNull(function, "function");
        partitionBy = List.copyOf(partitionBy);
        orderBy = List.copyOf(orderBy);
        Objects.requireNonNull(frame, "frame");
    }

    /**
     * Whether the window's order must be made total before it runs, so that every run of the source gives a row the
     * same value (R-SRC-04): {@code ROW_NUMBER}, {@code LAG}, {@code LEAD}, {@code FIRST_VALUE}, {@code LAST_VALUE},
     * and an aggregate over a {@code ROWS} frame. {@code RANK}, {@code DENSE_RANK} and an aggregate over a
     * {@code RANGE} frame or no frame are deterministic as written, and keep their order unchanged: appending the key
     * would turn {@code RANK} into {@code ROW_NUMBER}.
     */
    public boolean orderSensitive() {
        return switch (function) {
            case ROW_NUMBER, LAG, LEAD, FIRST_VALUE, LAST_VALUE -> true;
            case RANK, DENSE_RANK -> false;
            case SUM, AVG, MIN, MAX, COUNT -> switch (frame) {
                case ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW,
                        ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING,
                        ROWS_CURRENT_ROW_TO_UNBOUNDED_FOLLOWING -> true;
                case NONE, RANGE_UNBOUNDED_PRECEDING_TO_CURRENT_ROW,
                        RANGE_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING -> false;
            };
        };
    }

    /** A copy with {@code extra} appended after the declared order terms (R-SRC-04). */
    public WindowSpec withOrder(List<Order> extra) {
        var all = new ArrayList<>(orderBy);
        all.addAll(extra);
        return new WindowSpec(function, argument, partitionBy, all, frame);
    }
}
