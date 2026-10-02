package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** Columns carrying a {@link ColumnConverter} (spec api/10 R-COL-14, R-COL-11, D-37). */
class ConvertedColumnTest {

    /** The model holds a reference such as {@code #42} for the key and an enum for the status text. */
    record OrderView(String ref, OrderStatus status, Object rawRef, Object rawStatus) {

        long id() {
            return (Long) rawRef;
        }
    }

    record PlainView(Long id, String status) {}

    /** {@code #42} in the model, {@code 42} in the entity. */
    static final class RefConverter implements ColumnConverter<String, Long> {
        static final RefConverter INSTANCE = new RefConverter();

        @Override
        public String toModel(Long attribute) {
            return "#" + attribute;
        }

        @Override
        public Long toAttribute(String model) {
            return Long.valueOf(model.substring(1));
        }
    }

    static final class StatusConverter implements ColumnConverter<OrderStatus, String> {
        static final StatusConverter INSTANCE = new StatusConverter();

        @Override
        public OrderStatus toModel(String attribute) {
            return OrderStatus.valueOf(attribute);
        }

        @Override
        public String toAttribute(OrderStatus model) {
            return model.name();
        }
    }

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);

    private static final ColumnField<OrderView, OrderEntity, String> REF =
            ColumnField.of(OrderView.class, ROOT, "id", String.class, Long.class, RefConverter.INSTANCE);
    private static final ColumnField<OrderView, OrderEntity, OrderStatus> STATUS = ColumnField.of(
            OrderView.class, ROOT, "status", OrderStatus.class, String.class, StatusConverter.INSTANCE);

    private static final ColumnField<PlainView, OrderEntity, Long> ID =
            ColumnField.of(PlainView.class, ROOT, "id", Long.class);
    private static final ColumnField<PlainView, OrderEntity, String> STATUS_TEXT =
            ColumnField.of(PlainView.class, ROOT, "status", String.class);

    private static final ModelQuery.Builder<OrderEntity, String, OrderView> ORDERS = ModelQuery
            .builder(ROOT, row -> new OrderView(row.get(REF), row.get(STATUS), row.raw(REF), row.raw(STATUS)))
            .select(SelectSet.of(REF, STATUS))
            .primaryKey(PrimaryKey.of(REF));

    private static final ModelQuery.Builder<OrderEntity, Long, PlainView> PLAIN = ModelQuery
            .builder(ROOT, row -> new PlainView(row.get(ID), row.get(STATUS_TEXT)))
            .select(SelectSet.of(ID, STATUS_TEXT))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());

    @TckTest
    void ac_col_11_a_converted_column_round_trips_through_row_get_and_through_a_filter(TckDatabase db) {
        // Every value form binds the attribute value: a String for the status, a Long for the reference.
        var converted = ORDERS.orderBy(REF.asc()).where(f -> f
                .ne(STATUS, OrderStatus.CANCELLED)
                .in(STATUS, List.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.CANCELLED))
                .notIn(REF, List.of("#2", "#3"))
                .gt(REF, "#9")
                .between(REF, "#10", "#4000")
                .range(REF, Optional.of("#11"), Optional.empty())).build();
        var plain = PLAIN.where(f -> f
                .ne(STATUS_TEXT, "CANCELLED")
                .in(STATUS_TEXT, List.of("PAID", "SHIPPED", "CANCELLED"))
                .notIn(ID, List.of(2L, 3L))
                .gt(ID, 9L)
                .between(ID, 10L, 4_000L)
                .range(ID, Optional.of(11L), Optional.empty())).build();
        List<OrderView> orders = new ArrayList<>();
        List<OrderView> paid = new ArrayList<>();
        List<PlainView> expected = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "col-11-converted-column", ds -> withExecutor(ds, executor -> {
            orders.addAll(executor.list(converted, Limit.unlimited()));
            paid.addAll(executor.list(
                    ORDERS.orderBy(REF.asc()).where(f -> f.eq(STATUS, OrderStatus.PAID)).build(), Limit.of(3)));
        }));
        withExecutor(JoinTestSupport.dataSource(db),
                executor -> expected.addAll(executor.list(plain, Limit.unlimited())));

        assertThat(orders).isNotEmpty().hasSameSizeAs(expected);
        for (int i = 0; i < orders.size(); i++) {
            OrderView order = orders.get(i);
            PlainView row = expected.get(i);
            // Row.get converts, Row.raw returns the attribute value as read.
            assertThat(order.ref()).isEqualTo("#" + row.id());
            assertThat(order.rawRef()).isEqualTo(row.id());
            assertThat(order.status()).isEqualTo(OrderStatus.valueOf(row.status()));
            assertThat(order.rawStatus()).isEqualTo(row.status());
        }
        assertThat(orders).extracting(OrderView::status).containsOnly(OrderStatus.PAID, OrderStatus.SHIPPED);
        assertThat(paid).hasSize(3).allSatisfy(order -> {
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            assertThat(order.rawStatus()).isEqualTo("PAID");
        });
    }

    @TckTest
    void ac_col_11_keys_and_cursors_are_read_unconverted_so_paging_binds_attribute_values(TckDatabase db) {
        // Ordered by a converted column and keyed by another: the cursor and the key are bound as they were read.
        var keyset = ORDERS.orderBy(STATUS.asc()).keyset().build();
        var twoStep = ORDERS.orderBy(STATUS.desc(), REF.asc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        var oneStep = ORDERS.orderBy(STATUS.desc(), REF.asc()).build();
        List<OrderView> exported = new ArrayList<>();
        List<OrderView> byOffset = new ArrayList<>();
        withExecutor(JoinTestSupport.dataSource(db), executor -> {
            executor.export(keyset, ExportOptions.of(700), page -> page, exported::add);
            executor.export(oneStep, ExportOptions.of(900), page -> page, byOffset::add);
            PageSpec deep = PageSpec.of(3, 450);
            assertThat(executor.page(twoStep, deep, CountMode.NO_COUNT).content())
                    .hasSize(450)
                    .isEqualTo(executor.page(oneStep, deep, CountMode.NO_COUNT).content());
        });

        assertThat(exported).extracting(OrderView::ref).hasSize(TckFixture.ORDERS).doesNotHaveDuplicates();
        assertThat(exported).isSortedAccordingTo(
                Comparator.comparing((OrderView order) -> (String) order.rawStatus()).thenComparing(OrderView::id));
        assertThat(byOffset).extracting(OrderView::ref).hasSize(TckFixture.ORDERS).doesNotHaveDuplicates();
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }
}
