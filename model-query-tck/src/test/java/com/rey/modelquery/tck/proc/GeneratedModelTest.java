package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTarget;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/**
 * Generated QModels against a database (spec processor/30 §3, §4, processor/31 §4). They run on H2 alone: what a
 * QModel renders is covered per vendor by the hand-written models of the other suites.
 */
class GeneratedModelTest {

    private static final TckDatabase DB = TckDatabases.get(TckTarget.h2());

    @Test
    void ac_proc_04_a_converted_column_round_trips_through_row_get_and_through_a_filter_on_it() throws SQLException {
        var orders = QOrderView.query().columns(QOrderView.ALL).orderBy(QOrderView.ID.asc());
        List<OrderView> paid = new ArrayList<>();
        List<OrderView> all = new ArrayList<>();
        withExecutor(OrderEntity.class, executor -> {
            // The filter binds the attribute value, the text 'PAID'.
            paid.addAll(executor.list(
                    orders.where(f -> f.eq(QOrderView.STATUS, OrderStatus.PAID)).build(), Limit.unlimited()));
            all.addAll(executor.list(
                    orders.where(f -> f.in(QOrderView.STATUS, List.of(OrderStatus.values()))).build(),
                    Limit.unlimited()));
        });

        assertThat(paid).hasSize(count("SELECT COUNT(*) FROM orders WHERE status = 'PAID'")).isNotEmpty()
                .allSatisfy(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID));
        // Row.get returns the model value of every status the fixture holds.
        assertThat(all).hasSize(TckFixture.ORDERS);
        assertThat(all).extracting(OrderView::getStatus).containsOnly(OrderStatus.values());
    }

    @Test
    void ac_proc_05_two_joins_on_one_attribute_are_two_joins_with_distinct_aliases() {
        var buyers = QOrderBuyers.query()
                .columns(QOrderBuyers.ALL.with(QOrderBuyers.CUSTOMER).with(QOrderBuyers.BUYER))
                .orderBy(QOrderBuyers.ID.asc())
                .build();
        List<OrderBuyers> found = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(DB, "proc-05-two-joins-one-attribute",
                ds -> withExecutor(ds, OrderEntity.class,
                        executor -> found.addAll(executor.list(buyers, Limit.of(50)))));

        assertThat(sql).singleElement().satisfies(
                statement -> assertThat(statement.split(" join customers ", -1)).hasSize(3));
        assertThat(found).hasSize(50).allSatisfy(order -> {
            assertThat(order.customer()).isPresent();
            assertThat(order.buyer()).isEqualTo(order.customer());
        });
    }

    @Test
    void ac_gen_06_a_left_join_miss_is_empty_and_a_match_whose_other_columns_are_null_is_present() {
        // The referrer is joined LEFT, and two thirds of the orders have none.
        var withReferrer = QOrderView.query()
                .columns(QOrderView.ALL.with(QOrderView.REFERRER)).orderBy(QOrderView.ID.asc()).build();
        // Only a nullable column of the order is selected: its key is read because the join says so.
        var orderReferrerOnly = QItemView.query()
                .columns(ColumnSet.of(QItemView.ID, QItemView.ORDER_REFERRER_KEY)).orderBy(QItemView.ID.asc()).build();
        var unjoined = QOrderView.query().columns(QOrderView.ALL).orderBy(QOrderView.ID.asc()).build();
        List<OrderView> orders = new ArrayList<>();
        List<OrderView> flat = new ArrayList<>();
        List<ItemView> items = new ArrayList<>();
        withExecutor(OrderEntity.class, executor -> {
            orders.addAll(executor.list(withReferrer, Limit.unlimited()));
            flat.addAll(executor.list(unjoined, Limit.of(100)));
        });
        withExecutor(OrderItemEntity.class,
                executor -> items.addAll(executor.list(orderReferrerOnly, Limit.of(2_000))));

        assertThat(orders).hasSize(TckFixture.ORDERS).allSatisfy(order -> {
            if (order.getReferrerKey() == null) {
                assertThat(order.getReferrer()).isEmpty();
            } else {
                assertThat(order.getReferrer()).get().satisfies(referrer -> {
                    assertThat(referrer.id()).isEqualTo(order.getReferrerKey());
                    assertThat(referrer.name()).isNotNull();
                });
            }
        });
        assertThat(orders).anyMatch(order -> order.getReferrer().isEmpty())
                .anyMatch(order -> order.getReferrer().isPresent());
        // Every item has its order, also where the one column selected from it is NULL.
        assertThat(items).hasSize(2_000).allSatisfy(item -> assertThat(item.order()).get().satisfies(order -> {
            assertThat(order.getId()).isNotNull();
            assertThat(order.getStatus()).isNull();
            assertThat(order.getReferrer()).isEmpty();
        }));
        assertThat(items).anyMatch(item -> item.order().orElseThrow().getReferrerKey() == null)
                .anyMatch(item -> item.order().orElseThrow().getReferrerKey() != null);
        // No column of the join selected: empty, and never null, although the field has no initialiser.
        assertThat(flat).hasSize(100).allSatisfy(order -> assertThat(order.getReferrer()).isEmpty());
    }

    @Test
    void ac_gen_07_two_level_nesting_maps_a_class_in_a_record_and_a_record_in_that_class() {
        var nested = QItemView.query()
                .columns(QItemView.ALL.with(QItemView.ORDER).with(QItemView.ORDER_REFERRER))
                .orderBy(QItemView.ID.asc())
                .build();
        List<ItemView> items = new ArrayList<>();
        withExecutor(OrderItemEntity.class, executor -> items.addAll(executor.list(nested, Limit.of(2_000))));

        assertThat(items).hasSize(2_000).allSatisfy(item -> {
            assertThat(item.productCode()).isNotNull();
            // The class OrderView, nested in the record ItemView.
            OrderView order = item.order().orElseThrow();
            // Order of item i, as the fixture assigns it.
            assertThat(order.getId()).isEqualTo((item.id() - 1) * 7 % TckFixture.ORDERS + 1);
            assertThat(order.getStatus()).isNotNull();
            // The record CustomerView, nested in that class.
            if (order.getReferrerKey() == null) {
                assertThat(order.getReferrer()).isEmpty();
            } else {
                CustomerView referrer = order.getReferrer().orElseThrow();
                assertThat(referrer.id()).isEqualTo(order.getReferrerKey());
                assertThat(referrer.name()).isNotNull();
                assertThat(referrer.country()).isNotNull();
            }
        });
        assertThat(items).anyMatch(item -> item.order().orElseThrow().getReferrer().isPresent())
                .anyMatch(item -> item.order().orElseThrow().getReferrer().isEmpty());
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = DB.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static <E> void withExecutor(Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        withExecutor(JoinTestSupport.dataSource(DB), root, work);
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
