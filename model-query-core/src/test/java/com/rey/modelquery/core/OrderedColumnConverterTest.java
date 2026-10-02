package com.rey.modelquery.core;

import static com.rey.modelquery.core.ConvertedColumnTest.ORDERED_HINT;
import static com.rey.modelquery.core.ConvertedColumnTest.assertMq1408;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Tuple;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link OrderedColumnConverter}, its built-ins and the aggregates that take them (api/13 R-AGG-04, D-84). */
class OrderedColumnConverterTest {

    static final class Order {}

    static final class OrderView {}

    /** Cents in the model, whole units in the entity: ordered, but a sum of it is not a converted sum. */
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

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<OrderView, Order, Instant> PLACED = ColumnField.of(
            OrderView.class, ROOT, "placedAt", Instant.class, Timestamp.class, InstantTimestampConverter.INSTANCE);
    private static final ColumnField<OrderView, Order, Date> PLACED_DATE = ColumnField.of(
            OrderView.class, ROOT, "placedAt", Date.class, Timestamp.class, DateTimestampConverter.INSTANCE);
    private static final ColumnField<OrderView, Order, Timestamp> PLACED_STAMP =
            ColumnField.of(OrderView.class, ROOT, "placedAt", Timestamp.class);
    private static final ColumnField<OrderView, Order, Long> CENTS =
            ColumnField.of(OrderView.class, ROOT, "total", Long.class, Integer.class, new CentsConverter());

    /** Three instants a microsecond and a nanosecond apart, in order, none on a whole millisecond. */
    private static final List<Instant> INSTANTS = List.of(
            Instant.parse("2024-03-31T00:59:59.123456789Z"),
            Instant.parse("2024-03-31T00:59:59.123457789Z"),
            Instant.parse("2024-03-31T00:59:59.123457790Z"));

    @Test
    void ac_col_13_the_instant_converter_round_trips_with_nanoseconds_and_keeps_order_both_ways() {
        var converter = InstantTimestampConverter.INSTANCE;
        for (Instant instant : INSTANTS) {
            Timestamp attribute = converter.toAttribute(instant);
            assertThat(attribute.getNanos()).isEqualTo(instant.getNano());
            assertThat(converter.toModel(attribute)).isEqualTo(instant);
        }
        for (int i = 0; i + 1 < INSTANTS.size(); i++) {
            Timestamp a = converter.toAttribute(INSTANTS.get(i));
            Timestamp b = converter.toAttribute(INSTANTS.get(i + 1));
            // compareTo, not before(): Date.before compares milliseconds only, and these share one.
            assertThat(a.compareTo(b)).isNegative();
            assertThat(converter.toModel(a)).isBefore(converter.toModel(b));
        }
        assertThat(converter).isInstanceOf(OrderedColumnConverter.class);
    }

    @Test
    void ac_col_13_the_date_converter_returns_the_timestamp_itself_so_no_sub_millisecond_digit_is_lost() {
        var converter = DateTimestampConverter.INSTANCE;
        Timestamp stored = Timestamp.from(INSTANTS.get(0));

        Date model = converter.toModel(stored);
        assertThat(model).isSameAs(stored);
        // A value read back binds exactly what was read, so an eq against it matches the stored row.
        assertThat(converter.toAttribute(model)).isSameAs(stored);
        assertThat(converter.toAttribute(model).getNanos()).isEqualTo(123_456_789);

        // A plain Date carries milliseconds only, and binds them.
        Date plain = new Date(stored.getTime());
        assertThat(converter.toAttribute(plain)).isEqualTo(new Timestamp(stored.getTime()));
        assertThat(converter.toAttribute(plain).getNanos()).isEqualTo(123_000_000);

        Timestamp later = Timestamp.from(INSTANTS.get(1));
        // Timestamp.compareTo(Date) compares nanoseconds when both are timestamps, as every model value read is.
        assertThat(converter.toModel(stored).compareTo(converter.toModel(later))).isNegative();
        assertThat(converter.toAttribute(plain).compareTo(converter.toAttribute(later))).isNegative();
        assertThat(converter).isInstanceOf(OrderedColumnConverter.class);
    }

    @Test
    void ac_agg_13_min_max_and_count_distinct_take_an_ordered_column_and_min_max_read_the_model_type() {
        AggregateField<OrderView, Instant> min = Agg.min(PLACED);
        AggregateField<OrderView, Instant> max = Agg.max(PLACED);
        AggregateField<OrderView, Long> distinct = Agg.countDistinct(PLACED);
        AggregateField<OrderView, Date> maxDate = Agg.max(PLACED_DATE);
        assertThat(min.type()).isEqualTo(Instant.class);
        assertThat(maxDate.type()).isEqualTo(Date.class);
        assertThat(distinct.type()).isEqualTo(Long.class);
        assertThat(min.name()).isEqualTo("min(placedAt)");

        Timestamp first = Timestamp.from(INSTANTS.get(0));
        Timestamp last = Timestamp.from(INSTANTS.get(2));
        Row row = RowSelection.of(List.of(min, max, distinct, maxDate))
                .row(tuple(Map.of("c0", first, "c1", last, "c2", 3L, "c3", last)));
        assertThat(row.get(min)).isEqualTo(INSTANTS.get(0));
        assertThat(row.get(max)).isEqualTo(INSTANTS.get(2));
        assertThat(row.get(distinct)).isEqualTo(3L);
        assertThat(row.get(maxDate)).isSameAs(last);
        assertThat(row.raw(min)).isSameAs(first);
    }

    @Test
    void ac_agg_13_a_min_over_one_attribute_through_different_converters_is_a_different_aggregate() {
        assertThat(Agg.min(PLACED)).isEqualTo(Agg.min(PLACED)).hasSameHashCodeAs(Agg.min(PLACED));
        assertThat(Agg.<OrderView, Date>max(PLACED_DATE)).isNotEqualTo(Agg.max(PLACED))
                .isNotEqualTo(Agg.max(PLACED_STAMP));
        assertThat(Agg.max(PLACED).as("latest")).isEqualTo(Agg.max(PLACED).as("latest"));
        // countDistinct is a Long whatever converts the column, so it stays one key.
        assertThat(Agg.countDistinct(PLACED)).isEqualTo(Agg.countDistinct(PLACED_DATE));
    }

    @Test
    void ac_col_14_columns_over_one_attribute_share_one_alias_and_each_converts_the_value_it_reads() {
        Timestamp stamp = Timestamp.from(INSTANTS.get(0));
        // The attribute is c0 for every column over it, so the next attribute is c1, not c2 or c3.
        Row row = RowSelection.of(List.of(PLACED, PLACED_STAMP, CENTS, PLACED_DATE, Agg.max(PLACED)))
                .row(tuple(Map.of("c0", stamp, "c1", 12, "c2", stamp)));
        assertThat(row.get(PLACED)).isEqualTo(INSTANTS.get(0));
        assertThat(row.get(PLACED_STAMP)).isSameAs(stamp);
        assertThat(row.get(PLACED_DATE)).isSameAs(stamp);
        assertThat(row.get(CENTS)).isEqualTo(1_200L);
        assertThat(row.get(Agg.max(PLACED))).isEqualTo(INSTANTS.get(0));
        assertThat(row.raw(PLACED_DATE)).isSameAs(row.raw(PLACED));
    }

    @Test
    void ac_agg_13_sum_and_avg_over_an_ordered_column_and_any_function_over_an_unordered_one_throw_mq1408() {
        assertThat(Agg.min(CENTS).type()).isEqualTo(Long.class);
        assertMq1408(() -> Agg.sum(CENTS), "");
        assertMq1408(() -> Agg.sumAsLong(CENTS), "");
        assertMq1408(() -> Agg.avg(CENTS), "");

        var unordered = ColumnField.of(OrderView.class, ROOT, "placedAt", Instant.class, Timestamp.class,
                new ColumnConverter<Instant, Timestamp>() {
                    @Override
                    public Instant toModel(Timestamp attribute) {
                        return attribute.toInstant();
                    }

                    @Override
                    public Timestamp toAttribute(Instant model) {
                        return Timestamp.from(model);
                    }
                });
        assertMq1408(() -> Agg.min(unordered), ORDERED_HINT);
        assertMq1408(() -> Agg.max(unordered), ORDERED_HINT);
        assertMq1408(() -> Agg.countDistinct(unordered), ORDERED_HINT);
    }

    /** A tuple that answers {@code get(alias)} only, which is all a {@link Row} reads. */
    private static Tuple tuple(Map<String, Object> values) {
        return (Tuple) Proxy.newProxyInstance(OrderedColumnConverterTest.class.getClassLoader(),
                new Class<?>[] {Tuple.class}, (proxy, method, args) -> {
                    if (method.getName().equals("get") && args.length == 1 && args[0] instanceof String alias) {
                        return values.get(alias);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
