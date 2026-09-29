package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Column sets (spec api/10 R-COL-09). */
class ColumnSetTest {

    static final class Order {}

    static final class OrderView {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, Order, String> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", String.class);
    private static final ColumnField<OrderView, Order, Integer> TOTAL =
            ColumnField.of(OrderView.class, ROOT, "total", Integer.class);

    @Test
    void ac_col_05_with_and_without_leave_the_original_set_unchanged() {
        ColumnSet<OrderView> base = ColumnSet.of(ID, STATUS);

        ColumnSet<OrderView> added = base.with(TOTAL);
        ColumnSet<OrderView> merged = base.with(ColumnSet.of(TOTAL));
        ColumnSet<OrderView> removed = base.without(STATUS);

        assertThat(base.columns()).containsExactly(ID, STATUS);
        assertThat(added.columns()).containsExactly(ID, STATUS, TOTAL);
        assertThat(merged.columns()).containsExactly(ID, STATUS, TOTAL);
        assertThat(removed.columns()).containsExactly(ID);
    }

    @Test
    void ac_col_05_columns_is_unmodifiable() {
        ColumnSet<OrderView> base = ColumnSet.of(ID);

        assertThatThrownBy(() -> base.columns().add(STATUS)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> base.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(base.columns()).containsExactly(ID);
    }

    @Test
    void ac_col_05_a_column_appears_once_at_its_first_position() {
        var sameAsStatus = ColumnField.of(OrderView.class, TableField.root(Order.class), "status", String.class);

        ColumnSet<OrderView> set = ColumnSet.of(ID, STATUS, ID).with(sameAsStatus, TOTAL);

        assertThat(set.columns()).containsExactly(ID, STATUS, TOTAL);
        assertThat(set.without(sameAsStatus).columns()).containsExactly(ID, TOTAL);
    }

    @Test
    void ac_col_05_an_empty_set_selects_nothing() {
        assertThat(ColumnSet.<OrderView>of().columns()).isEmpty();
        assertThat(ColumnSet.of(ID).without(ID).columns()).isEmpty();
    }
}
