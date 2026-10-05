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

    static final class Address {}

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
    // As a QModel declares them: each join and column named after its model field (D-55).
    private static final TableField<Order, Customer> BUYER = CUSTOMER.named("customer");
    private static final TableField<Order, Address> BILLING =
            TableField.<Order, Address>join(ROOT, "address", LEFT).as("billing").named("billing");
    private static final TableField<Order, Address> SHIPPING =
            TableField.<Order, Address>join(ROOT, "address", LEFT).as("shipping").named("shipping");
    private static final ColumnField<OrderView, Order, String> STATE =
            ColumnField.of(OrderView.class, ROOT, "orderStatus", String.class).named("state");
    private static final ColumnField<OrderView, Customer, String> BUYER_NAME =
            ColumnField.of(OrderView.class, BUYER, "fullName", String.class).named("name");
    private static final ColumnField<OrderView, Address, String> BILLING_CITY =
            ColumnField.of(OrderView.class, BILLING, "city", String.class).named("city");
    private static final ColumnField<OrderView, Address, String> SHIPPING_CITY =
            ColumnField.of(OrderView.class, SHIPPING, "city", String.class).named("city");
    private static final AggregateField<OrderView, Long> ORDERS = Agg.<OrderView>count(ROOT).as("orders");
    private static final ExpressionField<OrderView, Long> ID_PLUS_ONE = Expr.plus(ID, 1L);
    private static final ExpressionField<OrderView, Long> ID_PLUS_ONE_NAMED = ID_PLUS_ONE.named("idPlus");

    private static ModelQuery.Builder<Order, Long, OrderView> selecting(SelectField<OrderView, ?>... columns) {
        return ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(columns))
                .primaryKey(PrimaryKey.of(ID));
    }

    @Test
    void ac_qry_13_a_property_matches_a_selected_column_by_its_path_from_the_root() {
        var query = selecting(STATUS, CUSTOMER_NAME, REFERRER_NAME).build();

        var sorted = query.orderedBy(SortSpec.of(Key.desc("referrer.name"), Key.asc("status")));

        assertThat(sorted.orderBy()).containsExactly(REFERRER_NAME.desc(), STATUS.asc());
    }

    @Test
    void ac_qry_13_a_bare_name_matches_the_root_column_only() {
        // "name" is the root column's path and the attribute of all three columns.
        var query = selecting(CUSTOMER_NAME, NAME, REFERRER_NAME).build();

        var sorted = query.orderedBy(SortSpec.of(Key.asc("name").nulls(NullPrecedence.LAST)));

        assertThat(sorted.orderBy()).containsExactly(NAME.asc().nullsLast());
    }

    @Test
    void ac_qry_13_a_bare_name_of_a_joined_column_throws_mq2301() {
        var query = selecting(STATUS, CUSTOMER_NAME, BUYER_NAME).build();

        // The attribute of a hand-written joined column, and the property of a named one: neither is a path.
        for (String property : List.of("name", "fullName")) {
            assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc(property))))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                    .hasMessage("MQ2301: OrderView: sort property '" + property + "' names no selected column or "
                            + "aggregate; a sort property is a selected column's property path or attribute path "
                            + "from the root, or an aggregate's name, exact and case-sensitive");
        }
    }

    @Test
    void ac_qry_13_a_property_matches_a_root_or_joined_column_by_its_property_path() {
        var query = selecting(STATUS, STATE, BUYER_NAME).build();

        var sorted = query.orderedBy(SortSpec.of(Key.desc("customer.name"), Key.asc("state")));

        assertThat(sorted.orderBy()).containsExactly(BUYER_NAME.desc(), STATE.asc());
    }

    @Test
    void ac_qry_13_a_renamed_field_matches_by_its_property_path_and_by_its_attribute_path() {
        // The nested field "name" reads the attribute "fullName", and the root field "state" reads "orderStatus".
        var query = selecting(STATE, BUYER_NAME).build();

        assertThat(query.orderedBy(SortSpec.of(Key.asc("customer.fullName"), Key.desc("orderStatus"))).orderBy())
                .containsExactly(BUYER_NAME.asc(), STATE.desc());
        assertThat(query.orderedBy(SortSpec.of(Key.asc("customer.name"), Key.desc("state"))).orderBy())
                .containsExactly(BUYER_NAME.asc(), STATE.desc());
    }

    @Test
    void ac_qry_13_a_property_naming_different_columns_on_two_tiers_throws_mq2301() {
        // "status" is the renamed column's property path and STATUS's attribute path (D-58).
        ColumnField<OrderView, Order, String> renamed =
                ColumnField.of(OrderView.class, ROOT, "orderStatus", String.class).named("status");
        var query = selecting(STATUS, renamed).build();

        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("status"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                .hasMessage("MQ2301: OrderView: sort property 'status' names more than one selected column: "
                        + "[status (attribute path), status reading orderStatus (property path)]; name one by a "
                        + "path no other selected column has");
    }

    @Test
    void ac_qry_13_one_column_matched_on_two_tiers_is_not_ambiguous() {
        var named = STATUS.named("status");
        var query = selecting(named, CUSTOMER_NAME).build();

        assertThat(query.orderedBy(SortSpec.of(Key.desc("status"))).orderBy()).containsExactly(named.desc());
    }

    @Test
    void ac_qry_13_two_joins_of_one_attribute_sort_apart_by_their_property_paths() {
        var query = selecting(STATUS, BILLING_CITY, SHIPPING_CITY).build();

        var sorted = query.orderedBy(SortSpec.of(Key.asc("shipping.city"), Key.desc("billing.city")));

        assertThat(sorted.orderBy()).containsExactly(SHIPPING_CITY.asc(), BILLING_CITY.desc());
        // Their attribute path is one, so it names both.
        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("address.city"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                .hasMessage("MQ2301: OrderView: sort property 'address.city' names more than one selected column: "
                        + "[billing.city reading address.city (attribute path), shipping.city reading address.city "
                        + "(attribute path)]; name one by a path no other selected column has");
    }

    @Test
    void ac_qry_13_a_column_without_a_property_matches_by_its_attribute_path_only() {
        // A named column on a join without a property, and an unnamed column on a named join, have no property path.
        ColumnField<OrderView, Customer, String> nick =
                ColumnField.of(OrderView.class, REFERRER, "nickname", String.class).named("nick");
        ColumnField<OrderView, Customer, String> email =
                ColumnField.of(OrderView.class, BUYER, "email", String.class);
        var query = selecting(STATUS, nick, email).build();

        assertThat(query.orderedBy(SortSpec.of(Key.asc("referrer.nickname"), Key.asc("customer.email"))).orderBy())
                .containsExactly(nick.asc(), email.asc());
        for (String property : List.of("referrer.nick", "nick")) {
            assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc(property))))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                    .hasMessageStartingWith("MQ2301: OrderView: sort property '" + property + "' names no selected");
        }
    }

    @Test
    void ac_qry_13_named_returns_a_copy_equal_to_the_original() {
        var named = STATUS.named("state");

        assertThat(named).isNotSameAs(STATUS).isEqualTo(STATUS).hasSameHashCodeAs(STATUS);
        assertThat(STATUS.propertyPath()).isNull();
        assertThat(named.propertyPath()).isEqualTo("state");
        assertThat(named.named("other").propertyPath()).isEqualTo("other");
        assertThat(named.propertyPath()).isEqualTo("state");

        assertThat(BUYER).isNotSameAs(CUSTOMER);
        assertThat(BUYER.key()).isEqualTo(CUSTOMER.key());
        assertThat(CUSTOMER.propertyPath()).isNull();
        assertThat(BUYER.propertyPath()).isEqualTo("customer");
        assertThat(BUYER_NAME).isEqualTo(ColumnField.of(OrderView.class, CUSTOMER, "fullName", String.class));
    }

    @Test
    void ac_qry_13_every_copy_of_a_named_join_or_column_keeps_its_property() {
        TableField<Order, Customer> aliased = BUYER.as("buyer").on((from, cb) -> cb.conjunction());
        // A nested model's own join, on its own root: a root's property is never part of a path.
        var address = TableField.<Customer, Address>join(TableField.root(Customer.class).named("ignored"), "address",
                LEFT).named("address");

        assertThat(aliased.propertyPath()).isEqualTo("customer");
        assertThat(BUYER.presentBy(PrimaryKey.of(ID)).propertyPath()).isEqualTo("customer");
        // A nested model's column re-rooted under the join, the way a QModel declares it (D-38).
        assertThat(address.withParent(BUYER).propertyPath()).isEqualTo("customer.address");
        assertThat(address.propertyPath()).isEqualTo("address");
        var city = ColumnField.of(Customer.class, address, "city", String.class).named("city");
        assertThat(city.withTable(OrderView.class, address.withParent(BUYER)).propertyPath())
                .isEqualTo("customer.address.city");
        assertThat(city.under(OrderView.class, BUYER).propertyPath()).isEqualTo("customer.address.city");
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
                            + "aggregate; a sort property is a selected column's property path or attribute path "
                            + "from the root, or an aggregate's name, exact and case-sensitive");
        }
    }

    @Test
    void ac_qry_13_an_ambiguous_property_throws_mq2301_naming_the_candidates() {
        // Two columns named alike, and STATUS, whose attribute path is the same name.
        ColumnField<OrderView, Order, String> code =
                ColumnField.of(OrderView.class, ROOT, "code", String.class).named("status");
        var query = selecting(STATUS, STATE.named("status"), code).build();

        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("status"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                .hasMessage("MQ2301: OrderView: sort property 'status' names more than one selected column: "
                        + "[status (attribute path), status reading orderStatus (property path), status reading "
                        + "code (property path)]; name one by a path no other selected column has");
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
                        + "selected column: [customer.name (attribute path), customer.name (attribute path)]");
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
        assertThat(sorted.select()).isSameAs(query.select());
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
                .select(SelectSet.of(STATUS, ORDERS))
                .groupBy(STATUS)
                .build();

        var sorted = grouped.orderedBy(SortSpec.of(Key.desc("orders"), Key.asc("status")));

        assertThat(sorted.orderBy()).containsExactly(ORDERS.desc(), STATUS.asc());
        assertThat(sorted.isGrouped()).isTrue();
    }

    @Test
    void ac_qry_13_a_keyset_copy_sorted_by_a_double_column_throws_mq2301_caused_by_mq1207() {
        var query = selecting(STATUS, WEIGHT).orderBy(STATUS.asc()).keyset().build();

        // The sort comes from the request, so the build failure is an execution failure (D-58).
        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("weight"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                .hasMessageStartingWith("MQ2301: OrderView: sort by [weight] does not fit the query: MQ1207: "
                        + "OrderView.weight: keyset() cannot page by a Double column")
                .cause()
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1207));
        // Without keyset() the same sort is an offset order, which a Double column can be.
        assertThat(selecting(STATUS, WEIGHT).build().orderedBy(SortSpec.of(Key.asc("weight"))).orderBy())
                .containsExactly(WEIGHT.asc());
    }

    @Test
    void ac_qry_15_a_keyset_query_ordered_by_an_expression_throws_mq1208_and_builds_without_keyset() {
        assertThatThrownBy(() -> selecting(STATUS).orderBy(ID_PLUS_ONE.asc()).keyset().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1208))
                .hasMessageStartingWith("MQ1208: " + ID_PLUS_ONE.name() + ": keyset() cannot order by an expression");
        // Without keyset() the same order is an offset order, which accepts an expression (R-PAG-25).
        assertThat(selecting(STATUS).orderBy(ID_PLUS_ONE.asc()).build().orderBy())
                .containsExactly(ID_PLUS_ONE.asc());
    }

    @Test
    void ac_qry_15_ordered_by_an_expression_name_sorts_by_it_and_a_keyset_copy_throws_mq2301() {
        var query = selecting(STATUS, ID_PLUS_ONE_NAMED).build();
        assertThat(query.orderedBy(SortSpec.of(Key.asc("idPlus"))).orderBy())
                .containsExactly(ID_PLUS_ONE_NAMED.asc());

        // On a keyset query the build refusal surfaces as MQ2301 with MQ1208 as its cause (R-QRY-16, D-58).
        var keyset = selecting(STATUS, ID_PLUS_ONE_NAMED).keyset().build();
        assertThatThrownBy(() -> keyset.orderedBy(SortSpec.of(Key.asc("idPlus"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                .hasMessageStartingWith("MQ2301: OrderView: sort by [idPlus] does not fit the query: MQ1208: ")
                .cause()
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1208));
    }

    @Test
    void ac_qry_13_an_ungrouped_query_without_a_primary_key_takes_no_sort() {
        var query = ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(STATUS))
                .orderBy(STATUS.asc())
                .build();

        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.desc("status"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301))
                .hasMessageStartingWith("MQ2301: OrderView: an ungrouped query without a primary key takes no sort");
        assertThat(query.orderedBy(SortSpec.unsorted())).isSameAs(query);
    }
}
