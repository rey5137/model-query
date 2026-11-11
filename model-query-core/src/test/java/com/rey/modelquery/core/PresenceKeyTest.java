package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A join's presence key, before any Criteria query exists (spec api/10 R-COL-15, api/13 R-AGG-09, D-38). */
class PresenceKeyTest {

    static final class Order {}

    static class Customer {}

    static final class OrderView {}

    static final class CustomerView {}

    static final class Lines {}

    static final class Admin extends Customer {}

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
                .select(SelectSet.of(NAME, Agg.count(ROOT)))
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
                .select(SelectSet.of(NAME, Agg.count(ROOT)))
                .groupBy(NAME, KEY)
                .build()).doesNotThrowAnyException();
        // A join that names no key asks for nothing.
        assertThatCode(() -> ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(CUSTOMER_NAME.withTable(OrderView.class, PLAIN), Agg.count(ROOT)))
                .groupBy(CUSTOMER_NAME.withTable(OrderView.class, PLAIN))
                .build()).doesNotThrowAnyException();
        // Nor does an ungrouped query, which selects the key itself.
        assertThatCode(() -> ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(ID, NAME))
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
                    .select(SelectSet.of(name, Agg.count(ROOT)))
                    .groupBy(name)
                    .build())
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1409));
        }
    }

    @Test
    void ac_col_64_present_by_on_a_joined_entity_root_keeps_the_join() {
        var anchor = ColumnField.of(OrderView.class, ROOT, "customerId", Long.class);
        EntityJoin<OrderView, Customer> buyer = EntityJoin.of("buyer", Customer.class, JoinKind.LEFT,
                List.of(EntityJoin.on(anchor, "id")));

        TableField<Customer, Customer> present = buyer.root().presentBy(CUSTOMER_KEY);

        assertThat(present).isEqualTo(buyer.root()).hasSameHashCodeAs(buyer.root());
        assertThat(present.key()).isEqualTo(buyer.root().key());
        assertThat(present.isJoinedEntity()).isTrue();
        assertThat(present.isJoinedSource()).isFalse();
        assertThat(present.presenceKey()).isSameAs(CUSTOMER_KEY);
        assertThat(present.describe()).isEqualTo(buyer.root().describe());
        TableField<Customer, Customer> named = present.named("purchaser");
        assertThat(named.presenceKey()).isSameAs(CUSTOMER_KEY);
        assertThat(named.withParent(CUSTOMER_ROOT)).isSameAs(named);
        assertThat(named).isEqualTo(buyer.root());
    }

    @Test
    void ac_col_64_present_by_on_a_query_root_a_source_root_a_joined_source_or_a_treated_root_is_mq1104() {
        var key = ColumnField.of(Lines.class, TableField.root(Lines.class), "id", Long.class);
        var id = ColumnField.of(OrderView.class, ROOT, "id", Long.class);
        var lines = SourceJoin.of("lines", Lines.class, JoinKind.LEFT, List.of(SourceJoin.on(id, key)));
        var anchor = ColumnField.of(OrderView.class, ROOT, "customerId", Long.class);
        var buyer = EntityJoin.of("buyer", Customer.class, JoinKind.LEFT, List.of(EntityJoin.on(anchor, "id")));

        for (TableField<?, ?> root : List.of(ROOT, TableField.source(Lines.class), lines.root(),
                CUSTOMER_ROOT.treat(Admin.class), buyer.root().treat(Admin.class))) {
            assertThatThrownBy(() -> root.presentBy(CUSTOMER_KEY))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1104))
                    .hasMessageContaining("presentBy(...) applies to a join");
        }
        assertThatThrownBy(() -> buyer.root().as("other"))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1104));
        assertThatThrownBy(() -> buyer.root().on((customer, cb) -> cb.conjunction()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1104));
    }
}
