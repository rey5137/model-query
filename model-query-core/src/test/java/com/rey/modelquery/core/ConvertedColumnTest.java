package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Tuple;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A column carrying a {@link ColumnConverter}, before any Criteria query exists (spec api/10 R-COL-14, D-37). */
class ConvertedColumnTest {

    static final class Order {}

    static final class Item {}

    static final class OrderView {}

    static final class ItemView {}

    enum Status { NEW, PAID }

    /** Two attribute values map to one model value, so it is not a bijection. */
    static final class StatusConverter implements ColumnConverter<Status, String> {
        @Override
        public Status toModel(String attribute) {
            return Status.valueOf(attribute.toUpperCase());
        }

        @Override
        public String toAttribute(Status model) {
            return model.name();
        }
    }

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

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Item, Item> ITEM_ROOT = TableField.root(Item.class);
    private static final TableField<Item, Order> ITEM_ORDER =
            TableField.join(ITEM_ROOT, "order", jakarta.persistence.criteria.JoinType.LEFT);

    private static final ColumnField<OrderView, Order, Status> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", Status.class, String.class, new StatusConverter());
    private static final ColumnField<OrderView, Order, String> STATUS_TEXT =
            ColumnField.of(OrderView.class, ROOT, "status", String.class);
    private static final ColumnField<OrderView, Order, Long> CENTS =
            ColumnField.of(OrderView.class, ROOT, "total", Long.class, int.class, new CentsConverter());

    @Test
    void ac_col_11_row_get_converts_what_was_read_and_row_raw_does_not() {
        RowSelection selection = RowSelection.of(List.of(STATUS, CENTS));
        Row row = selection.row(tuple(Map.of("c0", "paid", "c1", 7)));

        assertThat(row.get(STATUS)).isEqualTo(Status.PAID);
        assertThat(row.raw(STATUS)).isEqualTo("paid");
        assertThat(row.get(CENTS)).isEqualTo(700L);
        assertThat(row.raw(CENTS)).isEqualTo(7);
        assertThat(STATUS.type()).isEqualTo(Status.class);
        assertThat(CENTS.type()).isEqualTo(Long.class);
    }

    @Test
    void ac_col_11_a_null_value_is_never_given_to_the_converter() {
        Row row = RowSelection.of(List.of(STATUS)).row(tuple(new HashMap<>()));

        assertThat(row.get(STATUS)).isNull();
        assertThat(row.raw(STATUS)).isNull();
        // Not selected: no value is read, so none is converted.
        assertThat(row.get(CENTS)).isNull();
        assertThat(row.raw(CENTS)).isNull();
    }

    @Test
    void ac_col_11_row_raw_of_an_unconverted_column_is_its_value() {
        Row row = RowSelection.of(List.of(STATUS_TEXT)).row(tuple(Map.of("c0", "NEW")));

        assertThat(row.raw(STATUS_TEXT)).isEqualTo(row.get(STATUS_TEXT)).isEqualTo("NEW");
    }

    @Test
    void ac_col_11_with_table_keeps_the_converter_and_a_scoped_row_converts() {
        ColumnField<ItemView, Order, Status> nested = STATUS.withTable(ItemView.class, ITEM_ORDER);
        Row row = RowSelection.of(List.of(nested)).row(tuple(Map.of("c0", "new")));

        assertThat(row.get(nested)).isEqualTo(Status.NEW);
        assertThat(row.scoped(ITEM_ORDER).get(STATUS)).isEqualTo(Status.NEW);
        assertThat(row.scoped(ITEM_ORDER).raw(STATUS)).isEqualTo("new");
        // The same attribute without the converter is another column, so the scope does not find it.
        assertThat(row.scoped(ITEM_ORDER).isSelected(
                ColumnField.of(OrderView.class, ROOT, "status", Status.class))).isFalse();
    }

    @Test
    void ac_col_11_columns_are_equal_only_when_their_converters_are_of_the_same_class() {
        var same = ColumnField.of(OrderView.class, ROOT, "status", Status.class, String.class, new StatusConverter());
        var other = ColumnField.of(OrderView.class, ROOT, "status", Status.class, String.class,
                new ColumnConverter<Status, String>() {
                    @Override
                    public Status toModel(String attribute) {
                        return Status.NEW;
                    }

                    @Override
                    public String toAttribute(Status model) {
                        return "NEW";
                    }
                });
        var unconverted = ColumnField.of(OrderView.class, ROOT, "status", Status.class);

        assertThat(same).isEqualTo(STATUS).hasSameHashCodeAs(STATUS);
        assertThat(other).isNotEqualTo(STATUS);
        assertThat(unconverted).isNotEqualTo(STATUS);
    }

    @Test
    void ac_col_11_an_aggregate_function_over_a_converted_column_throws_mq1408() {
        // min, max and countDistinct over a converter that is not ordered do not compile (OrderedColumnFieldTest).
        assertMq1408(() -> Agg.sum(CENTS));
        assertMq1408(() -> Agg.sumAsLong(CENTS));
        assertMq1408(() -> Agg.avg(CENTS));
        // Agg.of aggregates the attribute itself, so it takes the column's path.
        assertThat(Agg.<OrderView, String>of("minStatus", String.class,
                (ctx, cb) -> cb.least(STATUS_TEXT.path(ctx)))).isNotNull();
    }

    static void assertMq1408(org.assertj.core.api.ThrowableAssert.ThrowingCallable factory) {
        assertThatThrownBy(factory)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1408))
                .hasMessageStartingWith("MQ1408: OrderView.")
                .hasMessageEndingWith(": an aggregate function does not take a column that has a ColumnConverter, "
                        + "since the database computes over attribute values; "
                        + "aggregate the attribute with Agg.of");
    }

    /** A tuple that answers {@code get(alias)} only, which is all a {@link Row} reads. */
    private static Tuple tuple(Map<String, Object> values) {
        return (Tuple) Proxy.newProxyInstance(ConvertedColumnTest.class.getClassLoader(), new Class<?>[] {Tuple.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("get") && args.length == 1 && args[0] instanceof String alias) {
                        return values.get(alias);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A model String read from an Integer attribute: neither a String nor the same attribute as STATUS_TEXT. */
    static final class CodeConverter implements ColumnConverter<String, Integer> {
        @Override
        public String toModel(Integer attribute) {
            return attribute.toString();
        }

        @Override
        public Integer toAttribute(String model) {
            return Integer.valueOf(model);
        }
    }

    private static final ColumnField<OrderView, Order, String> CODE =
            ColumnField.of(OrderView.class, ROOT, "code", String.class, Integer.class, new CodeConverter());

    @Test
    void ac_col_11_a_string_operator_on_a_converted_column_of_another_attribute_type_throws_mq1001() {
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.like(CODE, "1", LikeMode.CONTAINS)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1001").hasMessageContaining("like(...)")
                .hasMessageContaining("CodeConverter").hasMessageContaining("Integer");
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.eqIgnoreCase(CODE, "1")))
                .hasMessageContaining("MQ1001");
    }

    @Test
    void ac_col_11_comparing_a_converted_column_with_one_of_another_attribute_type_throws_mq1001() {
        assertThatThrownBy(() -> FilterGroup.<OrderView>collect(f -> f.compare(CODE, Op.EQ, STATUS_TEXT)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1001").hasMessageContaining("Integer").hasMessageContaining("String");
    }
}
