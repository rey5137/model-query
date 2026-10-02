package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A join's equality is its key (CC-IMM-04, R-COL-03). */
class TableFieldEqualityTest {

    static final class Order {}

    static final class Customer {}

    static final class Address {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);

    @Test
    void cc_imm_04_two_separately_built_equal_joins_are_equal() {
        TableField<Order, Customer> first = TableField.<Order, Customer>join(ROOT, "customer", LEFT).as("c");
        TableField<Order, Customer> second = TableField.<Order, Customer>join(TableField.root(Order.class), "customer", LEFT).as("c");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.on((from, cb) -> cb.conjunction())).isEqualTo(first);
        assertThat(first).isNotEqualTo(first.as("d"));
    }

    @Test
    void cc_imm_04_the_same_attribute_under_different_parents_is_not_equal() {
        TableField<Order, Customer> customer = TableField.join(ROOT, "customer", LEFT);
        TableField<Order, Customer> partner = TableField.join(ROOT, "partner", LEFT);
        TableField<Customer, Address> underCustomer = TableField.join(customer, "address", LEFT);
        TableField<Customer, Address> underPartner = TableField.join(partner, "address", LEFT);

        assertThat(underCustomer).isNotEqualTo(underPartner);
        assertThat(underCustomer).isEqualTo(TableField.join(customer, "address", LEFT));
    }

    @Test
    void cc_imm_04_to_string_names_the_root_the_path_the_type_and_the_alias() {
        TableField<Order, Customer> customer = TableField.join(ROOT, "customer", LEFT);
        TableField<Customer, Address> address = TableField.join(customer, "address", INNER);

        assertThat(ROOT).hasToString("Order");
        assertThat(address).hasToString("Order.customer.address (INNER)");
        assertThat(customer.as("c")).hasToString("Order.customer (LEFT, alias 'c')");
    }
}
