package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        assertThat(SelectSet.<OrderView>of().isEmpty()).isTrue();
        assertThat(SelectSet.of(ID).without(ID).isEmpty()).isTrue();
        assertThat(SelectSet.of(ID).isEmpty()).isFalse();
    }

    private static Row rowOf(SelectField<?, ?>... selected) {
        return RowSelection.of(List.of(selected)).row(field -> null);
    }

    @Test
    void ac_col_21_contains_is_exact() {
        var sameAsStatus = ColumnField.of(OrderView.class, TableField.root(Order.class), "status", String.class);
        var converted = ColumnField.of(OrderView.class, ROOT, "status", Integer.class, String.class, new Cv());
        SelectSet<OrderView> set = SelectSet.of(ID, STATUS);

        assertThat(set.contains(ID)).isTrue();
        assertThat(set.contains(sameAsStatus)).isTrue();
        assertThat(set.contains(TOTAL)).isFalse();
        assertThat(set.contains(converted)).isFalse();
    }

    static final class Cv implements ColumnConverter<Integer, String> {
        @Override
        public Integer toModel(String attribute) {
            return attribute.length();
        }

        @Override
        public String toAttribute(Integer model) {
            return "x".repeat(model);
        }
    }

    @Test
    void ac_col_21_equality_ignores_order_and_to_string_lists_in_order() {
        SelectSet<OrderView> a = SelectSet.of(ID, STATUS);
        SelectSet<OrderView> b = SelectSet.of(STATUS, ID);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(SelectSet.of(ID));
        assertThat(a).isNotEqualTo(SelectSet.of(ID, TOTAL));
        assertThat(a).isNotEqualTo("x");
        assertThat(a.toString()).isEqualTo("[OrderView.id, OrderView.status]");
        assertThat(SelectSet.<OrderView>of().toString()).isEqualTo("[]");
    }

    @Test
    void ac_col_22_selected_in_returns_the_subset_in_set_order_and_this_when_all_selected() {
        SelectSet<OrderView> set = SelectSet.of(ID, STATUS, TOTAL);

        assertThat(set.selectedIn(rowOf(ID, STATUS, TOTAL))).isSameAs(set);
        assertThat(set.selectedIn(rowOf(TOTAL, ID)).fields()).containsExactly(ID, TOTAL);
        assertThat(set.selectedIn(rowOf(CODE_OTHER)).fields()).isEmpty();
        SelectSet<OrderView> empty = SelectSet.of();
        assertThat(empty.selectedIn(rowOf(ID))).isSameAs(empty);
    }

    private static final ColumnField<OrderView, Order, String> CODE_OTHER =
            ColumnField.of(OrderView.class, ROOT, "other", String.class);

    @Test
    void ac_col_22_rows_of_one_selection_share_one_instance() {
        SelectSet<OrderView> set = SelectSet.of(ID, STATUS, TOTAL);

        SelectSet<OrderView> first = set.selectedIn(rowOf(ID, TOTAL));

        assertThat(set.selectedIn(rowOf(ID, TOTAL))).isSameAs(first);
        assertThat(set.selectedIn(rowOf(STATUS))).isNotSameAs(first);
        assertThat(set.selectedIn(rowOf(ID, TOTAL)).fields()).containsExactly(ID, TOTAL);
    }

    @Test
    void ac_col_22_selected_in_works_on_a_scoped_row() {
        TableField<Item, Item> itemRoot = TableField.root(Item.class);
        TableField<Item, Order> itemOrder =
                TableField.join(itemRoot, "order", jakarta.persistence.criteria.JoinType.LEFT);
        ColumnField<ItemView, Order, Long> nestedId = ID.withTable(ItemView.class, itemOrder);
        Row row = RowSelection.of(List.of(nestedId)).row(field -> null);

        assertThat(SelectSet.of(ID, STATUS).selectedIn(row.scoped(itemOrder)).fields()).containsExactly(ID);
        assertThat(SelectSet.of(ID).selectedIn(row.scoped(itemOrder))).isEqualTo(SelectSet.of(ID));
    }

    static final class Item {}

    static final class ItemView {}

    @Test
    void ac_col_22_a_set_of_more_than_64_fields_masks_every_position() {
        var all = new ArrayList<ColumnField<OrderView, Order, Long>>();
        for (int i = 0; i < 130; i++) {
            all.add(ColumnField.of(OrderView.class, ROOT, "f" + i, Long.class));
        }
        SelectSet<OrderView> set = SelectSet.copyOf(all);
        var odd = new ArrayList<ColumnField<OrderView, Order, Long>>();
        for (int i = 1; i < all.size(); i += 2) {
            odd.add(all.get(i));
        }
        odd.add(all.get(64));

        SelectSet<OrderView> subset = set.selectedIn(RowSelection.of(odd).row(field -> null));

        assertThat(subset.fields()).containsExactlyElementsOf(all.stream().filter(odd::contains).toList());
        assertThat(subset.fields()).contains(all.get(64), all.get(129)).doesNotContain(all.get(66));
        assertThat(set.selectedIn(RowSelection.of(all).row(field -> null))).isSameAs(set);
        assertThat(set.selectedIn(RowSelection.of(odd).row(field -> null))).isSameAs(subset);
    }

    @Test
    void ac_col_22_concurrent_different_selections_each_get_the_right_subset() throws Exception {
        SelectSet<OrderView> set = SelectSet.of(ID, STATUS, TOTAL);
        List<List<SelectField<OrderView, ?>>> cases = List.of(List.of(ID), List.of(STATUS), List.of(TOTAL),
                List.of(ID, STATUS), List.of(STATUS, TOTAL), List.of(ID, TOTAL), List.of());
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int t = 0; t < 8; t++) {
                int offset = t;
                tasks.add(() -> {
                    for (int n = 0; n < 5000; n++) {
                        var expected = cases.get((n + offset) % cases.size());
                        var row = RowSelection.of(expected).row(field -> null);
                        if (!set.selectedIn(row).fields().equals(expected)) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
