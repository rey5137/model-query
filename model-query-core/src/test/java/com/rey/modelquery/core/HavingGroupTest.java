package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Recording {@code having} filters, before any Criteria query exists (spec api/13 R-AGG-06). */
class HavingGroupTest {

    static final class Order {}

    static final class Summary {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final OrderedColumnField<Summary, Order, Long> TOTAL =
            ColumnField.of(Summary.class, ROOT, "total", Long.class);
    private static final OrderedColumnField<Summary, Order, String> STATUS =
            ColumnField.of(Summary.class, ROOT, "status", String.class);
    private static final AggregateField<Summary, Long> COUNT = Agg.count(ROOT);
    private static final AggregateField<Summary, Long> SUM = Agg.sum(TOTAL);
    private static final AggregateField<Summary, String> FIRST_STATUS = Agg.min(STATUS);

    @Test
    void ac_agg_07_an_empty_optional_records_no_having_filter() {
        HavingGroup.Clause recorded = HavingGroup.<Summary>collect(h -> h
                .eq(SUM, Optional.empty())
                .ne(SUM, Optional.empty())
                .gt(SUM, Optional.empty())
                .gte(SUM, Optional.empty())
                .lt(SUM, Optional.empty())
                .lte(SUM, Optional.empty())
                .range(SUM, Optional.empty(), Optional.empty())
                .between(SUM, Optional.empty(), Optional.empty())
                .in(SUM, Optional.<List<Long>>empty())
                .notIn(SUM, Optional.<List<Long>>empty())
                .like(FIRST_STATUS, Optional.empty(), LikeMode.CONTAINS)
                .likeIgnoreCase(FIRST_STATUS, Optional.empty(), LikeMode.CONTAINS)
                .eqIgnoreCase(FIRST_STATUS, Optional.empty())
                .isNull(SUM, Optional.empty()));
        assertThat(recorded.filters()).isEmpty();
    }

    @Test
    void ac_agg_07_an_or_whose_every_branch_was_skipped_records_nothing() {
        Optional<Long> none = Optional.empty();
        assertThat(HavingGroup.<Summary>collect(h -> h.or(a -> a.gt(SUM, none), b -> b.lt(COUNT, none))).filters())
                .isEmpty();
        // One live branch keeps the or; the skipped one is dropped rather than turning into FALSE or TRUE.
        assertThat(HavingGroup.<Summary>collect(h -> h.or(a -> a.gt(SUM, none), b -> b.lt(COUNT, 3L))).filters())
                .hasSize(1);
    }

    @Test
    void ac_agg_07_an_empty_or_list_is_recorded_as_false_but_an_all_skipped_list_is_skipped() {
        Optional<Long> none = Optional.empty();
        assertThat(HavingGroup.<Summary>collect(h -> h.or(List.of())).filters()).hasSize(1);
        assertThat(HavingGroup.<Summary>collect(h -> h.or(List.of(a -> a.gt(SUM, none)))).filters()).isEmpty();
    }

    @Test
    void ac_agg_07_not_when_and_apply_skip_like_or() {
        Optional<Long> none = Optional.empty();
        UnaryOperator<Having<Summary>> many = g -> g.gt(COUNT, 1L);
        assertThat(HavingGroup.<Summary>collect(h -> h.not(g -> g.eq(SUM, none))).filters()).isEmpty();
        assertThat(HavingGroup.<Summary>collect(h -> h.not(many)).filters()).hasSize(1);
        assertThat(HavingGroup.<Summary>collect(h -> h.when(false, many)).filters()).isEmpty();
        assertThat(HavingGroup.<Summary>collect(h -> h.when(true, many)).filters()).hasSize(1);
        assertThat(HavingGroup.<Summary>collect(h -> h.apply(g -> g.gt(COUNT, 1L).lt(SUM, none))).filters())
                .hasSize(1);
    }

    @Test
    void ac_agg_07_a_value_form_given_null_throws_mq1301_naming_the_aggregate() {
        Long missing = null;
        assertThatThrownBy(() -> HavingGroup.<Summary>collect(h -> h.gt(SUM, missing)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301))
                .hasMessageStartingWith("MQ1301: sum(total): gt(...)");
    }

    @Test
    void ac_agg_07_a_having_reference_kept_past_its_operator_throws_instead_of_being_ignored() {
        List<Having<Summary>> leaked = new ArrayList<>();
        HavingGroup.Clause recorded = HavingGroup.<Summary>collect(h -> {
            leaked.add(h);
            return h.gt(COUNT, 1L);
        });
        assertThat(recorded.filters()).hasSize(1);
        FilterGroupTest.assertMq1303(() -> leaked.get(0).gt(SUM, 3L), "only valid inside its having(...) operator");
        FilterGroupTest.assertMq1303(() -> HavingGroup.<Summary>collect(h -> h.or(List.of(a -> h.gt(SUM, 1L)))),
                "nested");
    }

    @Test
    void ac_agg_05_having_notes_every_aggregate_it_names_for_the_mq1103_check() {
        HavingGroup.Clause recorded = HavingGroup.<Summary>collect(h -> h
                .gt(COUNT, 1L)
                .or(a -> a.lt(SUM, Optional.empty()), b -> b.like(FIRST_STATUS, "P", LikeMode.STARTS_WITH)));
        assertThat(recorded.aggregates()).containsExactly(COUNT, SUM, FIRST_STATUS);
    }
}
