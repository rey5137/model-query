package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.SortSpec.Key;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A per-call sort resolved against the selected columns, before any Criteria query exists (spec api/11 R-QRY-14). */
class OrderedByTest {

    static final class Order {}

    static final class Customer {}

    static final class OrderView {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Customer> CUSTOMER = TableField.join(ROOT, "customer", LEFT);
    private static final TableField<Order, Customer> REFERRER = TableField.join(ROOT, "referrer", LEFT);

    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, Order, String> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", String.class);
    private static final ColumnField<OrderView, Order, String> NAME =
            ColumnField.of(OrderView.class, ROOT, "name", String.class);
    private static final ColumnField<OrderView, Order, Double> WEIGHT =
            ColumnField.of(OrderView.class, ROOT, "weight", Double.class);
    private static final ColumnField<OrderView, Customer, String> CUSTOMER_NAME =
            ColumnField.of(OrderView.class, CUSTOMER, "name", String.class);
    private static final ColumnField<OrderView, Customer, String> REFERRER_NAME =
            ColumnField.of(OrderView.class, REFERRER, "name", String.class);
    private static final AggregateField<OrderView, Long> ORDERS = Agg.<OrderView>count(ROOT).as("orders");

    private static ModelQuery.Builder<Order, Long, OrderView> selecting(SelectField<OrderView, ?>... columns) {
        return ModelQuery.builder(ROOT, row -> new OrderView())
                .columns(ColumnSet.of(columns))
                .primaryKey(PrimaryKey.of(ID));
    }

    @Test
    void ac_qry_13_a_property_matches_a_selected_column_by_its_path_from_the_root() {
        var query = selecting(STATUS, CUSTOMER_NAME, REFERRER_NAME).build();

        var sorted = query.orderedBy(SortSpec.of(Key.desc("referrer.name"), Key.asc("status")));

        assertThat(sorted.orderBy()).containsExactly(REFERRER_NAME.desc(), STATUS.asc());
    }

    @Test
    void ac_qry_13_a_property_matches_a_selected_column_by_its_name() {
        var query = selecting(STATUS, CUSTOMER_NAME).build();

        var sorted = query.orderedBy(SortSpec.of(Key.asc("name").nulls(NullPrecedence.LAST)));

        assertThat(sorted.orderBy()).containsExactly(CUSTOMER_NAME.asc().nullsLast());
    }

    @Test
    void ac_qry_13_a_path_match_wins_over_a_name_match() {
        // "name" is the root column's path and the name of all three columns.
        var query = selecting(CUSTOMER_NAME, NAME, REFERRER_NAME).build();

        assertThat(query.orderedBy(SortSpec.of(Key.asc("name"))).orderBy()).containsExactly(NAME.asc());
    }

    @Test
    void ac_qry_13_an_unknown_property_throws_mq2301_naming_it() {
        var query = selecting(STATUS, CUSTOMER_NAME).build();

        // An attribute of the root the query does not select, a wrong case, and a path the query does not select.
        for (String property : List.of("weight", "Status", "referrer.name")) {
            assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc(property))))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                    .hasMessage("MQ2301: OrderView: sort property '" + property + "' names no selected column or "
                            + "aggregate; a sort property is a selected column's attribute path from the root, or "
                            + "its name, exact and case-sensitive");
        }
    }

    @Test
    void ac_qry_13_an_ambiguous_property_throws_mq2301_naming_the_candidates() {
        var query = selecting(STATUS, CUSTOMER_NAME, REFERRER_NAME).build();

        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("status"), Key.asc("name"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                .hasMessage("MQ2301: OrderView: sort property 'name' names more than one selected column: "
                        + "[customer.name, referrer.name]; name one by its attribute path");
    }

    @Test
    void ac_qry_13_two_joins_of_one_attribute_make_its_path_ambiguous() {
        ColumnField<OrderView, Customer, String> aliased =
                ColumnField.of(OrderView.class, CUSTOMER.as("other"), "name", String.class);
        var query = selecting(CUSTOMER_NAME, aliased).build();

        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("customer.name"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                .hasMessageStartingWith("MQ2301: OrderView: sort property 'customer.name' names more than one "
                        + "selected column: [customer.name, customer.name]");
    }

    @Test
    void ac_qry_13_an_empty_spec_returns_the_same_instance() {
        var query = selecting(STATUS).orderBy(STATUS.desc()).build();

        assertThat(query.orderedBy(SortSpec.unsorted())).isSameAs(query);
        assertThat(query.orderedBy(new SortSpec(List.of()))).isSameAs(query);
    }

    @Test
    void ac_qry_13_the_spec_replaces_the_definitions_order_and_leaves_the_definition_as_it_was() {
        var query = selecting(STATUS, CUSTOMER_NAME).orderBy(STATUS.desc(), ID.asc()).keyset().build();

        var sorted = query.orderedBy(SortSpec.of(Key.asc("customer.name")));

        assertThat(sorted).isNotSameAs(query);
        assertThat(sorted.orderBy()).containsExactly(CUSTOMER_NAME.asc());
        assertThat(sorted.spec().orderBy()).containsExactly(CUSTOMER_NAME.asc());
        assertThat(query.orderBy()).containsExactly(STATUS.desc(), ID.asc());
        assertThat(sorted.columns()).isSameAs(query.columns());
        assertThat(sorted.primaryKey()).isEqualTo(query.primaryKey());
        assertThat(sorted.isKeyset()).isTrue();
        // The phase check of the definition covers every copy, a copy of a copy included (R-QRY-11).
        assertThat(query.definition()).isSameAs(query);
        assertThat(sorted.definition()).isSameAs(query);
        assertThat(sorted.orderedBy(SortSpec.of(Key.desc("status"))).definition()).isSameAs(query);
    }

    @Test
    void ac_qry_13_a_spec_is_a_copy_of_the_keys_it_was_given() {
        var keys = new ArrayList<>(List.of(Key.asc("status")));
        var spec = new SortSpec(keys);
        keys.add(Key.desc("name"));

        assertThat(spec.keys()).containsExactly(new Key("status", true, NullPrecedence.DEFAULT));
        assertThat(spec).isEqualTo(SortSpec.of(Key.asc("status")));
    }

    @Test
    void ac_qry_13_a_grouped_query_sorts_by_an_aggregates_name() {
        var grouped = ModelQuery.builder(ROOT, row -> new OrderView())
                .columns(ColumnSet.of(STATUS, ORDERS))
                .groupBy(STATUS)
                .build();

        var sorted = grouped.orderedBy(SortSpec.of(Key.desc("orders"), Key.asc("status")));

        assertThat(sorted.orderBy()).containsExactly(ORDERS.desc(), STATUS.asc());
        assertThat(sorted.isGrouped()).isTrue();
    }

    @Test
    void ac_qry_13_a_keyset_copy_sorted_by_a_double_column_throws_mq1207() {
        var query = selecting(STATUS, WEIGHT).orderBy(STATUS.asc()).keyset().build();

        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("weight"))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1207))
                .hasMessageStartingWith("MQ1207: OrderView.weight: keyset() cannot page by a Double column");
        // Without keyset() the same sort is an offset order, which a Double column can be.
        assertThat(selecting(STATUS, WEIGHT).build().orderedBy(SortSpec.of(Key.asc("weight"))).orderBy())
                .containsExactly(WEIGHT.asc());
    }
}
