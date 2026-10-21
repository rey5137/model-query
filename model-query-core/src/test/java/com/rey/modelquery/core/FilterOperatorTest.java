package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Condition.Kind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A {@link FilterOperator} and {@code op(...)} before any Criteria query exists: the factories, the definition-time
 * refusals and what a query records (spec api/12 R-FLT-20, R-FLT-21, api/16 R-INS-13, D-168).
 */
class FilterOperatorTest {

    static final class Order {}

    static final class OrderView {}

    /** Stores the model's {@code Long} cents as {@code Integer} whole units, so its attribute is an Integer. */
    static final class CentsConverter implements OrderedColumnConverter<Long, Integer> {
        @Override
        public Long toModel(Integer attribute) {
            return attribute * 100L;
        }

        @Override
        public Integer toAttribute(Long model) {
            return (int) (model / 100);
        }
    }

    /** Stores the model's {@code Long} as its text, so its attribute is a String. */
    static final class LongTextConverter implements ColumnConverter<Long, String> {
        @Override
        public Long toModel(String attribute) {
            return Long.valueOf(attribute);
        }

        @Override
        public String toAttribute(Long model) {
            return model.toString();
        }
    }

    /** Stores the model's {@code String} with a {@code SKU-} prefix, so its attribute is a String too. */
    static final class SkuConverter implements OrderedColumnConverter<String, String> {
        @Override
        public String toModel(String attribute) {
            return attribute.substring("SKU-".length());
        }

        @Override
        public String toAttribute(String model) {
            return "SKU-" + model;
        }
    }

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderView, Order, String> NAME =
            ColumnField.of(OrderView.class, ROOT, "name", String.class);
    private static final ColumnField<OrderView, Order, String> CODE =
            ColumnField.of(OrderView.class, ROOT, "code", String.class);
    private static final OrderedColumnField<OrderView, Order, Long> CENTS =
            ColumnField.of(OrderView.class, ROOT, "total", Long.class, int.class, new CentsConverter());
    private static final ColumnField<OrderView, Order, Long> REFERENCE =
            ColumnField.of(OrderView.class, ROOT, "reference", Long.class, String.class, new LongTextConverter());
    private static final OrderedColumnField<OrderView, Order, String> SKU =
            ColumnField.of(OrderView.class, ROOT, "code", String.class, String.class, new SkuConverter());
    private static final AggregateField<OrderView, Long> COUNT = Agg.count(ROOT);

    private static final FilterOperator<String, String> MATCHES =
            FilterOperator.of("matches", (ctx, left, right) -> ctx.cb().equal(left, right));
    private static final FilterOperator<Long, Long> AT_MOST =
            FilterOperator.of("atMost", (ctx, left, right) -> ctx.cb().le(left, right));
    private static final FilterOperator<Object, String> TEXT_OF =
            FilterOperator.of("textOf", String.class, (ctx, left, right) -> ctx.cb().conjunction());

    private static ModelQuery<Order, Long, OrderView> where(UnaryOperator<Filters<OrderView>> filters) {
        return ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(ID, NAME))
                .primaryKey(PrimaryKey.of(ID))
                .where(filters)
                .build();
    }

    private static List<Condition> conditions(UnaryOperator<Filters<OrderView>> filters) {
        return where(filters).conditions().where();
    }

    private static List<Condition> having(AggregateField<OrderView, ?> aggregate,
            UnaryOperator<Having<OrderView>> having) {
        return ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(NAME, aggregate))
                .groupBy(NAME)
                .having(having)
                .build()
                .conditions()
                .having();
    }

    private static void assertCode(Runnable call, MqCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---- the factories

    @Test
    void ac_flt_22_a_null_or_blank_name_is_mq1301() {
        assertCode(() -> FilterOperator.of(null, (ctx, l, r) -> ctx.cb().conjunction()), MqCode.MQ1301);
        assertCode(() -> FilterOperator.of(" ", String.class, (ctx, l, r) -> ctx.cb().conjunction()), MqCode.MQ1301);
    }

    @Test
    void ac_flt_25_each_modifier_returns_a_new_operator_and_keeps_the_name() {
        FilterOperator<String, String> required = MATCHES.requiringAttribute(String.class);
        FilterOperator<String, String> gated = MATCHES.requires(OperatorFeature.ILIKE);
        FilterOperator<String, String> prepared = MATCHES.preparing(String::trim);

        assertThat(List.of(required, gated, prepared)).doesNotContain(MATCHES).doesNotHaveDuplicates();
        assertThat(List.of(required, gated, prepared)).extracting(FilterOperator::name).containsOnly("matches");
        assertThat(MATCHES.attributeType()).isNull();
        assertThat(MATCHES.feature()).isNull();
        assertThat(MATCHES.prepare()).isNull();
        assertThat(gated.feature()).isEqualTo(OperatorFeature.ILIKE);
        // A later call replaces the setting rather than adding to it (D-168).
        assertThat(gated.requires(OperatorFeature.REGEX_MATCH).feature()).isEqualTo(OperatorFeature.REGEX_MATCH);
        assertThat(FilterOperator.of("p", int.class, (ctx, l, r) -> ctx.cb().conjunction()).valueType())
                .isEqualTo(Integer.class);
        assertThat(MATCHES).hasToString("matches");
    }

    // ---- what a query records

    @Test
    void ac_flt_25_records_operator_with_the_operator_column_and_unprepared_value() {
        FilterOperator<String, String> trimmed = MATCHES.preparing(String::trim);
        Condition condition = conditions(f -> f.op(trimmed, NAME, "  acme ")).get(0);

        assertThat(condition.kind()).isEqualTo(Kind.OPERATOR);
        assertThat(condition.column()).contains(NAME);
        assertThat(condition.operator()).containsSame(trimmed);
        assertThat(condition.values()).containsExactly("  acme ");
        assertThat(condition.right()).isEmpty();
        assertThat(conditions(f -> f.eq(NAME, "a")).get(0).operator()).isEmpty();
    }

    @Test
    void ac_flt_25_the_field_form_records_the_right_operand_and_no_value() {
        Condition condition = conditions(f -> f.op(MATCHES, NAME, CODE)).get(0);

        assertThat(condition.kind()).isEqualTo(Kind.OPERATOR);
        assertThat(condition.column()).contains(NAME);
        assertThat(condition.right()).contains(CODE);
        assertThat(condition.values()).isEmpty();
        assertThat(condition).hasToString("OPERATOR(OrderView.name, matches, OrderView.code)");
    }

    @Test
    void ac_flt_25_queries_sharing_the_constant_are_equal_and_another_operator_of_that_name_is_not() {
        FilterOperator<String, String> twin =
                FilterOperator.of("matches", (ctx, left, right) -> ctx.cb().equal(left, right));

        assertThat(where(f -> f.op(MATCHES, NAME, "a")).conditions())
                .isEqualTo(where(f -> f.op(MATCHES, NAME, "a")).conditions())
                .hasSameHashCodeAs(where(f -> f.op(MATCHES, NAME, "a")).conditions())
                .isNotEqualTo(where(f -> f.op(twin, NAME, "a")).conditions())
                .isNotEqualTo(where(f -> f.op(MATCHES, NAME, "b")).conditions());
        // An operator built at the call site is a new instance per call, so the queries differ.
        assertThat(where(f -> f.op(MATCHES.preparing(String::trim), NAME, "a")).conditions())
                .isNotEqualTo(where(f -> f.op(MATCHES.preparing(String::trim), NAME, "a")).conditions());
    }

    @Test
    void ac_flt_25_an_array_value_compares_by_content() {
        FilterOperator<String, String[]> anyOf =
                FilterOperator.of("anyOf", String[].class, (ctx, left, right) -> ctx.cb().conjunction());

        assertThat(where(f -> f.op(anyOf, NAME, new String[] {"a"})).conditions())
                .isEqualTo(where(f -> f.op(anyOf, NAME, new String[] {"a"})).conditions())
                .hasSameHashCodeAs(where(f -> f.op(anyOf, NAME, new String[] {"a"})).conditions())
                .isNotEqualTo(where(f -> f.op(anyOf, NAME, new String[] {"b"})).conditions());
    }

    @Test
    void ac_flt_25_to_string_prints_the_operator_name_and_no_value() {
        String text = conditions(f -> f.op(MATCHES, NAME, "secret-value")).get(0).toString();

        assertThat(text).isEqualTo("OPERATOR(OrderView.name, matches, ?)").doesNotContain("secret-value");
    }

    @Test
    void ac_flt_21_an_empty_optional_records_nothing() {
        assertThat(conditions(f -> f.op(MATCHES, NAME, Optional.empty()))).isEmpty();
        assertThat(conditions(f -> f.op(MATCHES, NAME, Optional.of("a"))))
                .isEqualTo(conditions(f -> f.op(MATCHES, NAME, "a")));
    }

    @Test
    void ac_flt_26_having_op_records_operator_over_the_aggregate() {
        FilterOperator<Long, Long> atLeast = FilterOperator.of("atLeast", (ctx, l, r) -> ctx.cb().ge(l, r));
        var query = ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(NAME, COUNT))
                .groupBy(NAME)
                .having(h -> h.op(atLeast, COUNT, 2L).op(atLeast, COUNT, Optional.empty()).op(atLeast, COUNT, COUNT))
                .build();

        List<Condition> having = query.conditions().having();
        assertThat(having).extracting(Condition::kind).containsExactly(Kind.OPERATOR, Kind.OPERATOR);
        assertThat(having.get(0).values()).containsExactly(2L);
        assertThat(having.get(1).right()).contains(COUNT);
    }

    // ---- refusals when the filter is added

    @Test
    void ac_flt_26_an_op_in_a_case_condition_is_mq1505_naming_the_operator_in_value_and_field_form() {
        assertThatThrownBy(() -> Expr.cases(OrderView.class, Long.class).when(f -> f.op(MATCHES, NAME, "a"), 1L))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1505))
                .hasMessageContaining("op(matches)");
        assertCode(() -> Expr.cases(OrderView.class, Long.class).when(f -> f.op(MATCHES, NAME, CODE), 1L),
                MqCode.MQ1505);
        // Nested under or and not too: the check walks the whole condition.
        assertCode(() -> Expr.cases(OrderView.class, Long.class)
                .when(f -> f.eq(ID, 1L).or(g -> g.not(h -> h.op(MATCHES, NAME, "a")), g -> g.eq(ID, 2L)), 1L),
                MqCode.MQ1505);
    }

    @Test
    void ac_flt_22_a_null_through_a_typed_variable_is_mq1301_naming_the_column() {
        String none = null;

        assertThatThrownBy(() -> where(f -> f.op(MATCHES, NAME, none)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1301))
                .hasMessageContaining("OrderView.name");
    }

    @Test
    void ac_flt_22_a_collection_an_optional_or_a_map_value_is_mq1502_naming_the_operator_and_column() {
        FilterOperator<Object, Object> any = FilterOperator.of("any", Object.class, (ctx, l, r) -> ctx.cb().and());
        ScalarField<OrderView, Object> name = cast(NAME);

        for (Object value : List.of(List.of("a"), Optional.of("a"), Map.of("a", 1))) {
            assertThatThrownBy(() -> where(f -> f.op(any, name, value)))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("OrderView.name").hasMessageContaining("op(any)");
        }
    }

    @Test
    void ac_flt_22_requiring_attribute_over_the_wrong_attribute_is_mq1001() {
        FilterOperator<Long, Long> textual = AT_MOST.requiringAttribute(String.class);

        // A converted column's attribute is the converter's: Integer for CENTS, String for REFERENCE (R-COL-14).
        assertThatThrownBy(() -> where(f -> f.op(textual, CENTS, 1L)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1001))
                .hasMessageContaining("op(atMost)").hasMessageContaining("CentsConverter");
        assertCode(() -> where(f -> f.op(textual, ID, 1L)), MqCode.MQ1001);
        assertCode(() -> where(f -> f.op(textual, ID, ID)), MqCode.MQ1001);
        assertThat(conditions(f -> f.op(textual, REFERENCE, 1L))).hasSize(1);
        assertThat(conditions(f -> f.op(AT_MOST.requiringAttribute(Number.class), CENTS, 1L))).hasSize(1);
    }

    @Test
    void ac_flt_22_a_field_form_over_two_converted_columns_of_different_attribute_types_is_mq1001() {
        assertCode(() -> where(f -> f.op(AT_MOST, CENTS, REFERENCE)), MqCode.MQ1001);
        assertCode(() -> where(f -> f.op(AT_MOST, CENTS, ID)), MqCode.MQ1001);
        assertThat(conditions(f -> f.op(AT_MOST, CENTS, CENTS))).hasSize(1);
        // The <C, V> form's sides differ in type by design, so it is not compare's check (D-168).
        assertThat(conditions(f -> f.op(TEXT_OF, cast(CENTS), NAME))).hasSize(1);
    }

    @Test
    void ac_flt_23_a_prepare_returning_null_or_throwing_is_mq1313_and_an_engine_error_keeps_its_code() {
        FilterOperator<String, String> nothing = MATCHES.preparing(value -> null);
        FilterOperator<String, String> failing = MATCHES.preparing(value -> {
            throw new IllegalStateException("bad pattern");
        });
        FilterOperator<String, String> engine = MATCHES.preparing(value -> {
            throw new ModelQueryDefinitionException(MqCode.MQ1308, "engine");
        });

        assertThatThrownBy(() -> where(f -> f.op(nothing, NAME, "a")))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1313))
                .hasMessageContaining("OrderView.name").hasMessageContaining("op(matches)");
        assertThatThrownBy(() -> where(f -> f.op(failing, NAME, "a")))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1313))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertCode(() -> where(f -> f.op(engine, NAME, "a")), MqCode.MQ1308);
    }

    @Test
    void ac_flt_25_prepare_runs_once_when_the_filter_is_added_and_the_value_is_recorded_unprepared() {
        var seen = new java.util.ArrayList<Long>();
        FilterOperator<Long, Long> doubled = AT_MOST.preparing(value -> {
            seen.add(value);
            return value * 2;
        });

        Condition condition = conditions(f -> f.op(doubled, ID, 150L)).get(0);

        assertThat(seen).containsExactly(150L);
        assertThat(condition.values()).containsExactly(150L);
    }

    @Test
    void ac_flt_21_prepare_gets_a_converted_columns_attribute_value_as_like_builds_its_pattern() {
        var seen = new java.util.ArrayList<String>();
        FilterOperator<String, String> wrapped = MATCHES.preparing(value -> {
            seen.add(value);
            return "%" + value + "%";
        });

        Condition condition = conditions(f -> f.op(wrapped, SKU, "12")).get(0);

        // like converts first too: CONTAINS "12" is %SKU-12%, never SKU-%12% (R-FLT-06, R-FLT-21, D-171).
        assertThat(seen).containsExactly("SKU-12");
        assertThat(condition.values()).containsExactly("12");
        assertThat(conditions(f -> f.op(wrapped, SKU, Optional.of("12")))).hasSize(1);
        // Over max(SKU) in having too: the aggregate's column converts first.
        seen.clear();
        assertThat(having(Agg.max(SKU), h -> h.op(wrapped, Agg.max(SKU), "12")).get(0).values())
                .containsExactly("12");
        assertThat(seen).containsExactly("SKU-12");
    }

    @Test
    void ac_flt_21_a_preparing_operator_on_a_converted_column_of_another_attribute_type_is_mq1001() {
        FilterOperator<Long, Long> doubled = AT_MOST.preparing(value -> value * 2);

        // prepare is a Long -> Long, and CENTS' attribute is an Integer: refused, never a ClassCastException.
        assertThatThrownBy(() -> where(f -> f.op(doubled, CENTS, 150L)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1001))
                .hasMessageContaining("op(atMost)").hasMessageContaining("CentsConverter")
                .hasMessageContaining("Integer");
        assertCode(() -> where(f -> f.op(doubled, REFERENCE, Optional.of(150L))), MqCode.MQ1001);
        assertCode(() -> having(Agg.max(CENTS), h -> h.op(doubled, Agg.max(CENTS), 150L)), MqCode.MQ1001);
        // With no prepare, or in the field form, nothing is prepared; the <C, V> form is never converted.
        assertThat(conditions(f -> f.op(AT_MOST, CENTS, 150L))).hasSize(1);
        assertThat(conditions(f -> f.op(doubled, CENTS, CENTS))).hasSize(1);
        assertThat(conditions(f -> f.op(TEXT_OF.preparing(String::trim), cast(CENTS), " 1 "))).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static <C> ScalarField<OrderView, C> cast(ScalarField<OrderView, ?> field) {
        return (ScalarField<OrderView, C>) field;
    }
}
