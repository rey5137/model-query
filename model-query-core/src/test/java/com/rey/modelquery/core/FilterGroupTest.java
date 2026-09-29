package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Recording filters, before any Criteria query exists (spec api/12 §1, R-FLT-01..03, R-FLT-11). */
class FilterGroupTest {

    static final class Order {}

    static final class OrderView {}

    static final class Item {}

    static final class Customer {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, Order, String> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", String.class);
    private static final ColumnField<OrderView, Order, Integer> TOTAL =
            ColumnField.of(OrderView.class, ROOT, "total", Integer.class);
    private static final TableField<Order, Item> ITEMS = TableField.join(ROOT, "items", JoinType.INNER);
    private static final TableField<Order, Item> OTHER_ITEMS = ITEMS.as("other");
    private static final TableField<Order, Customer> CUSTOMER = TableField.join(ROOT, "customer", JoinType.INNER);
    private static final ColumnField<OrderView, Item, String> SKU =
            ColumnField.of(OrderView.class, ITEMS, "sku", String.class);
    private static final ColumnField<OrderView, Item, String> OTHER_SKU =
            ColumnField.of(OrderView.class, OTHER_ITEMS, "sku", String.class);
    private static final ColumnField<OrderView, Customer, String> CUSTOMER_NAME =
            ColumnField.of(OrderView.class, CUSTOMER, "name", String.class);

    @Test
    void ac_flt_02_every_value_form_given_null_throws_mq1301_naming_the_column() {
        String noText = null;
        Integer noNumber = null;
        List<Integer> noList = null;
        Map<String, UnaryOperator<Filters<OrderView>>> calls = Map.ofEntries(
                Map.entry("eq", f -> f.eq(STATUS, noText)),
                Map.entry("ne", f -> f.ne(STATUS, noText)),
                Map.entry("gt", f -> f.gt(TOTAL, noNumber)),
                Map.entry("gte", f -> f.gte(TOTAL, noNumber)),
                Map.entry("lt", f -> f.lt(TOTAL, noNumber)),
                Map.entry("lte", f -> f.lte(TOTAL, noNumber)),
                Map.entry("between", f -> f.between(TOTAL, 1, noNumber)),
                Map.entry("in", f -> f.in(TOTAL, noList)),
                Map.entry("notIn", f -> f.notIn(TOTAL, noList)),
                Map.entry("like", f -> f.like(STATUS, noText, LikeMode.CONTAINS)),
                Map.entry("likeIgnoreCase", f -> f.likeIgnoreCase(STATUS, noText, LikeMode.EXACT)),
                Map.entry("eqIgnoreCase", f -> f.eqIgnoreCase(STATUS, noText)));
        calls.forEach((operator, call) -> assertThatThrownBy(() -> FilterGroup.collect(call))
                .as(operator)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301))
                .hasMessageStartingWith("MQ1301: OrderView.")
                .hasMessageContaining(operator + "(...)"));
    }

    @Test
    void ac_flt_02_a_null_element_in_a_set_throws_mq1301_naming_the_column() {
        List<Integer> withNull = Arrays.asList(1, null);
        for (UnaryOperator<Filters<OrderView>> call :
                List.<UnaryOperator<Filters<OrderView>>>of(f -> f.in(TOTAL, withNull), f -> f.notIn(TOTAL, withNull))) {
            assertThatThrownBy(() -> FilterGroup.collect(call))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301))
                    .hasMessageContaining("OrderView.total").hasMessageContaining("null element");
        }
    }

    @Test
    void ac_flt_02_where_fails_when_it_is_called_not_when_the_query_runs() {
        var builder = ModelQuery.builder(ROOT, row -> new OrderView()).columns(ColumnSet.of(ID));
        String status = null;
        assertThatThrownBy(() -> builder.where(f -> f.eq(STATUS, status)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301))
                .hasMessageContaining("OrderView.status");
    }

    @Test
    void ac_flt_01_an_empty_optional_records_no_filter() {
        List<Filter> recorded = FilterGroup.<OrderView>collect(f -> f
                .eq(STATUS, Optional.empty())
                .ne(STATUS, Optional.empty())
                .gt(TOTAL, Optional.empty())
                .gte(TOTAL, Optional.empty())
                .lt(TOTAL, Optional.empty())
                .lte(TOTAL, Optional.empty())
                .range(TOTAL, Optional.empty(), Optional.empty())
                .between(TOTAL, Optional.empty(), Optional.empty())
                .in(TOTAL, Optional.<List<Integer>>empty())
                .notIn(TOTAL, Optional.<List<Integer>>empty())
                .like(STATUS, Optional.empty(), LikeMode.CONTAINS)
                .likeIgnoreCase(STATUS, Optional.empty(), LikeMode.CONTAINS)
                .eqIgnoreCase(STATUS, Optional.empty())
                .isNull(STATUS, Optional.empty()));
        assertThat(recorded).isEmpty();
    }

    @Test
    void ac_flt_05_empty_sets_are_recorded_not_skipped() {
        assertThat(FilterGroup.<OrderView>collect(f -> f.in(TOTAL, List.of()))).hasSize(1);
        assertThat(FilterGroup.<OrderView>collect(f -> f.notIn(TOTAL, List.of()))).hasSize(1);
    }

    @Test
    void ac_flt_01_every_filter_added_applies_even_when_the_operator_drops_the_return_value() {
        List<Filter> recorded = FilterGroup.<OrderView>collect(f -> {
            f.eq(STATUS, "PAID");
            f.gt(TOTAL, 10);
            return f;
        });
        assertThat(recorded).hasSize(2);
    }

    @Test
    void ac_flt_01_a_filters_reference_kept_past_its_operator_throws_instead_of_being_ignored() {
        List<Filters<OrderView>> leaked = new ArrayList<>();
        List<Filter> recorded = FilterGroup.<OrderView>collect(f -> {
            leaked.add(f);
            return f.eq(STATUS, "PAID");
        });
        assertThat(recorded).hasSize(1);
        Filters<OrderView> late = leaked.get(0);
        List<Runnable> uses = List.of(
                () -> late.eq(STATUS, "NEW"),
                () -> late.eq(STATUS, Optional.empty()),
                () -> late.range(TOTAL, Optional.empty(), Optional.empty()),
                () -> late.isNull(STATUS));
        for (Runnable use : uses) {
            assertThatThrownBy(use::run).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only valid inside its where(...) operator");
        }
        assertThat(recorded).hasSize(1);
    }

    @Test
    void ac_flt_03_an_or_whose_every_branch_was_skipped_records_nothing() {
        Optional<String> none = Optional.empty();
        assertThat(FilterGroup.<OrderView>collect(f -> f.or(a -> a.eq(STATUS, none), b -> b.like(CUSTOMER_NAME, none,
                LikeMode.CONTAINS)))).isEmpty();
        assertThat(FilterGroup.<OrderView>collect(f -> f.or(a -> a, b -> b.eq(STATUS, none)))).isEmpty();
        // One live branch keeps the or; the skipped one is dropped rather than turning into FALSE or TRUE.
        assertThat(FilterGroup.<OrderView>collect(f -> f.or(a -> a.eq(STATUS, none), b -> b.eq(STATUS, "PAID"))))
                .hasSize(1);
    }

    @Test
    void ac_flt_03_not_when_and_apply_skip_like_or() {
        Optional<String> none = Optional.empty();
        UnaryOperator<Filters<OrderView>> paid = g -> g.eq(STATUS, "PAID");
        assertThat(FilterGroup.<OrderView>collect(f -> f.not(g -> g.eq(STATUS, none)))).isEmpty();
        assertThat(FilterGroup.<OrderView>collect(f -> f.not(paid))).hasSize(1);
        assertThat(FilterGroup.<OrderView>collect(f -> f.when(false, paid))).isEmpty();
        assertThat(FilterGroup.<OrderView>collect(f -> f.when(true, paid))).hasSize(1);
        // A false condition never runs the group, so its values are not even checked.
        String missing = null;
        assertThat(FilterGroup.<OrderView>collect(f -> f.when(false, g -> g.eq(STATUS, missing)))).isEmpty();
        // apply ANDs the fragment's filters into the group, one by one.
        assertThat(FilterGroup.<OrderView>collect(f -> f.apply(g -> g.eq(STATUS, "PAID").gt(TOTAL, 1).eq(STATUS,
                none)))).hasSize(2);
    }

    @Test
    void ac_flt_03_a_branch_adding_to_the_enclosing_filters_throws_instead_of_anding() {
        for (UnaryOperator<Filters<OrderView>> misuse : List.<UnaryOperator<Filters<OrderView>>>of(
                f -> f.or(a -> f.eq(STATUS, "PAID")),
                f -> f.not(g -> f.eq(STATUS, "PAID")),
                f -> f.apply(g -> f.eq(STATUS, "PAID")),
                f -> f.exists(ITEMS, i -> f.eq(STATUS, "PAID")))) {
            assertThatThrownBy(() -> FilterGroup.collect(misuse)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nested");
        }
        List<Filters<OrderView>> leaked = new ArrayList<>();
        FilterGroup.<OrderView>collect(f -> f.or(a -> {
            leaked.add(a);
            return a.eq(STATUS, "PAID");
        }));
        assertThatThrownBy(() -> leaked.get(0).eq(STATUS, "NEW")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ac_flt_04_exists_with_every_inner_filter_skipped_records_nothing_but_exists_path_records_one() {
        Optional<String> none = Optional.empty();
        assertThat(FilterGroup.<OrderView>collect(f -> f.exists(ITEMS, i -> i.eq(SKU, none)))).isEmpty();
        assertThat(FilterGroup.<OrderView>collect(f -> f.notExists(ITEMS, i -> i.eq(SKU, none)))).isEmpty();
        assertThat(FilterGroup.<OrderView>collect(f -> f.exists(ITEMS))).hasSize(1);
        assertThat(FilterGroup.<OrderView>collect(f -> f.exists(ITEMS, i -> i.eq(SKU, "A")))).hasSize(1);
    }

    @Test
    void ac_flt_10_a_column_outside_the_exists_path_throws_mq1302_when_where_runs() {
        var builder = ModelQuery.builder(ROOT, row -> new OrderView()).columns(ColumnSet.of(ID));
        Map<String, UnaryOperator<Filters<OrderView>>> outside = Map.of(
                "root column", f -> f.exists(ITEMS, i -> i.eq(STATUS, "PAID")),
                "sibling join", f -> f.exists(ITEMS, i -> i.isNull(CUSTOMER_NAME)),
                "skipped value", f -> f.exists(ITEMS, i -> i.eq(STATUS, Optional.empty())),
                "inside or", f -> f.exists(ITEMS, i -> i.or(a -> a.eq(SKU, "A"), b -> b.eq(STATUS, "PAID"))),
                "unaliased path in an aliased exists", f -> f.notExists(OTHER_ITEMS, i -> i.eq(SKU, "A")),
                "aliased path in an unaliased exists", f -> f.exists(ITEMS, i -> i.eq(OTHER_SKU, "A")),
                "column against column", f -> f.exists(ITEMS, i -> i.compare(SKU, Op.EQ, CUSTOMER_NAME)));
        outside.forEach((name, where) -> assertThatThrownBy(() -> builder.where(where)).as(name)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1302))
                .hasMessageStartingWith("MQ1302: OrderView.")
                .hasMessageContaining("outside the exists(...) path"));
        assertThatThrownBy(() -> builder.where(f -> f.exists(ITEMS, i -> i.exists(CUSTOMER))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1302))
                .hasMessageContaining("join 'customer'");

        assertThat(FilterGroup.<OrderView>collect(f -> f.exists(OTHER_ITEMS, i -> i.eq(OTHER_SKU, "A")))).hasSize(1);
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.exists(ROOT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("root Order");
    }
}
