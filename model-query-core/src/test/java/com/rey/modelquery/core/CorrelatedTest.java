package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.Incubating;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The immutable definition of the correlated terminals, before a Criteria statement exists (R-COL-31, D-150). */
class CorrelatedTest {

    static final class Order {}

    static final class Refund {}

    static final class View {}

    static final class RefundView {}

    static final class AmountConverter implements ColumnConverter<Long, Integer> {
        @Override
        public Long toModel(Integer value) {
            return value.longValue();
        }

        @Override
        public Integer toAttribute(Long value) {
            return value.intValue();
        }
    }

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Refund, Refund> REFUNDS = TableField.root(Refund.class);
    private static final ColumnField<View, Order, Long> ID = ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final ColumnField<View, Order, String> STATUS =
            ColumnField.of(View.class, ROOT, "status", String.class);
    private static final ColumnField<RefundView, Refund, Long> ORDER_ID =
            ColumnField.of(RefundView.class, REFUNDS, "orderId", Long.class);
    private static final ColumnField<RefundView, Refund, Long> AMOUNT =
            ColumnField.of(RefundView.class, REFUNDS, "amount", Long.class);
    private static final ColumnField<RefundView, Refund, String> REASON =
            ColumnField.of(RefundView.class, REFUNDS, "reason", String.class);
    private static final SubSelect<RefundView, Long> ROWS = SubSelect.of(AMOUNT).where(f -> f.eq(REASON, "issued"));
    private static final BiFunction<Filters<RefundView>, Outer<View, RefundView>, Filters<RefundView>> MATCH =
            (f, outer) -> f.compare(ORDER_ID, Op.EQ, outer.column(ID));

    @Test
    void ac_col_36_count_is_a_long_expression_over_only_the_lifted_outer_columns() {
        ExpressionField<View, Long> count = Correlated.count(View.class, ROWS, MATCH);

        assertThat(count.type()).isEqualTo(Long.class);
        assertThat(count.columns()).containsExactly(ID);
        assertThat(count.binds()).isEqualTo(1);
        assertThat(count.named("refundCount").property()).contains("refundCount");
        assertThat(count.property()).isEmpty();
        assertThat(count.toString()).contains("COUNT", "outer.View.id").doesNotContain("issued");
        assertThatThrownBy(() -> count.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ac_col_36_equal_calls_share_identity_by_rows_correlation_and_terminal_ignoring_names() {
        ExpressionField<View, Long> count = Correlated.count(View.class, ROWS, MATCH);
        ExpressionField<View, Long> same = Correlated.count(View.class,
                SubSelect.of(AMOUNT).where(f -> f.eq(REASON, "issued")),
                (f, outer) -> f.compare(ORDER_ID, Op.EQ, outer.column(ID)));

        assertThat(count).isEqualTo(same.named("count")).hasSameHashCodeAs(same);
        assertThat(SelectSet.of(count, same.named("another")).fields()).hasSize(1);
        assertThat(count).isNotEqualTo(Correlated.sum(View.class, ROWS, MATCH));
        assertThat(count).isNotEqualTo(Correlated.count(View.class,
                ROWS.where(f -> f.eq(REASON, "different")), MATCH));
        assertThat(count).isNotEqualTo(Correlated.count(View.class, SubSelect.of(ORDER_ID), MATCH));
        assertThat(count).isNotEqualTo(Correlated.count(View.class, ROWS,
                (f, outer) -> f.compare(ORDER_ID, Op.NE, outer.column(ID))));
    }

    @Test
    void ac_col_36_custom_conditions_compare_by_recorded_identity_at_every_depth_of_both_where_trees() {
        for (boolean labelled : List.of(false, true)) {
            UnaryOperator<Filters<RefundView>> first = f -> labelled
                    ? f.add("custom", (ctx, cb) -> cb.conjunction()) : f.add((ctx, cb) -> cb.conjunction());
            UnaryOperator<Filters<RefundView>> second = f -> labelled
                    ? f.add("custom", (ctx, cb) -> cb.disjunction()) : f.add((ctx, cb) -> cb.disjunction());
            var firstPlacements = customPlacements(first);
            var secondPlacements = customPlacements(second);
            for (int i = 0; i < firstPlacements.size(); i++) {
                var a = firstPlacements.get(i);
                var b = secondPlacements.get(i);
                var firstRows = SubSelect.of(AMOUNT).where(a);
                var secondRows = SubSelect.of(AMOUNT).where(b);
                // SubSelect and Condition keep their existing equality outside CorrelatedValue.
                assertThat(firstRows).isEqualTo(secondRows).hasSameHashCodeAs(secondRows);
                assertThat(firstRows.conditions()).isEqualTo(secondRows.conditions());
                var count = Correlated.count(View.class, firstRows, MATCH);
                assertCustomValues(count, Correlated.count(View.class, secondRows, MATCH));
                assertCustomValues(count, Correlated.count(View.class, SubSelect.of(AMOUNT).where(a), MATCH));
                assertThat(count).isEqualTo(Correlated.count(View.class, firstRows, MATCH))
                        .hasSameHashCodeAs(Correlated.count(View.class, firstRows, MATCH));
                assertCustomValues(Correlated.count(View.class, ROWS,
                                (f, outer) -> a.apply(f.compare(ORDER_ID, Op.EQ, outer.column(ID)))),
                        Correlated.count(View.class, ROWS,
                                (f, outer) -> b.apply(f.compare(ORDER_ID, Op.EQ, outer.column(ID)))));
            }
        }
    }

    private static List<UnaryOperator<Filters<RefundView>>> customPlacements(
            UnaryOperator<Filters<RefundView>> custom) {
        return List.of(custom,
                f -> f.not(n -> n.or(custom, g -> g.eq(REASON, "other"))),
                f -> f.in(ORDER_ID, SubSelect.of(ORDER_ID).where(custom)),
                f -> f.exists(SubSelect.of(ORDER_ID).where(custom),
                        (g, outer) -> g.compare(ORDER_ID, Op.EQ, outer.column(ORDER_ID))),
                f -> f.exists(SubSelect.of(ORDER_ID),
                        (g, outer) -> custom.apply(g.compare(ORDER_ID, Op.EQ, outer.column(ORDER_ID)))));
    }

    private static void assertCustomValues(ExpressionField<View, Long> first, ExpressionField<View, Long> second) {
        assertThat(first).isNotEqualTo(second);
        assertThat(SelectSet.of(first, second).fields()).containsExactly(first, second);
        assertThat(first).isEqualTo(first.named("same")).hasSameHashCodeAs(first.named("same"));
    }

    @Test
    void ac_col_36_correlation_runs_once_and_its_filters_close_after_recording() throws Exception {
        var calls = new AtomicInteger();
        var captured = new AtomicReference<Filters<RefundView>>();
        ExpressionField<View, Long> count = Correlated.count(View.class, ROWS, (f, outer) -> {
            calls.incrementAndGet();
            captured.set(f);
            return f.compare(ORDER_ID, Op.EQ, outer.column(ID));
        });
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<ColumnField<View, ?, ?>>>> tasks =
                    java.util.stream.IntStream.range(0, 8).mapToObj(i ->
                            (Callable<List<ColumnField<View, ?, ?>>>) count::columns).toList();
            for (var result : pool.invokeAll(tasks)) {
                assertThat(result.get()).containsExactly(ID);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> captured.get().eq(AMOUNT, 1L))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1303));
    }

    @Test
    void ac_col_36_nested_sub_select_binds_count_but_its_lifted_columns_belong_to_the_inner_root() {
        ExpressionField<View, Long> count = Correlated.count(View.class, ROWS, (f, outer) -> f
                .compare(ORDER_ID, Op.EQ, outer.column(ID))
                .exists(SubSelect.of(ORDER_ID).where(g -> g.eq(REASON, "nested")),
                        (g, nested) -> g.compare(ORDER_ID, Op.EQ, nested.column(ORDER_ID)).eq(AMOUNT, 2L)));

        assertThat(count.columns()).containsExactly(ID);
        assertThat(count.binds()).isEqualTo(3);
    }

    @Test
    void ac_col_36_conditions_inside_or_and_expressions_keep_each_lifted_column_once() {
        ExpressionField<View, Long> count = Correlated.count(View.class, ROWS, (f, outer) -> f.or(
                g -> g.compare(ORDER_ID, Op.EQ, Expr.plus(outer.column(ID), 1L)),
                g -> g.compare(REASON, Op.EQ, outer.column(STATUS)).compare(ORDER_ID, Op.EQ, outer.column(ID))));

        assertThat(count.columns()).containsExactly(ID, STATUS);
        assertThat(count.binds()).isEqualTo(2);
    }

    @Test
    void ac_col_37_exists_is_a_boolean_expression_with_the_same_recorded_correlation() {
        ExpressionField<View, Boolean> exists = Correlated.exists(View.class, ROWS, MATCH);

        assertThat(exists.type()).isEqualTo(Boolean.class);
        assertThat(exists.columns()).containsExactly(ID);
        assertThat(exists.binds()).isEqualTo(1);
        assertThat(exists).isEqualTo(Correlated.exists(View.class, ROWS, MATCH));
        assertThat(exists).isNotEqualTo(Correlated.count(View.class, ROWS, MATCH));
    }

    @Test
    void ac_col_38_min_and_max_keep_the_comparable_column_type_and_differ_by_terminal() {
        ExpressionField<View, String> min = Correlated.min(View.class, SubSelect.of(REASON), MATCH);
        ExpressionField<View, String> max = Correlated.max(View.class, SubSelect.of(REASON), MATCH);

        assertThat(min.type()).isEqualTo(String.class);
        assertThat(max.type()).isEqualTo(String.class);
        assertThat(min).isEqualTo(Correlated.min(View.class, SubSelect.of(REASON), MATCH));
        assertThat(max).isEqualTo(Correlated.max(View.class, SubSelect.of(REASON), MATCH));
        assertThat(min).isNotEqualTo(max);
        assertThat(min.columns()).containsExactly(ID);
    }

    @Test
    void ac_col_39_sum_keeps_the_supported_number_type_and_composes_as_an_expression() {
        ColumnField<RefundView, Refund, BigDecimal> decimal =
                ColumnField.of(RefundView.class, REFUNDS, "decimal", BigDecimal.class);
        ColumnField<RefundView, Refund, Double> floating =
                ColumnField.of(RefundView.class, REFUNDS, "floating", Double.class);
        ExpressionField<View, Long> sum = Correlated.sum(View.class, ROWS, MATCH);

        assertThat(sum.type()).isEqualTo(Long.class);
        assertThat(Correlated.sum(View.class, SubSelect.of(decimal), MATCH).type()).isEqualTo(BigDecimal.class);
        assertThat(Correlated.sum(View.class, SubSelect.of(floating), MATCH).type()).isEqualTo(Double.class);
        assertThat(Expr.coalesce(sum, 0L).columns()).containsExactly(ID);
        assertThat(Expr.nullIf(sum, 0L).binds()).isEqualTo(2);
        assertThat(Expr.plus(sum, 1L).binds()).isEqualTo(2);
    }

    @Test
    void ac_col_39_sum_refuses_a_number_whose_database_sum_changes_type() {
        ColumnField<RefundView, Refund, Integer> integer =
                ColumnField.of(RefundView.class, REFUNDS, "integer", Integer.class);

        assertThatThrownBy(() -> Correlated.sum(View.class, SubSelect.of(integer), MATCH))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1403))
                .hasMessageContainingAll("Correlated.sum", "use a Long, BigDecimal or Double column")
                .hasMessageNotContaining("Agg.");
        assertThatThrownBy(() -> Agg.sum(integer))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1403))
                .hasMessageContainingAll("Agg.sum", "use Agg.sumAsLong");
        var decimal = ColumnField.of(RefundView.class, REFUNDS, "decimal", BigDecimal.class);
        assertThatThrownBy(() -> Agg.sumAsLong(decimal))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1403))
                .hasMessageContainingAll("Agg.sumAsLong", "use Agg.sum");
    }

    @Test
    void ac_col_38_and_39_value_terminals_keep_the_existing_converter_refusals() {
        ColumnField<RefundView, Refund, Long> converted = ColumnField.of(RefundView.class, REFUNDS, "amount",
                Long.class, Integer.class, new AmountConverter());
        SubSelect<RefundView, Long> rows = SubSelect.of(converted);

        assertThat(Correlated.count(View.class, rows, MATCH).type()).isEqualTo(Long.class);
        assertThat(Correlated.exists(View.class, rows, MATCH).type()).isEqualTo(Boolean.class);
        assertThatThrownBy(() -> Correlated.min(View.class, rows, MATCH))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1501));
        assertThatThrownBy(() -> Correlated.max(View.class, rows, MATCH))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1501));
        assertThatThrownBy(() -> Correlated.sum(View.class, rows, MATCH))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1408));
    }

    @Test
    void ac_col_36_a_correlation_with_no_lift_even_after_skipping_is_mq1309() {
        assertThatThrownBy(() -> Correlated.count(View.class, ROWS,
                (f, outer) -> f.eq(AMOUNT, Optional.empty())))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1309));
        assertThatThrownBy(() -> Correlated.count(View.class, ROWS, (f, outer) -> f.exists(SubSelect.of(ORDER_ID),
                (g, nested) -> g.compare(ORDER_ID, Op.EQ, nested.column(ORDER_ID)))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1309));
    }

    @Test
    void ac_col_36_a_correlation_reuses_inner_scope_and_outer_root_only_checks() {
        ColumnField<RefundView, Order, Long> wrongRoot = ColumnField.of(RefundView.class, ROOT, "id", Long.class);
        assertThatThrownBy(() -> Correlated.count(View.class, ROWS,
                (f, outer) -> f.eq(wrongRoot, 1L).compare(ORDER_ID, Op.EQ, outer.column(ID))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1003));
        TableField<Order, Refund> joined = TableField.join(ROOT, "refunds", INNER);
        ColumnField<View, Refund, Long> joinedId = ColumnField.of(View.class, joined, "id", Long.class);
        assertThatThrownBy(() -> Correlated.count(View.class, ROWS,
                (f, outer) -> f.compare(ORDER_ID, Op.EQ, outer.column(joinedId))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1311));
        assertThatThrownBy(() -> Correlated.count(View.class, ROWS,
                (f, outer) -> f.compare(ORDER_ID, Op.EQ, outer.column(Outer.reference(ID)))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1310));
    }

    @Test
    void ac_col_36_all_terminals_require_a_model_rows_and_correlation() {
        assertThatThrownBy(() -> Correlated.count(null, ROWS, MATCH))
                .isInstanceOf(NullPointerException.class).hasMessage("model");
        assertThatThrownBy(() -> Correlated.count(View.class, null, MATCH))
                .isInstanceOf(NullPointerException.class).hasMessage("rows");
        assertThatThrownBy(() -> Correlated.count(View.class, ROWS, null))
                .isInstanceOf(NullPointerException.class).hasMessage("correlation");
    }

    @Test
    void ac_col_36_the_public_type_and_every_terminal_are_incubating() throws IOException {
        // Incubating has CLASS retention, so reflection cannot read it.
        try (var input = new DataInputStream(Correlated.class.getResourceAsStream("Correlated.class"))) {
            var type = new ClassFile(input);
            var annotations = (AnnotationsAttribute) type.getAttribute(AnnotationsAttribute.invisibleTag);
            assertThat(annotations).isNotNull();
            assertThat(annotations.getAnnotation(Incubating.class.getName())).isNotNull();
            assertThat(type.getMethods().stream()
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getAccessFlags())))
                    .hasSize(5).allSatisfy(method -> {
                        var members = (AnnotationsAttribute) method.getAttribute(AnnotationsAttribute.invisibleTag);
                        assertThat(members).isNotNull();
                        assertThat(members.getAnnotation(Incubating.class.getName())).isNotNull();
                    });
        }
    }

    @Test
    void ac_col_38_and_39_terminal_bounds_reject_non_comparable_and_non_numeric_columns(@TempDir Path out)
            throws IOException {
        List<String> members = List.of(
                "static final class Entity {}",
                "static final class Model {}",
                "static final TableField<Entity, Entity> ROOT = TableField.root(Entity.class);",
                "static final ColumnField<Model, Entity, Object> VALUE =",
                "        ColumnField.of(Model.class, ROOT, \"value\", Object.class);",
                "static final ColumnField<Model, Entity, String> TEXT =",
                "        ColumnField.of(Model.class, ROOT, \"text\", String.class);");
        for (String terminal : List.of("min", "max")) {
            assertThat(CompileProbe.problems(out, members, "Correlated." + terminal
                    + "(Model.class, SubSelect.of(VALUE), (f, outer) -> f.eq(outer.column(VALUE), new Object()))"))
                    .isNotEmpty();
        }
        assertThat(CompileProbe.problems(out, members,
                "Correlated.sum(Model.class, SubSelect.of(TEXT), (f, outer) -> f.eq(outer.column(TEXT), \"x\"))"))
                .isNotEmpty();
    }
}
