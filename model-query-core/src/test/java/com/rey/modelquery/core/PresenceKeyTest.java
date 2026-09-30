package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** A join's presence key, before any Criteria query exists (spec api/10 R-COL-15, api/13 R-AGG-09, D-38). */
class PresenceKeyTest {

    static final class Order {}

    static final class Customer {}

    static final class OrderView {}

    static final class CustomerView {}

    private static final TableField<Customer, Customer> CUSTOMER_ROOT = TableField.root(Customer.class);
    private static final ColumnField<CustomerView, Customer, Long> CUSTOMER_ID =
            ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "id", Long.class);
    private static final ColumnField<CustomerView, Customer, String> CUSTOMER_NAME =
            ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "name", String.class);
    private static final PrimaryKey<CustomerView, Long> CUSTOMER_KEY = PrimaryKey.of(CUSTOMER_ID);

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Customer> PLAIN = TableField.join(ROOT, "customer", LEFT);
    private static final TableField<Order, Customer> CUSTOMER = PLAIN.presentBy(CUSTOMER_KEY);

    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, Customer, String> NAME =
            CUSTOMER_NAME.withTable(OrderView.class, CUSTOMER);
    private static final ColumnField<OrderView, Customer, Long> KEY =
            CUSTOMER_ID.withTable(OrderView.class, CUSTOMER);

    @Test
    void ac_col_12_present_by_on_a_root_throws_mq1104() {
        assertThatThrownBy(() -> ROOT.presentBy(CUSTOMER_KEY))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1104))
                .hasMessage("MQ1104: root Order: presentBy(...) applies to a join; a root is the query's FROM, not a "
                        + "join");
    }

    @Test
    void ac_col_12_the_presence_key_is_not_part_of_the_join_key() {
        assertThat(CUSTOMER.key()).isEqualTo(PLAIN.key());
        assertThat(NAME).isEqualTo(CUSTOMER_NAME.withTable(OrderView.class, PLAIN));
    }

    @Test
    void ac_col_12_a_grouped_query_selecting_under_a_present_by_join_without_its_key_throws_mq1409() {
        var grouped = ModelQuery.builder(ROOT, row -> new OrderView())
                .columns(ColumnSet.of(NAME, Agg.count(ROOT)))
                .groupBy(NAME);

        assertThatThrownBy(grouped::build)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1409))
                .hasMessage("MQ1409: OrderView.name: selected on a grouped query under the presentBy join 'customer' "
                        + "(LEFT), whose key column id is not in groupBy; a grouped query adds no presence key, so "
                        + "group by the key or select the column through a join without presentBy");
    }

    @Test
    void ac_col_12_a_grouped_query_whose_group_keys_hold_the_presence_key_builds() {
        assertThatCode(() -> ModelQuery.builder(ROOT, row -> new OrderView())
                .columns(ColumnSet.of(NAME, Agg.count(ROOT)))
                .groupBy(NAME, KEY)
                .build()).doesNotThrowAnyException();
        // A join that names no key asks for nothing.
        assertThatCode(() -> ModelQuery.builder(ROOT, row -> new OrderView())
                .columns(ColumnSet.of(CUSTOMER_NAME.withTable(OrderView.class, PLAIN), Agg.count(ROOT)))
                .groupBy(CUSTOMER_NAME.withTable(OrderView.class, PLAIN))
                .build()).doesNotThrowAnyException();
        // Nor does an ungrouped query, which selects the key itself.
        assertThatCode(() -> ModelQuery.builder(ROOT, row -> new OrderView())
                .columns(ColumnSet.of(ID, NAME))
                .build()).doesNotThrowAnyException();
    }

    @Test
    void ac_col_12_as_on_and_with_parent_keep_the_presence_key() {
        TableField<Order, Customer> aliased = CUSTOMER.as("billing");
        TableField<Order, Customer> conditioned = aliased.on((customer, cb) -> cb.isNotNull(customer.get("name")));
        TableField<Order, Customer> reparented = CUSTOMER.withParent(ROOT);

        for (TableField<Order, Customer> join : java.util.List.of(aliased, conditioned, reparented)) {
            ColumnField<OrderView, Customer, String> name = CUSTOMER_NAME.withTable(OrderView.class, join);
            assertThatThrownBy(() -> ModelQuery.builder(ROOT, row -> new OrderView())
                    .columns(ColumnSet.of(name, Agg.count(ROOT)))
                    .groupBy(name)
                    .build())
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1409));
        }
    }
}
