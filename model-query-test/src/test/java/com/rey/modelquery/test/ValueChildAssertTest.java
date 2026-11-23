package com.rey.modelquery.test;

import static com.rey.modelquery.test.QueryAssertions.assertThatQuery;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.MapChildField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValueChildField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The value-child and map-child assertions of {@link QueryAssert#values} and {@link QueryAssert#map} (D-257). */
class ValueChildAssertTest {

    static final class Order {}

    static final class OrderView {}

    private static final TableField<Order, Order> ORDER = TableField.root(Order.class);
    private static final OrderedColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ORDER, "id", Long.class);
    private static final ValueChildField<OrderView, String> TAGS = values("tags");
    private static final ValueChildField<OrderView, String> TAG_SET = values("tagSet");
    private static final ValueChildField<OrderView, String> CODES = values("codes");
    private static final MapChildField<OrderView, String, String> LABELS = map("labels");
    private static final MapChildField<OrderView, String, String> NOTES = map("notes");

    /** Loads {@code tags} bounded, {@code tagSet} unbounded and the map {@code labels} bounded. */
    private static ModelQuery<Order, Long, OrderView> query() {
        return ModelQuery.builder(ORDER, row -> new OrderView())
                .primaryKey(PrimaryKey.of(ID))
                .fetch(FetchPlan.of(SelectSet.of(ID))
                        .values(TAGS, q -> q.maxPerParent(3))
                        .values(TAG_SET)
                        .map(LABELS, q -> q.maxPerParent(2)))
                .build();
    }

    @Test
    void ac_ins_25_values_and_map_assert_the_loads_a_plan_runs_and_their_max_per_parent() {
        assertThatQuery(query()).values(TAGS).hasMaxPerParent(3);
        assertThatQuery(query()).values(TAG_SET).hasNoMaxPerParent();
        assertThatQuery(query()).map(LABELS).hasMaxPerParent(2);
    }

    @Test
    void ac_ins_25_a_failing_assertion_names_the_load_and_its_bound() {
        assertThatThrownBy(() -> assertThatQuery(query()).values(TAGS).hasMaxPerParent(5))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the values of OrderView.tags to load at most 5 values per parent, "
                        + "but it loads at most 3");
        assertThatThrownBy(() -> assertThatQuery(query()).values(TAG_SET).hasMaxPerParent(5))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the values of OrderView.tagSet to load at most 5 values per parent, "
                        + "but it has none");
        assertThatThrownBy(() -> assertThatQuery(query()).values(TAGS).hasNoMaxPerParent())
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the values of OrderView.tags to have no maxPerParent, but it loads at most 3");
        assertThatThrownBy(() -> assertThatQuery(query()).map(LABELS).hasMaxPerParent(5))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the map of OrderView.labels to load at most 5 entries per parent, "
                        + "but it loads at most 2");
        assertThatThrownBy(() -> assertThatQuery(query()).map(LABELS).hasNoMaxPerParent())
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the map of OrderView.labels to have no maxPerParent, but it loads at most 2");
    }

    @Test
    void ac_ins_25_an_assertion_keeps_the_querys_description() {
        assertThatThrownBy(() -> assertThatQuery(query()).as("order search").values(TAGS).hasMaxPerParent(5))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("[order search] Expecting the values of OrderView.tags");
        assertThatThrownBy(() -> assertThatQuery(query()).as("order search").map(LABELS).hasNoMaxPerParent())
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("[order search] Expecting the map of OrderView.labels");
    }

    @Test
    void ac_ins_25_a_load_the_plan_does_not_run_names_what_it_loads() {
        assertThatThrownBy(() -> assertThatQuery(query()).values(CODES))
                .isInstanceOf(AssertionError.class)
                .hasMessage(String.format("Expecting the query of OrderView to load the values OrderView.codes, "
                        + "but its fetch plan loads%n  [OrderView.tags, OrderView.tagSet, OrderView.labels]"));
        assertThatThrownBy(() -> assertThatQuery(query()).map(NOTES))
                .isInstanceOf(AssertionError.class)
                .hasMessage(String.format("Expecting the query of OrderView to load the map OrderView.notes, "
                        + "but its fetch plan loads%n  [OrderView.tags, OrderView.tagSet, OrderView.labels]"));
        ModelQuery<Order, ?, OrderView> noPlan = ModelQuery.builder(ORDER, row -> new OrderView())
                .select(SelectSet.of(ID))
                .build();
        assertThatThrownBy(() -> assertThatQuery(noPlan).values(TAGS))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the query of OrderView to load the values OrderView.tags, "
                        + "but it has no fetch plan");
        assertThatThrownBy(() -> assertThatQuery(noPlan).map(LABELS))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the query of OrderView to load the map OrderView.labels, "
                        + "but it has no fetch plan");
    }

    private static ValueChildField<OrderView, String> values(String name) {
        return new ValueChildField<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ColumnField<OrderView, ?, ?> key() {
                return ID;
            }

            @Override
            public TableField<?, ?> owner() {
                return ORDER;
            }

            @Override
            public String attribute() {
                return name;
            }

            @Override
            public Class<String> elementType() {
                return String.class;
            }

            @Override
            public boolean isOrdered() {
                return false;
            }

            @Override
            public boolean ordersByValue() {
                return true;
            }

            @Override
            public OrderView with(OrderView parent, List<String> values) {
                return parent;
            }
        };
    }

    private static MapChildField<OrderView, String, String> map(String name) {
        return new MapChildField<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ColumnField<OrderView, ?, ?> key() {
                return ID;
            }

            @Override
            public TableField<?, ?> owner() {
                return ORDER;
            }

            @Override
            public String attribute() {
                return name;
            }

            @Override
            public Class<String> keyType() {
                return String.class;
            }

            @Override
            public Class<String> valueType() {
                return String.class;
            }

            @Override
            public OrderView with(OrderView parent, Map<String, String> entries) {
                return parent;
            }
        };
    }
}
