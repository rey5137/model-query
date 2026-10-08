package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The field index (spec api/10 R-COL-23, R-COL-24, D-121). */
class FieldIndexTest {

    static final class Order {}

    static final class Customer {}

    static final class Item {}

    static final class OrderView {}

    /** A child that is only a name, equal by identity as a generated constant is: the index calls nothing else. */
    private static final class Items implements ChildField<OrderView, Item> {
        private final String name;

        Items(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public ColumnField<OrderView, ?, ?> key() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ColumnField<Item, ?, ?>> foreignKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TableField<?, ?>> through() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isToMany() {
            return true;
        }

        @Override
        public ModelQuery.Builder<?, ?, Item> query() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderView with(OrderView parent, List<Item> children) {
            throw new UnsupportedOperationException();
        }
    }

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    // As a QModel declares them: each join and column named after its model field (D-55).
    private static final TableField<Order, Customer> CUSTOMER_TABLE =
            TableField.<Order, Customer>join(ROOT, "customer", LEFT).named("customer");

    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class).named("id");
    private static final ColumnField<OrderView, Order, String> STATUS =
            ColumnField.of(OrderView.class, ROOT, "orderStatus", String.class).named("status");
    private static final ColumnField<OrderView, Customer, String> CUSTOMER_NAME =
            ColumnField.of(OrderView.class, CUSTOMER_TABLE, "fullName", String.class).named("name");
    private static final ColumnField<OrderView, Customer, String> CUSTOMER_COUNTRY =
            ColumnField.of(OrderView.class, CUSTOMER_TABLE, "country", String.class);
    // A hand-written column has no property: its attribute path is its key.
    private static final ColumnField<OrderView, Order, Integer> TOTAL =
            ColumnField.of(OrderView.class, ROOT, "total", Integer.class);
    private static final ExpressionField<OrderView, Long> ID_PLUS_ONE = Expr.plus(ID, 1L).named("idPlus");
    private static final AggregateField<OrderView, Long> ORDERS = Agg.<OrderView>count(ROOT).named("orders");
    private static final Items ITEMS = new Items("items");
    private static final Items NOTES = new Items("notes");

    private static final SelectSet<OrderView> CUSTOMER_SET = SelectSet.of(CUSTOMER_NAME);
    private static final SelectSet<OrderView> ALL = SelectSet.of(ID, STATUS, TOTAL, CUSTOMER_NAME);
    private static final SelectSet<OrderView> DEFAULT = SelectSet.of(ID, STATUS);

    private static FieldIndex<OrderView> index() {
        return FieldIndex.builder(OrderView.class)
                .select(ID, STATUS, TOTAL, CUSTOMER_NAME, ID_PLUS_ONE, ORDERS)
                .filterOnly(CUSTOMER_COUNTRY)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .joinSet(CUSTOMER_TABLE, CUSTOMER_SET)
                .child(ITEMS, NOTES)
                .build();
    }

    @Test
    void ac_col_25_each_kind_is_keyed_as_r_col_23_says() {
        var index = index();

        // A property path where there is one, else the attribute path; an expression and an aggregate by name.
        assertThat(index.select("id")).containsSame(ID);
        assertThat(index.select("status")).containsSame(STATUS);
        assertThat(index.select("customer.name")).containsSame(CUSTOMER_NAME);
        assertThat(index.select("total")).containsSame(TOTAL);
        assertThat(index.select("idPlus")).containsSame(ID_PLUS_ONE);
        assertThat(index.select("orders")).containsSame(ORDERS);
        // The attribute path of a column that has a property is not a key (tier 1 only).
        assertThat(index.select("orderStatus")).isEmpty();
        assertThat(index.select("customer.fullName")).isEmpty();
        // A filter-only column is keyed by its attribute path and is not a select field.
        assertThat(index.select("customer.country")).isEmpty();
        assertThat(index.filter("customer.country")).containsSame(CUSTOMER_COUNTRY);
        // A join set is keyed by its join's property path; "customer" is a set, "customer.name" a column.
        assertThat(index.set("customer")).contains(CUSTOMER_SET);
        assertThat(index.set("ALL")).contains(ALL);
        assertThat(index.set("DEFAULT")).contains(DEFAULT);
        assertThat(index.select("customer")).isEmpty();
        assertThat(index.child("items")).containsSame(ITEMS);
        assertThat(index.child("notes")).containsSame(NOTES);
        assertThat(index.child("id")).isEmpty();
    }

    @Test
    void ac_col_25_names_and_filter_names_list_what_resolve_and_filter_accept() {
        var index = index();

        assertThat(index.names()).containsExactly("id", "status", "total", "customer.name", "idPlus", "orders", "ALL",
                "DEFAULT", "customer", "items", "notes");
        assertThat(index.filterNames()).containsExactly("id", "status", "total", "customer.name", "idPlus",
                "customer.country");
        assertThatThrownBy(() -> index.names().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> index.filterNames().remove("id")).isInstanceOf(UnsupportedOperationException.class);
        // filter takes columns and expressions, never an aggregate or a set.
        assertThat(index.filter("idPlus")).containsSame(ID_PLUS_ONE);
        assertThat(index.filter("orders")).isEmpty();
        assertThat(index.filter("ALL")).isEmpty();
        assertThat(index.filter("items")).isEmpty();
    }

    @Test
    void ac_col_25_a_property_less_join_or_a_root_cannot_key_a_set() {
        var builder = FieldIndex.builder(OrderView.class);
        var bare = TableField.<Order, Customer>join(ROOT, "customer", LEFT);

        assertThatThrownBy(() -> builder.joinSet(bare, CUSTOMER_SET)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.joinSet(ROOT, CUSTOMER_SET)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_col_25_an_equal_field_given_twice_is_kept_once() {
        var sameStatus = ColumnField.of(OrderView.class, TableField.root(Order.class), "orderStatus", String.class)
                .named("status");
        var sameCountry = ColumnField.of(OrderView.class,
                TableField.<Order, Customer>join(ROOT, "customer", LEFT).named("customer"), "country", String.class);

        var index = FieldIndex.builder(OrderView.class)
                .select(STATUS, sameStatus, CUSTOMER_COUNTRY)
                .filterOnly(CUSTOMER_COUNTRY, sameCountry, CUSTOMER_COUNTRY)
                .set("DEFAULT", DEFAULT).set("DEFAULT", SelectSet.of(STATUS, ID))
                .child(ITEMS).child(ITEMS)
                .build();

        assertThat(index.names()).containsExactly("status", "customer.country", "DEFAULT", "items");
        assertThat(index.filterNames()).containsExactly("status", "customer.country");
    }

    @Test
    void ac_col_25_a_different_field_under_one_key_throws_when_the_index_is_built() {
        var otherStatus = ColumnField.of(OrderView.class, ROOT, "status", String.class).named("status");
        var rawStatus = ColumnField.of(OrderView.class, ROOT, "status", Integer.class);
        var otherItems = new Items("items");

        assertThatThrownBy(() -> FieldIndex.builder(OrderView.class).select(STATUS, otherStatus).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'status'");
        assertThatThrownBy(() -> FieldIndex.builder(OrderView.class).set("ALL", ALL).set("ALL", DEFAULT).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'ALL'");
        assertThatThrownBy(() -> FieldIndex.builder(OrderView.class).filterOnly(otherStatus, rawStatus).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'status'");
        assertThatThrownBy(() -> FieldIndex.builder(OrderView.class).child(ITEMS).child(otherItems).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'items'");
        // A set is the same kind however it is keyed: a join set and a plain set under one name clash.
        assertThatThrownBy(() -> FieldIndex.builder(OrderView.class).set("customer", ALL)
                .joinSet(CUSTOMER_TABLE, CUSTOMER_SET).build()).isInstanceOf(IllegalStateException.class);
        // A filter-only column against a select column on one key is the same filter name.
        var builder = FieldIndex.builder(OrderView.class).select(STATUS).filterOnly(otherStatus);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class).hasMessageContaining("'status'");
    }

    @Test
    void ac_col_25_a_select_key_and_a_set_key_can_be_one_string() {
        var index = FieldIndex.builder(OrderView.class)
                .select(STATUS)
                .set("status", DEFAULT)
                .build();

        assertThat(index.select("status")).containsSame(STATUS);
        assertThat(index.set("status")).contains(DEFAULT);
        assertThat(index.resolve(List.of("status")).select().fields()).containsExactly(STATUS);
    }

    @Test
    void ac_col_23_resolve_keeps_input_order_expands_a_set_in_place_and_keeps_a_field_once() {
        var resolved = index().resolve(List.of("total", "DEFAULT", "id", "customer", "status"));

        assertThat(resolved.select().fields()).containsExactly(TOTAL, ID, STATUS, CUSTOMER_NAME);
        assertThat(resolved.children()).isEmpty();
        assertThat(resolved.unknown()).isEmpty();
    }

    @Test
    void ac_col_23_resolve_looks_up_select_fields_before_sets_before_children() {
        var index = FieldIndex.builder(OrderView.class)
                .select(STATUS).set("status", SelectSet.of(ID)).child(new Items("status"))
                .set("items", SelectSet.of(TOTAL)).child(ITEMS)
                .build();

        var resolved = index.resolve(List.of("status", "items"));

        assertThat(resolved.select().fields()).containsExactly(STATUS, TOTAL);
        assertThat(resolved.children()).isEmpty();
    }

    @Test
    void ac_col_23_resolve_keeps_each_child_once_in_input_order() {
        var resolved = index().resolve(List.of("notes", "items", "notes", "items"));

        assertThat(resolved.children()).containsExactly(NOTES, ITEMS);
        assertThat(resolved.select().isEmpty()).isTrue();
    }

    @Test
    void ac_col_23_resolve_collects_unknown_names_distinct_in_input_order() {
        // A filter-only column and "" are unknown to resolve, as is a name in another case or an attribute path.
        var resolved = index()
                .resolve(List.of("zip", "id", "customer.country", "", "ZIP", "zip", "orderStatus", "", "Id"));

        assertThat(resolved.select().fields()).containsExactly(ID);
        assertThat(resolved.unknown()).containsExactly("zip", "customer.country", "", "ZIP", "orderStatus", "Id");
    }

    @Test
    void ac_col_23_resolve_of_nothing_or_children_only_gives_an_empty_select() {
        assertThat(index().resolve(List.of()).select().isEmpty()).isTrue();
        assertThat(index().resolve(List.of("items")).select().isEmpty()).isTrue();
        assertThat(index().resolve(List.of("items")).children()).containsExactly(ITEMS);
    }

    @Test
    void ac_col_23_resolve_throws_for_a_null_element_only() {
        var withNull = Arrays.asList("id", null);

        assertThatThrownBy(() -> index().resolve(withNull)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> index().resolve(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> index().select(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ac_col_23_a_resolution_holds_copies() {
        var names = new ArrayList<>(List.of("items", "zip"));
        var resolved = index().resolve(names);
        names.clear();
        var children = new ArrayList<ChildField<OrderView, ?>>(List.of(ITEMS));
        var unknown = new ArrayList<>(List.of("zip"));
        var made = new FieldIndex.Resolution<>(SelectSet.<OrderView>of(), children, unknown);
        children.clear();
        unknown.clear();

        assertThat(resolved.children()).containsExactly(ITEMS);
        assertThat(resolved.unknown()).containsExactly("zip");
        assertThat(made.children()).containsExactly(ITEMS);
        assertThat(made.unknown()).containsExactly("zip");
        assertThatThrownBy(() -> resolved.unknown().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resolved.children().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ac_col_24_only_keeps_those_keys_and_narrows_a_set_to_the_kept_fields() {
        var narrow = index().only(List.of("ALL", "id", "customer", "customer.name", "items", "customer.country"));

        assertThat(narrow.names()).containsExactly("id", "customer.name", "ALL", "customer", "items");
        assertThat(narrow.filterNames()).containsExactly("id", "customer.name", "customer.country");
        assertThat(narrow.set("ALL").orElseThrow().fields()).containsExactly(ID, CUSTOMER_NAME);
        // A set left whole is the same instance.
        assertThat(narrow.set("customer")).containsSame(CUSTOMER_SET);
        assertThat(narrow.resolve(List.of("ALL", "status")).select().fields()).containsExactly(ID, CUSTOMER_NAME);
        assertThat(narrow.resolve(List.of("status", "customer.country")).unknown())
                .containsExactly("status", "customer.country");
        assertThat(narrow.filter("customer.country")).containsSame(CUSTOMER_COUNTRY);
        // The index it came from is unchanged.
        assertThat(index().set("ALL")).contains(ALL);
    }

    @Test
    void ac_col_24_only_with_a_set_name_alone_keeps_the_set_only_when_a_field_survives() {
        var narrow = index().only(List.of("ALL", "total"));

        assertThat(narrow.set("ALL").orElseThrow().fields()).containsExactly(TOTAL);
        assertThat(narrow.only(List.of("total")).names()).containsExactly("total");
    }

    @Test
    void ac_col_24_only_with_an_unknown_name_throws_mq1105_naming_it_and_the_index_names() {
        assertThatThrownBy(() -> index().only(List.of("id", "zip", "Status", "customer.country", "orders")))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1105))
                .hasMessageStartingWith("MQ1105: OrderView: only(...) names no key: [zip, Status]; names() are [id, "
                        + "status, total, customer.name, idPlus, orders, ALL, DEFAULT, customer, items, notes], "
                        + "filterNames() are [id, status, total, customer.name, idPlus, customer.country]");
    }

    @Test
    void ac_col_24_only_leaving_a_kept_set_empty_throws_mq1105() {
        // DEFAULT is [id, status]: keeping it but neither field leaves it empty.
        assertThatThrownBy(() -> index().only(List.of("DEFAULT", "total")))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1105))
                .hasMessageContaining("leaves the set empty: [DEFAULT]");
        assertThatThrownBy(() -> index().only(List.of("ALL", "zip", "DEFAULT")))
                .hasMessageContaining("names no key: [zip]; leaves the set empty: [ALL, DEFAULT]");
        assertThatThrownBy(() -> index().only(Arrays.asList("id", null))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ac_col_26_named_is_outside_equals_and_hash_code_and_survives_as() {
        AggregateField<OrderView, Long> plain = Agg.count(ROOT);
        AggregateField<OrderView, Long> renamed = plain.named("orders");
        AggregateField<OrderView, Long> other = plain.named("count");

        assertThat(renamed).isEqualTo(plain).isEqualTo(other).hasSameHashCodeAs(plain);
        assertThat(plain.property()).isEmpty();
        assertThat(renamed.property()).contains("orders");
        assertThat(renamed.name()).isEqualTo(plain.name());
        assertThat(renamed.as("total").property()).contains("orders");
        assertThat(renamed.as("total")).isNotEqualTo(plain);
        assertThatThrownBy(() -> plain.named(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ac_col_26_an_aggregate_is_keyed_by_its_named_property_else_its_name() {
        AggregateField<OrderView, Long> count = Agg.count(ROOT);
        var index = FieldIndex.builder(OrderView.class).select(count, ORDERS.as("n").named("n2")).build();

        assertThat(index.names()).containsExactly(count.name(), "n2");
        assertThat(index.select(count.name())).containsSame(count);
    }
}
