package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Column sets (spec api/10 R-COL-09). */
class SelectSetTest {

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
        SelectSet<OrderView> base = SelectSet.of(ID, STATUS);

        SelectSet<OrderView> added = base.with(TOTAL);
        SelectSet<OrderView> merged = base.with(SelectSet.of(TOTAL));
        SelectSet<OrderView> removed = base.without(STATUS);

        assertThat(base.fields()).containsExactly(ID, STATUS);
        assertThat(added.fields()).containsExactly(ID, STATUS, TOTAL);
        assertThat(merged.fields()).containsExactly(ID, STATUS, TOTAL);
        assertThat(removed.fields()).containsExactly(ID);
    }

    @Test
    void ac_col_05_columns_is_unmodifiable() {
        SelectSet<OrderView> base = SelectSet.of(ID);

        assertThatThrownBy(() -> base.fields().add(STATUS)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> base.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(base.fields()).containsExactly(ID);
    }

    @Test
    void ac_col_05_a_column_appears_once_at_its_first_position() {
        var sameAsStatus = ColumnField.of(OrderView.class, TableField.root(Order.class), "status", String.class);

        SelectSet<OrderView> set = SelectSet.of(ID, STATUS, ID).with(sameAsStatus, TOTAL);

        assertThat(set.fields()).containsExactly(ID, STATUS, TOTAL);
        assertThat(set.without(sameAsStatus).fields()).containsExactly(ID, TOTAL);
    }

    @Test
    void ac_col_05_an_empty_set_selects_nothing() {
        assertThat(SelectSet.<OrderView>of().fields()).isEmpty();
        assertThat(SelectSet.of(ID).without(ID).fields()).isEmpty();
    }
}
