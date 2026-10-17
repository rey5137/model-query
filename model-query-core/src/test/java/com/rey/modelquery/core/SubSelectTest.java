package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Condition.Kind;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.PluralAttribute;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Sub-selects and lifted outer columns, before any Criteria query exists: their definition, equality and recording
 * (spec api/12 R-FLT-15..17, api/16 R-INS-08, D-112).
 */
class SubSelectTest {

    static final class Order {}

    static final class Item {}

    static final class Refund {}

    static final class OrderView {}

    static final class RefundView {}

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Item> ITEMS = TableField.join(ROOT, "items", JoinType.INNER);
    private static final TableField<Refund, Refund> REFUND_ROOT = TableField.root(Refund.class);
    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, Order, String> NAME =
            ColumnField.of(OrderView.class, ROOT, "name", String.class);
    /** Cardinality is checked at resolution, so this joined column can be lifted at definition (R-FLT-27). */
    private static final ColumnField<OrderView, Item, String> SKU =
            ColumnField.of(OrderView.class, ITEMS, "sku", String.class);
    private static final ColumnField<RefundView, Refund, Long> REFUND_ORDER =
            ColumnField.of(RefundView.class, REFUND_ROOT, "orderId", Long.class);
    private static final ColumnField<RefundView, Refund, String> REFUND_STATUS =
            ColumnField.of(RefundView.class, REFUND_ROOT, "status", String.class);
    /** On the Order root, but the RefundView model: a filter column the sub-select's Refund root does not cover. */
    private static final ColumnField<RefundView, Order, Long> REFUND_ON_ORDER =
            ColumnField.of(RefundView.class, ROOT, "orderId", Long.class);
    /** The {@code OrderView} model on the Refund root: the same model type, another root (R-FLT-17). */
    private static final ColumnField<OrderView, Refund, Long> ORDER_VIEW_ON_REFUND =
            ColumnField.of(OrderView.class, REFUND_ROOT, "orderId", Long.class);
    /** A model Long read from an Integer attribute, so a converter hides the attribute type on one side (R-FLT-15). */
    private static final ColumnField<OrderView, Order, Long> ID_CENTS =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class, Integer.class, new CentsConverter());

    /** A model {@code Long} cents value over an {@code Integer} attribute. */
    static final class CentsConverter implements ColumnConverter<Long, Integer> {
        @Override
        public Long toModel(Integer attribute) {
            return attribute * 100L;
        }

        @Override
        public Integer toAttribute(Long model) {
            return (int) (model / 100);
        }
    }

    private static SubSelect<RefundView, Long> refunded() {
        return SubSelect.of(REFUND_ORDER);
    }

    @Test
    void ac_flt_15_a_sub_select_keeps_its_column_root_and_recorded_conditions() {
        SubSelect<RefundView, Long> sub = refunded().where(f -> f.eq(REFUND_STATUS, "DONE").isNotNull(REFUND_ORDER));

        assertThat(sub.column()).isEqualTo(REFUND_ORDER);
        assertThat(sub.root()).isEqualTo(REFUND_ROOT);
        assertThat(sub.conditions()).extracting(Condition::kind).containsExactly(Kind.EQ, Kind.IS_NOT_NULL);
        assertThat(sub.conditions().get(0).values()).containsExactly("DONE");
    }

    @Test
    void ac_flt_15_a_sub_select_filter_column_off_its_root_throws_mq1003() {
        assertThatThrownBy(() -> refunded().where(f -> f.eq(REFUND_ON_ORDER, 1L)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1003");
    }

    @Test
    void ac_flt_15_a_converter_hiding_different_attribute_types_in_a_sub_select_throws_mq1001() {
        SubSelect<RefundView, Long> sub = refunded();
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.in(ID_CENTS, sub)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1001");
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.notIn(ID_CENTS, sub)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1001");
        // The same call without a converter on either side is a plain sub-select: no attribute-type check applies.
        assertThat(recorded(f -> f.in(ID, sub))).extracting(Condition::kind)
                .containsExactly(Kind.IN_SUBSELECT);
    }

    @Test
    void ac_flt_15_a_sub_select_is_never_skipped_when_all_its_own_filters_skip() {
        Optional<String> none = Optional.empty();
        SubSelect<RefundView, Long> sub = SubSelect.of(REFUND_ORDER).where(f -> f.eq(REFUND_STATUS, none));

        assertThat(sub.conditions()).isEmpty();
        assertThat(recorded(f -> f.in(ID, sub))).extracting(Condition::kind).containsExactly(Kind.IN_SUBSELECT);
    }

    @Test
    void ac_ins_08_a_sub_select_equals_one_built_from_equal_calls() {
        assertThat(refunded().where(f -> f.eq(REFUND_STATUS, "DONE")))
                .isEqualTo(refunded().where(f -> f.eq(REFUND_STATUS, "DONE")))
                .isNotEqualTo(refunded().where(f -> f.eq(REFUND_STATUS, "OPEN")))
                .isNotEqualTo(refunded().where(f -> f.isNotNull(REFUND_STATUS)));
    }

    @Test
    void ac_ins_08_outer_referenced_returns_the_outer_column_and_is_empty_for_a_plain_column() {
        ColumnField<RefundView, Order, String> lifted =
                new Outer<OrderView, RefundView>(Order.class).column(NAME);

        assertThat(Outer.referenced(NAME)).isEmpty();
        assertThat(Outer.referenced(lifted)).contains(NAME);
    }

    @Test
    void ac_ins_08_a_lifted_column_equals_another_lift_and_prints_outer_marker() {
        Outer<OrderView, RefundView> outer = new Outer<>(Order.class);

        assertThat(outer.column(NAME)).isEqualTo(outer.column(NAME));
        assertThat(outer.column(NAME).toString()).isEqualTo("outer.OrderView.name");
        assertThat(SKU.toString()).isEqualTo("OrderView.sku"); // a plain column is not marked
    }

    @Test
    void ac_ins_08_a_lifted_column_never_equals_the_plain_column_it_lifts() {
        ColumnField<RefundView, Order, Long> lifted = new Outer<OrderView, RefundView>(Order.class).column(ID);

        assertThat(lifted).isNotEqualTo(ID);
        assertThat(ID).isNotEqualTo(lifted);
    }

    @Test
    void ac_ins_08_in_and_not_in_over_a_sub_select_record_their_column_and_sub_select() {
        SubSelect<RefundView, Long> sub = refunded();
        List<Condition> where = recorded(f -> f.in(ID, sub).notIn(ID, sub));

        assertThat(where).extracting(Condition::kind)
                .containsExactly(Kind.IN_SUBSELECT, Kind.NOT_IN_SUBSELECT);
        assertThat(where.get(0).column()).contains(ID);
        assertThat(where.get(0).subSelect()).contains(sub);
        assertThat(where.get(0).values()).isEmpty();
        assertThat(where.get(0).children()).isEmpty();
        assertThat(where.get(1).subSelect()).contains(sub);
    }

    @Test
    void ac_ins_08_exists_and_not_exists_over_a_sub_select_record_the_sub_select_and_correlation() {
        SubSelect<RefundView, Long> sub = refunded();
        List<Condition> where = recorded(f -> f
                .exists(sub, (s, outer) -> s.compare(REFUND_ORDER, Op.EQ, outer.column(ID)))
                .notExists(sub, (s, outer) -> s.isNull(outer.column(NAME))));

        assertThat(where).extracting(Condition::kind)
                .containsExactly(Kind.EXISTS_SUBSELECT, Kind.NOT_EXISTS_SUBSELECT);
        assertThat(where.get(0).subSelect()).contains(sub);
        assertThat(where.get(0).column()).isEmpty();
        assertThat(where.get(0).path()).isEmpty();
        assertThat(where.get(0).children()).extracting(Condition::kind).containsExactly(Kind.COMPARE);
        assertThat(where.get(1).subSelect()).contains(sub);
        assertThat(where.get(1).children()).extracting(Condition::kind).containsExactly(Kind.IS_NULL);
    }

    @Test
    void ac_flt_28_outer_column_accepts_a_joined_column_before_cardinality_is_known() {
        assertThat(Outer.referenced(new Outer<OrderView, RefundView>(Order.class).column(SKU))).contains(SKU);
    }

    @Test
    void ac_flt_17_outer_column_given_another_root_with_the_same_model_type_throws_mq1311() {
        // ORDER_VIEW_ON_REFUND is the same OrderView model type as the outer query's root, but another entity's root.
        assertThatThrownBy(() -> new Outer<OrderView, RefundView>(Order.class).column(ORDER_VIEW_ON_REFUND))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1311");
    }

    @Test
    void ac_flt_15_a_self_sub_select_whose_where_lifts_an_outer_column_throws_mq1003() {
        // The sub-select's root is the outer root, so the lifted column's table would sit on the scope: without the
        // explicit lifted check it would be accepted, though lift(...) is never legal in a sub-select's own where.
        SubSelect<OrderView, Long> self = SubSelect.of(ID);
        Outer<OrderView, OrderView> outer = new Outer<>(Order.class);

        assertThatThrownBy(() -> self.where(f -> f.compare(ID, Op.EQ, outer.column(ID))))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1003");
    }

    @Test
    void ac_flt_17_outer_column_given_a_lifted_column_throws_mq1310() {
        assertThatThrownBy(() -> new Outer<OrderView, RefundView>(Order.class).column(Outer.reference(NAME)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1310");
    }

    @Test
    void ac_flt_17_reference_given_a_lifted_column_throws_mq1310() {
        // A1: reference skips no refusal Outer.column makes (api/12 R-FLT-17, D-112).
        assertThatThrownBy(() -> Outer.reference(Outer.reference(NAME)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1310");
    }

    @Test
    void ac_flt_28_reference_accepts_a_joined_column_before_cardinality_is_known() {
        assertThat(Outer.referenced(Outer.reference(SKU))).contains(SKU);
    }

    @Test
    void ac_flt_28_a_joined_column_starting_at_another_root_is_still_mq1311() {
        TableField<Refund, Item> items = TableField.join(REFUND_ROOT, "items", JoinType.INNER);
        ColumnField<OrderView, Item, String> sku = ColumnField.of(OrderView.class, items, "sku", String.class);
        assertThatThrownBy(() -> new Outer<OrderView, RefundView>(Order.class).column(sku))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1311));
    }

    @Test
    void ac_flt_15_a_source_rooted_sub_select_is_mq1311_at_first_resolution() {
        TableField<RefundView, RefundView> source = TableField.source(RefundView.class);
        ColumnField<RefundView, RefundView, Long> id =
                ColumnField.over(RefundView.class, source, REFUND_ORDER.named("orderId"));
        SubSelect<RefundView, Long> sub = SubSelect.of(id);
        JoinContext ctx = JoinContext.rootless(null, RenderOptions.portable());

        for (boolean negated : List.of(false, true)) {
            assertThatThrownBy(() -> ctx.inSubSelect(ID, sub, negated))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1311))
                    .hasMessageContaining("the sub-select's root is a source");
            assertThatThrownBy(() -> ctx.existsSubSelect(sub, List.of(), negated))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1311))
                    .hasMessageContaining("the sub-select's root is a source");
        }
    }

    @Test
    void ac_flt_17_a_correlation_that_lifts_no_outer_column_throws_mq1309() {
        SubSelect<RefundView, Long> sub = refunded();
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(
                f -> f.exists(sub, (s, outer) -> s.eq(REFUND_STATUS, "DONE"))))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1309");
    }

    @Test
    void ac_flt_15_a_to_many_lift_is_mq1311_at_resolution() {
        var plural = criteria(PluralAttribute.class, (name, args) -> null);
        var items = criteria(Join.class, (name, args) -> switch (name) {
            case "getAttribute" -> plural;
            default -> throw new AssertionError(name);
        });
        var root = criteria(Root.class, (name, args) -> switch (name) {
            case "getJavaType" -> Order.class;
            case "join" -> items;
            default -> throw new AssertionError(name);
        });
        var sub = criteria(Subquery.class, (name, args) -> switch (name) {
            case "from", "select" -> null;
            default -> throw new AssertionError(name);
        });
        var query = criteria(CriteriaQuery.class, (name, args) -> switch (name) {
            case "subquery" -> sub;
            default -> throw new AssertionError(name);
        });
        var cb = criteria(CriteriaBuilder.class, (name, args) -> switch (name) {
            case "literal" -> null;
            default -> throw new AssertionError(name);
        });
        var ctx = JoinContext.of(root, cb, query, RenderOptions.portable());
        var rows = SubSelect.of(REFUND_ORDER);
        List<Filter> correlation = FilterGroup.<OrderView, RefundView>collectCorrelation(rows,
                (s, outer) -> s.isNull(outer.column(SKU)), Order.class);
        assertThatThrownBy(() -> ctx.existsSubSelect(rows, correlation, false))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1311))
                .hasMessageContaining("to-many");
    }

    @SuppressWarnings("unchecked")
    private static <T> T criteria(Class<T> type, BiFunction<String, Object[], Object> calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> calls.apply(method.getName(), args));
    }

    /** The conditions {@code operator} records on a {@code where} group. */
    private static List<Condition> recorded(UnaryOperator<Filters<OrderView>> operator) {
        return ConditionGroup.conditions(FilterGroup.collect(operator));
    }
}
