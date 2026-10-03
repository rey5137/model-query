package com.rey.modelquery.test;

import static com.rey.modelquery.test.FilterMatchers.eq;
import static com.rey.modelquery.test.FilterMatchers.gt;
import static com.rey.modelquery.test.QueryAssertions.assertThatQuery;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The child-query assertions of {@link QueryAssert#child} (D-104). */
class ChildQueryAssertTest {

    static final class Order {}

    static final class Item {}

    static final class OrderView {}

    static final class ItemView {}

    private static final TableField<Order, Order> ORDER = TableField.root(Order.class);
    private static final TableField<Item, Item> ITEM = TableField.root(Item.class);
    private static final OrderedColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ORDER, "id", Long.class);
    private static final OrderedColumnField<OrderView, Order, String> NAME =
            ColumnField.of(OrderView.class, ORDER, "name", String.class);
    private static final OrderedColumnField<ItemView, Item, Long> ITEM_ID =
            ColumnField.of(ItemView.class, ITEM, "id", Long.class);
    private static final OrderedColumnField<ItemView, Item, Long> ITEM_ORDER_ID =
            ColumnField.of(ItemView.class, ITEM, "orderId", Long.class);
    private static final OrderedColumnField<ItemView, Item, Integer> QTY =
            ColumnField.of(ItemView.class, ITEM, "qty", Integer.class);
    private static final OrderedColumnField<ItemView, Item, String> SKU =
            ColumnField.of(ItemView.class, ITEM, "sku", String.class);
    private static final ChildField<OrderView, ItemView> ITEMS = child("items");
    private static final ChildField<OrderView, ItemView> GIFTS = child("gifts");
    private static final ChildField<OrderView, ItemView> RETURNS = child("returns");

    /** Loads {@code items} filtered, ordered and bounded, and {@code gifts} with an empty child query. */
    private static ModelQuery<Order, Long, OrderView> query() {
        FetchPlan<ItemView> items = FetchPlan.of(SelectSet.of(ITEM_ID, QTY));
        return ModelQuery.builder(ORDER, row -> new OrderView())
                .primaryKey(PrimaryKey.of(ID))
                .fetch(FetchPlan.of(SelectSet.of(ID, NAME))
                        .child(ITEMS, items, c -> c.where(f -> f.gt(QTY, 1).eq(SKU, Optional.of("A-1")))
                                .orderBy(QTY.desc())
                                .maxPerParent(3))
                        .child(GIFTS, FetchPlan.of(SelectSet.of(ITEM_ID, ITEM_ORDER_ID))))
                .build();
    }

    @Test
    void ac_ins_07_child_asserts_the_child_querys_filters_order_selection_and_max_per_parent() {
        assertThatQuery(query())
                .child(ITEMS)
                .hasFilters(eq(SKU, "A-1"), gt(QTY, 1))
                .containsFilter(gt(QTY, 1))
                .isOrderedBy(QTY.desc())
                .hasSelection(ITEM_ID, QTY, ITEM_ORDER_ID)
                .selectionContains(QTY)
                .hasMaxPerParent(3);
        // The plan already selects the foreign key, so the child query selects it once, where the plan put it.
        assertThatQuery(query())
                .child(GIFTS)
                .hasNoFilters()
                .isNotOrdered()
                .hasSelection(ITEM_ID, ITEM_ORDER_ID)
                .hasNoMaxPerParent();
    }

    @Test
    void ac_ins_07_a_failing_child_assertion_names_the_child() {
        assertThatThrownBy(() -> assertThatQuery(query()).child(ITEMS).hasFilters(gt(QTY, 1)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContainingAll("the child query of OrderView.items", "sku", "A-1");
        assertThatThrownBy(() -> assertThatQuery(query()).child(ITEMS).isOrderedBy(QTY.asc()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expecting the child query of OrderView.items to be ordered by");
        assertThatThrownBy(() -> assertThatQuery(query()).child(ITEMS).hasSelection(ITEM_ID, QTY))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expecting the selection of the child query of OrderView.items to be");
        assertThatThrownBy(() -> assertThatQuery(query()).child(ITEMS).hasMaxPerParent(5))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the child query of OrderView.items to load at most 5 children per parent, "
                        + "but it loads at most 3");
        assertThatThrownBy(() -> assertThatQuery(query()).child(GIFTS).hasMaxPerParent(5))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the child query of OrderView.gifts to load at most 5 children per parent, "
                        + "but it has none");
        assertThatThrownBy(() -> assertThatQuery(query()).child(ITEMS).hasNoMaxPerParent())
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the child query of OrderView.items to have no maxPerParent, "
                        + "but it loads at most 3");
    }

    @Test
    void ac_ins_07_a_child_assertion_keeps_the_querys_description() {
        assertThatThrownBy(() -> assertThatQuery(query()).as("order search").child(ITEMS).hasMaxPerParent(5))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("[order search] Expecting the child query of OrderView.items");
        assertThatThrownBy(() -> assertThatQuery(query()).as("order search").child(ITEMS).hasNoFilters())
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("[order search] Expecting the child query of OrderView.items");
    }

    @Test
    void ac_ins_07_a_missing_child_names_the_children_the_plan_loads() {
        assertThatThrownBy(() -> assertThatQuery(query()).child(RETURNS))
                .isInstanceOf(AssertionError.class)
                .hasMessage(String.format("Expecting the query of OrderView to load the child OrderView.returns, "
                        + "but its fetch plan loads%n  [OrderView.items, OrderView.gifts]"));
        ModelQuery<Order, ?, OrderView> noPlan = ModelQuery.builder(ORDER, row -> new OrderView())
                .select(SelectSet.of(ID, NAME))
                .build();
        assertThatThrownBy(() -> assertThatQuery(noPlan).child(ITEMS))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the query of OrderView to load the child OrderView.items, "
                        + "but it has no fetch plan");
    }

    private static ChildField<OrderView, ItemView> child(String name) {
        return new ChildField<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ColumnField<OrderView, ?, ?> key() {
                return ID;
            }

            @Override
            public Optional<ColumnField<ItemView, ?, ?>> foreignKey() {
                return Optional.of(ITEM_ORDER_ID);
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return Optional.empty();
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, ItemView> query() {
                return ModelQuery.builder(ITEM, row -> new ItemView()).primaryKey(PrimaryKey.of(ITEM_ID));
            }

            @Override
            public OrderView with(OrderView parent, List<ItemView> children) {
                return parent;
            }
        };
    }
}
