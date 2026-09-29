package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Recording filters, before any Criteria query exists (spec api/12 §1, R-FLT-01..03). */
class FilterGroupTest {

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
}
