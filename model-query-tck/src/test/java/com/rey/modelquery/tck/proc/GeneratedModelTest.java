package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.col.StampedOrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTarget;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
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
    void ac_proc_06_a_filter_path_under_a_join_reuses_it_and_any_other_path_joins_on_its_own() throws SQLException {
        var selected = QOrderView.query()
                .columns(QOrderView.ALL.with(QOrderView.REFERRER)).orderBy(QOrderView.ID.asc());
        List<OrderView> vipReferrer = new ArrayList<>();
        List<OrderView> german = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(DB, "proc-06-filter-column-joins",
                ds -> withExecutor(ds, OrderEntity.class, executor -> {
                    vipReferrer.addAll(executor.list(
                            selected.where(f -> f.eq(QOrderView.REFERRER_VIP, true)).build(), Limit.unlimited()));
                    german.addAll(executor.list(
                            selected.where(f -> f.eq(QOrderView.REFERRER_VIP, true)
                                    .eq(QOrderView.CUSTOMER_COUNTRY, "DE")).build(),
                            Limit.unlimited()));
                }));

        // Selecting the referrer and filtering on it is one join; the customer, which no @Join reads, is another.
        assertThat(sql).hasSize(2);
        assertThat(sql.get(0).split(" join customers ", -1)).hasSize(2);
        assertThat(sql.get(1).split(" join customers ", -1)).hasSize(3);
        assertThat(vipReferrer)
                .hasSize(count("SELECT COUNT(*) FROM orders o JOIN customers r ON r.id = o.referrer_id WHERE r.vip"))
                .isNotEmpty()
                .allSatisfy(order -> assertThat(order.getReferrer()).isPresent());
        assertThat(german)
                .hasSize(count("SELECT COUNT(*) FROM orders o JOIN customers r ON r.id = o.referrer_id "
                        + "JOIN customers c ON c.id = o.customer_id WHERE r.vip AND c.country = 'DE'"))
                .isNotEmpty();
    }

    @Test
    void ac_proc_07_filter_columns_of_one_alias_share_a_join_and_another_alias_joins_again() throws SQLException {
        var orders = QOrderView.query().columns(QOrderView.ALL).orderBy(QOrderView.ID.asc());
        List<OrderView> sameLine = new ArrayList<>();
        List<OrderView> twoLines = new ArrayList<>();
        List<OrderView> neverOneLine = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(DB, "proc-07-filter-column-aliases",
                ds -> withExecutor(ds, OrderEntity.class, executor -> {
                    sameLine.addAll(executor.list(
                            orders.where(f -> f.eq(QOrderView.LINE_CODE, "P007").eq(QOrderView.LINE_QUANTITY, 1))
                                    .build(),
                            Limit.unlimited()));
                    twoLines.addAll(executor.list(
                            orders.where(f -> f.eq(QOrderView.LINE_QUANTITY, 1).eq(QOrderView.OTHER_QUANTITY, 9))
                                    .build(),
                            Limit.unlimited()));
                    neverOneLine.addAll(executor.list(
                            orders.where(f -> f.eq(QOrderView.LINE_QUANTITY, 1).eq(QOrderView.LINE_QUANTITY, 9))
                                    .build(),
                            Limit.unlimited()));
                }));

        assertThat(sql).hasSize(3);
        assertThat(sql.get(0).split(" join order_items ", -1)).hasSize(2);
        assertThat(sql.get(1).split(" join order_items ", -1)).hasSize(3);
        assertThat(sql.get(2).split(" join order_items ", -1)).hasSize(2);
        // One alias is one item: both of its filters hold on the same row.
        assertThat(sameLine)
                .hasSize(count("SELECT COUNT(*) FROM order_items WHERE product_code = 'P007' AND quantity = 1"))
                .isNotEmpty();
        assertThat(neverOneLine).isEmpty();
        // Two aliases are two items of the order, which no single item could be.
        assertThat(twoLines)
                .hasSize(count("SELECT COUNT(*) FROM order_items a JOIN order_items b ON b.order_id = a.order_id "
                        + "WHERE a.quantity = 1 AND b.quantity = 9"))
                .isNotEmpty();
    }

    @Test
    void ac_proc_07_an_alias_that_is_a_joins_alias_filters_on_that_join() throws SQLException {
        var buyers = QOrderBuyers.query()
                .columns(QOrderBuyers.ALL.with(QOrderBuyers.CUSTOMER).with(QOrderBuyers.BUYER))
                .where(f -> f.eq(QOrderBuyers.CUSTOMER_VIP, true).eq(QOrderBuyers.BUYER_VIP, true))
                .orderBy(QOrderBuyers.ID.asc())
                .build();
        List<OrderBuyers> found = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(DB, "proc-07-filter-alias-selects-a-join",
                ds -> withExecutor(ds, OrderEntity.class,
                        executor -> found.addAll(executor.list(buyers, Limit.unlimited()))));

        // The two @Joins and nothing more: each filter is on the join its alias names, the first for none.
        assertThat(sql).singleElement().satisfies(
                statement -> assertThat(statement.split(" join customers ", -1)).hasSize(3));
        assertThat(found)
                .hasSize(count("SELECT COUNT(*) FROM orders o JOIN customers c ON c.id = o.customer_id WHERE c.vip"))
                .isNotEmpty();
    }

    @Test
    void ac_proc_08_an_inner_filter_through_a_root_collection_leaves_its_table_left() throws SQLException {
        var orders = QOrderView.query().columns(QOrderView.ALL).orderBy(QOrderView.ID.asc());
        List<OrderView> onTheTable = new ArrayList<>();
        List<OrderView> onItsOwnJoin = new ArrayList<>();
        List<OrderView> onBoth = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(DB, "proc-08-inner-filter-beside-the-collection-table",
                ds -> withExecutor(ds, OrderEntity.class, executor -> {
                    onTheTable.addAll(executor.list(
                            orders.where(f -> f.eq(QOrderView.ITEM_CODE, "P007")).build(), Limit.unlimited()));
                    onItsOwnJoin.addAll(executor.list(
                            orders.where(f -> f.eq(QOrderView.ITEM_QUANTITY, 1)).build(), Limit.unlimited()));
                    onBoth.addAll(executor.list(
                            orders.where(f -> f.eq(QOrderView.ITEM_CODE, "P007").eq(QOrderView.ITEM_QUANTITY, 9))
                                    .build(),
                            Limit.unlimited()));
                }));

        assertThat(sql).hasSize(3);
        // ITEMS_TABLE is LEFT whatever a filter column asks for; the INNER path is another join, never merged.
        assertThat(sql.get(0)).contains(" left join order_items ");
        assertThat(sql.get(1)).contains(" join order_items ").doesNotContain(" left join ");
        assertThat(sql.get(2)).contains(" left join order_items ");
        assertThat(sql.get(2).split(" join order_items ", -1)).hasSize(3);
        assertThat(onTheTable)
                .hasSize(count("SELECT COUNT(*) FROM order_items WHERE product_code = 'P007'"))
                .isNotEmpty();
        assertThat(onItsOwnJoin)
                .hasSize(count("SELECT COUNT(*) FROM order_items WHERE quantity = 1"))
                .isNotEmpty();
        assertThat(onBoth)
                .hasSize(count("SELECT COUNT(*) FROM order_items a JOIN order_items b ON b.order_id = a.order_id "
                        + "WHERE a.product_code = 'P007' AND b.quantity = 9"))
                .isNotEmpty();
    }

    @Test
    void ac_proc_08_the_generated_table_of_a_root_collection_is_usable_in_exists() throws SQLException {
        var withItem = QOrderView.query()
                .columns(QOrderView.ALL)
                .where(f -> f.exists(QOrderView.ITEMS_TABLE, item -> item.eq(QOrderView.ITEM_CODE, "P007")))
                .orderBy(QOrderView.ID.asc())
                .build();
        // A model that declares nothing for the collection still has its table.
        var withOrders = QCustomerView.query()
                .columns(QCustomerView.ALL)
                .where(f -> f.exists(QCustomerView.ORDERS_TABLE))
                .orderBy(QCustomerView.ID.asc())
                .build();
        List<OrderView> orders = new ArrayList<>();
        List<CustomerView> customers = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(DB, "proc-08-exists-on-a-root-collection", ds -> {
            withExecutor(ds, OrderEntity.class, executor -> orders.addAll(executor.list(withItem, Limit.unlimited())));
            withExecutor(ds, CustomerEntity.class,
                    executor -> customers.addAll(executor.list(withOrders, Limit.unlimited())));
        });

        assertThat(sql).hasSize(2)
                .allSatisfy(statement -> assertThat(statement).contains("exists(").doesNotContain(" join "));
        assertThat(orders)
                .hasSize(count("SELECT COUNT(DISTINCT order_id) FROM order_items WHERE product_code = 'P007'"))
                .isNotEmpty();
        assertThat(customers)
                .hasSize(count("SELECT COUNT(DISTINCT customer_id) FROM orders"))
                .isNotEmpty();
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

    @Test
    void ac_proc_09_a_summary_model_query_is_grouped_by_its_group_keys() throws SQLException {
        var summary = QStatusSummary.query()
                .columns(QStatusSummary.GROUP_KEYS.with(
                        QStatusSummary.ORDERS, QStatusSummary.REVENUE, QStatusSummary.FIRST_PLACED))
                .orderBy(QStatusSummary.STATUS.asc())
                .build();
        List<StatusSummary> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(DB, "proc-09-summary-model-grouped",
                ds -> withExecutor(ds, OrderEntity.class,
                        executor -> rows.addAll(executor.list(summary, Limit.unlimited()))));

        assertThat(sql).singleElement().satisfies(statement -> assertThat(statement).contains(" group by "));
        assertThat(rows).hasSize(count("SELECT COUNT(DISTINCT status) FROM orders")).isNotEmpty();
        assertThat(rows).extracting(StatusSummary::status).isSorted().doesNotHaveDuplicates();
        assertThat(rows.stream().mapToLong(StatusSummary::orders).sum()).isEqualTo(TckFixture.ORDERS);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.orders()).isEqualTo(
                    count("SELECT COUNT(*) FROM orders WHERE status = '" + row.status() + "'"));
            assertThat(row.revenue()).isEqualByComparingTo(decimal(
                    "SELECT SUM(total) FROM orders WHERE status = '" + row.status() + "'"));
            assertThat(row.firstPlaced()).isNotNull();
        });
    }

    @Test
    void ac_proc_10_a_single_group_model_query_returns_one_row_with_no_group_by_and_no_key() throws SQLException {
        var totals = QOrderTotals.query()
                .columns(ColumnSet.of(QOrderTotals.ORDERS, QOrderTotals.REVENUE))
                .build();
        List<OrderTotals> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(DB, "proc-10-summary-model-single-group",
                ds -> withExecutor(ds, OrderEntity.class,
                        executor -> rows.addAll(executor.list(totals, Limit.unlimited()))));

        assertThat(sql).singleElement().satisfies(statement -> assertThat(statement).doesNotContain(" group by "));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.orders()).isEqualTo(TckFixture.ORDERS);
            assertThat(row.revenue()).isEqualByComparingTo(decimal("SELECT SUM(total) FROM orders"));
        });
        assertThat(totals.primaryKey()).isEmpty();
    }

    @Test
    void ac_proc_11_a_generated_date_column_over_a_timestamp_filters_with_an_optional_date() {
        var stamped = QStampedOrderView.query().columns(QStampedOrderView.ALL).orderBy(QStampedOrderView.ID.asc());
        List<StampedOrderView> all = new ArrayList<>();
        withExecutor(StampedOrderEntity.class,
                executor -> all.addAll(executor.list(stamped.build(), Limit.unlimited())));
        Date cutoff = all.stream().map(StampedOrderView::placed).sorted().toList().get(all.size() / 2);
        Optional<Date> from = Optional.of(cutoff);
        Optional<Date> none = Optional.empty();
        List<StampedOrderView> later = new ArrayList<>();
        List<StampedOrderView> unfiltered = new ArrayList<>();
        withExecutor(StampedOrderEntity.class, executor -> {
            later.addAll(executor.list(
                    stamped.where(f -> f.gte(QStampedOrderView.PLACED, from)).build(), Limit.unlimited()));
            unfiltered.addAll(executor.list(
                    stamped.where(f -> f.gte(QStampedOrderView.PLACED, none)).build(), Limit.unlimited()));
        });

        // The built-in converter hands back the Timestamp read, typed as the field's Date.
        assertThat(all).hasSize(TckFixture.ORDERS)
                .allSatisfy(order -> assertThat(order.placed()).isInstanceOf(Timestamp.class));
        assertThat(later).isNotEmpty().hasSizeLessThan(all.size())
                .isEqualTo(all.stream().filter(order -> order.placed().compareTo(cutoff) >= 0).toList());
        assertThat(unfiltered).isEqualTo(all);
    }

    @Test
    void ac_proc_12_a_generated_max_over_a_timestamp_reads_the_database_max_as_a_date() throws SQLException {
        var summary = QStampSummary.query()
                .columns(QStampSummary.GROUP_KEYS.with(QStampSummary.LAST_PLACED))
                .orderBy(QStampSummary.STATUS.asc())
                .build();
        List<StampSummary> rows = new ArrayList<>();
        SqlSnapshots.assertMatches(DB, "proc-12-built-in-converter-max",
                ds -> withExecutor(ds, StampedOrderEntity.class,
                        executor -> rows.addAll(executor.list(summary, Limit.unlimited()))));

        assertThat(rows).hasSize(count("SELECT COUNT(DISTINCT status) FROM orders")).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.lastPlaced()).isInstanceOf(Timestamp.class)
                .isEqualTo(timestamp("SELECT MAX(placed_at) FROM orders WHERE status = '" + row.status() + "'")));
    }

    private static BigDecimal decimal(String sql) throws SQLException {
        try (Connection connection = DB.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBigDecimal(1);
        }
    }

    private static Timestamp timestamp(String sql) throws SQLException {
        try (Connection connection = DB.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getTimestamp(1);
        }
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
