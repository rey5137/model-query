package com.rey.modelquery.core;

import static com.rey.modelquery.annotations.WindowFrame.NONE;
import static com.rey.modelquery.annotations.WindowFrame.RANGE_UNBOUNDED_PRECEDING_TO_CURRENT_ROW;
import static com.rey.modelquery.annotations.WindowFrame.RANGE_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING;
import static com.rey.modelquery.annotations.WindowFrame.ROWS_CURRENT_ROW_TO_UNBOUNDED_FOLLOWING;
import static com.rey.modelquery.annotations.WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW;
import static com.rey.modelquery.annotations.WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING;
import static com.rey.modelquery.annotations.WindowFunction.AVG;
import static com.rey.modelquery.annotations.WindowFunction.COUNT;
import static com.rey.modelquery.annotations.WindowFunction.DENSE_RANK;
import static com.rey.modelquery.annotations.WindowFunction.FIRST_VALUE;
import static com.rey.modelquery.annotations.WindowFunction.LAG;
import static com.rey.modelquery.annotations.WindowFunction.LAST_VALUE;
import static com.rey.modelquery.annotations.WindowFunction.LEAD;
import static com.rey.modelquery.annotations.WindowFunction.MAX;
import static com.rey.modelquery.annotations.WindowFunction.MIN;
import static com.rey.modelquery.annotations.WindowFunction.RANK;
import static com.rey.modelquery.annotations.WindowFunction.ROW_NUMBER;
import static com.rey.modelquery.annotations.WindowFunction.SUM;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import jakarta.persistence.criteria.Order;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The core rule a window follows before it is rendered: which functions need a total order, and what the engine
 * appends to give them one (spec api/17 R-SRC-04, R-GEN-38). The rendered SQL of each function and frame is pinned by
 * the TCK's AC-SRC-05 case; this pins the rule the append is decided by.
 */
class WindowFieldTest {

    static final class Entity {}

    static final class Source {}

    private static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);
    private static final ColumnField<Source, Entity, Long> ID =
            ColumnField.of(Source.class, ROOT, "id", Long.class).named("id");
    private static final ColumnField<Source, Entity, String> STATUS =
            ColumnField.of(Source.class, ROOT, "status", String.class).named("status");
    private static final ColumnField<Source, Entity, LocalDateTime> PLACED_AT =
            ColumnField.of(Source.class, ROOT, "placedAt", LocalDateTime.class).named("placedAt");
    private static final ColumnField<Source, Entity, BigDecimal> TOTAL =
            ColumnField.of(Source.class, ROOT, "total", BigDecimal.class).named("total");

    /** A window over the source's statuses, ordered as {@code window}'s own {@code orderBy} says. */
    private static WindowField<Source, Long> window(WindowFunction function, WindowFrame frame) {
        return WindowField.of(Source.class, function, Long.class, TOTAL, List.of(STATUS), List.of(PLACED_AT.asc()),
                frame).named("w");
    }

    private static WindowSpec spec(WindowFunction function, WindowFrame frame) {
        return new WindowSpec(function, null, List.of(), List.of(), frame);
    }

    @Test
    void ac_src_05_a_ranking_function_keeps_its_order_so_its_key_is_appended_only_when_it_counts_no_peers() {
        assertThat(spec(ROW_NUMBER, NONE).orderSensitive()).isTrue();
        assertThat(spec(RANK, NONE).orderSensitive()).isFalse();
        assertThat(spec(DENSE_RANK, NONE).orderSensitive()).isFalse();
    }

    @Test
    void ac_src_05_the_functions_that_read_a_row_offset_always_take_the_key() {
        for (WindowFrame frame : List.of(NONE, ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW)) {
            assertThat(spec(LAG, frame).orderSensitive()).isTrue();
            assertThat(spec(LEAD, frame).orderSensitive()).isTrue();
            assertThat(spec(FIRST_VALUE, frame).orderSensitive()).isTrue();
            assertThat(spec(LAST_VALUE, frame).orderSensitive()).isTrue();
        }
    }

    @Test
    void ac_src_05_an_aggregate_takes_the_key_under_a_rows_frame_and_keeps_its_order_otherwise() {
        for (WindowFunction function : List.of(SUM, AVG, MIN, MAX, COUNT)) {
            assertThat(spec(function, ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW).orderSensitive()).isTrue();
            assertThat(spec(function, ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING).orderSensitive()).isTrue();
            assertThat(spec(function, ROWS_CURRENT_ROW_TO_UNBOUNDED_FOLLOWING).orderSensitive()).isTrue();
            assertThat(spec(function, RANGE_UNBOUNDED_PRECEDING_TO_CURRENT_ROW).orderSensitive()).isFalse();
            assertThat(spec(function, RANGE_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING).orderSensitive()).isFalse();
            assertThat(spec(function, NONE).orderSensitive()).isFalse();
        }
    }

    @Test
    void ac_src_05_the_key_is_appended_after_the_window_own_order_and_leaves_it_unchanged() {
        Order own = order("placedAt");
        Order key = order("id");
        WindowSpec spec = new WindowSpec(SUM, null, List.of(), List.of(own), NONE);

        WindowSpec withKey = spec.withOrder(List.of(key));

        assertThat(withKey.orderBy()).containsExactly(own, key);
        assertThat(spec.orderBy()).containsExactly(own);
        assertThat(withKey.function()).isEqualTo(spec.function());
        assertThat(withKey.frame()).isEqualTo(spec.frame());
    }

    @Test
    void ac_src_05_a_window_reports_the_columns_it_already_orders_by_so_a_key_column_is_not_repeated() {
        WindowField<Source, Long> byPlacedAt = window(ROW_NUMBER, NONE);
        WindowField<Source, Long> byKey = WindowField.of(Source.class, ROW_NUMBER, Long.class, null, List.of(STATUS),
                List.of(ID.asc()), NONE).named("w");

        assertThat(byPlacedAt.orderedColumns()).containsExactly(PLACED_AT);
        assertThat(byKey.orderedColumns()).containsExactly(ID);
    }

    @Test
    void r_gen_38_a_window_constant_names_itself_by_the_property_it_was_named_with() {
        WindowField<Source, Long> unnamed = WindowField.of(Source.class, ROW_NUMBER, Long.class, null, List.of(STATUS),
                List.of(PLACED_AT.asc()), NONE);

        assertThat(unnamed.named("rank").property()).contains("rank");
        assertThat(unnamed.property()).isEmpty();
        assertThat(unnamed.named("rank")).isNotEqualTo(unnamed.named("other"));
        assertThat(unnamed.type()).isEqualTo(Long.class);
        assertThat(unnamed.named("rank").name()).isEqualTo("rank");
        assertThat(unnamed.name()).isEqualTo("row_number");
        assertThat(WindowField.of(Source.class, SUM, BigDecimal.class, TOTAL, List.of(STATUS),
                List.of(PLACED_AT.asc()), NONE).name()).isEqualTo("sum(total)");
    }

    /**
     * A bare {@link Order} no provider built, so the append rule is checked without a provider's criteria objects.
     * JPA 3.2's {@code Order} takes a method whose return type JPA 3.1 has not, so no stub class can implement both
     * lines' interface; a proxy satisfies whichever one the line compiles against. It carries no terms: the rule
     * compares the orders by identity, and {@code name} is what a failure prints.
     */
    private static Order order(String name) {
        return (Order) Proxy.newProxyInstance(Order.class.getClassLoader(), new Class<?>[] {Order.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
