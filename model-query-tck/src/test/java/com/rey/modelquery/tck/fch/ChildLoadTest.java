package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.binds;
import static com.rey.modelquery.tck.fch.FetchTestSupport.grouped;
import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;

/**
 * A three-level plan loads every level on every call that returns or passes on models, leaving the parent page as it
 * is without a plan, and runs on nothing else: not on {@code count}, {@code ONLY_COUNT} nor the probe row, and
 * {@code stream} refuses it (spec api/15 R-FCH-05, R-FCH-09, AC-FCH-01, AC-FCH-07).
 */
class ChildLoadTest {

    private static final long LAST_CUSTOMER = 40;

    private static final FetchPlan<Line> LINE = FetchPlan.of(QLine.ALL);
    private static final FetchPlan<OrderLines> ORDER = FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS, LINE);
    private static final FetchPlan<CustomerOrders> CUSTOMER =
            FetchPlan.of(QCustomerOrders.ALL).child(QCustomerOrders.ORDERS, ORDER);

    /** Customers 1 to {@link #LAST_CUSTOMER}, by name descending, without a selection or a plan yet. */
    private static ModelQuery.Builder<CustomerEntity, Long, CustomerOrders> customers() {
        return QCustomerOrders.query()
                .where(f -> f.lte(QCustomerOrders.ID, LAST_CUSTOMER))
                .orderBy(QCustomerOrders.NAME.desc());
    }

    @TckTest
    void ac_fch_01_a_three_level_plan_loads_every_level_on_list_and_offset_pages(TckDatabase db) {
        checkPaths(db, customers(), (executor, check) -> {
            check.list(executor);
            check.page(executor, 7);
        });
    }

    @TckTest
    void ac_fch_01_a_three_level_plan_loads_every_level_on_keyset_pages_and_keyset_export(TckDatabase db) {
        checkPaths(db, customers().keyset(), (executor, check) -> {
            check.page(executor, 14);
            check.export(executor);
        });
    }

    @TckTest
    void ac_fch_01_a_three_level_plan_loads_every_level_on_primary_key_first_pages_and_export(TckDatabase db) {
        checkPaths(db, customers().primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(5)), (executor, check) -> {
            check.page(executor, 14);
            check.export(executor);
        });
    }

    @TckTest
    void ac_fch_01_a_three_level_plan_loads_every_level_on_offset_export(TckDatabase db) {
        checkPaths(db, customers(), (executor, check) -> check.export(executor));
    }

    @TckTest
    void ac_fch_07_stream_with_a_plan_throws_mq2605_before_any_statement(TckDatabase db) {
        ModelQuery<CustomerEntity, Long, CustomerOrders> q = customers().fetch(CUSTOMER).build();
        ModelQuery<CustomerEntity, Long, CustomerOrders> selectionOnly =
                customers().fetch(FetchPlan.of(QCustomerOrders.ALL)).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CustomerEntity.class, executor ->
                assertThatThrownBy(() -> executor.stream(q, Limit.unlimited(), Stream::count))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2605))
                        .hasMessage("MQ2605: CustomerOrders: stream(...) cannot run the fetch plan, whose children, "
                                + "join plans and enrichers run once per page, and a stream has no page; use "
                                + "export(...), which runs the plan on each page")));
        assertThat(sql).isEmpty();
        // A plan that only selects has nothing to run per page; PostgreSQL streams only in a transaction.
        try (SessionFactory sf = JoinTestSupport.sessionFactory(JoinTestSupport.dataSource(db))) {
            sf.inTransaction(em -> {
                long streamed = ModelQueryExecutor.create(em, CustomerEntity.class, ModelQueryConfig.defaults())
                        .stream(selectionOnly, Limit.unlimited(), Stream::count);
                assertThat(streamed).isEqualTo(LAST_CUSTOMER);
            });
        }
    }

    @TckTest
    void ac_fch_07_count_and_only_count_run_no_child_query(TckDatabase db) {
        ModelQuery<CustomerEntity, Long, CustomerOrders> q = customers().fetch(CUSTOMER).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CustomerEntity.class, executor -> {
            assertThat(executor.count(q)).isEqualTo(LAST_CUSTOMER);
            Slice<CustomerOrders> page = executor.page(q, PageSpec.of(0, 5), CountMode.ONLY_COUNT);
            assertThat(page.content()).isEmpty();
            assertThat(page.total()).hasValue(LAST_CUSTOMER);
        }));
        assertThat(sql).hasSize(2);
    }

    @TckTest
    void ac_fch_07_the_probe_row_children_are_never_loaded(TckDatabase db) {
        ModelQuery<CustomerEntity, Long, CustomerOrders> q = customers().fetch(CUSTOMER).build();
        ModelQuery<CustomerEntity, Long, CustomerOrders> keysFirst = customers()
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).fetch(CUSTOMER).build();

        List<String> sql = SqlSnapshots.assertMatches(db, "fch-07-probe-row", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> {
                    for (ModelQuery<CustomerEntity, Long, CustomerOrders> query : List.of(q, keysFirst)) {
                        Slice<CustomerOrders> page = executor.page(query, PageSpec.ofOffset(3, 3), CountMode.NO_COUNT);
                        assertThat(page.hasNext()).isTrue();
                        assertThat(page.content()).hasSize(3).allSatisfy(c -> assertThat(c.orders()).isNotEmpty());
                    }
                }));
        // The page, then its orders and their items; primary-key-first reads the page's keys and rows first. The
        // probe row is read by the page's statements, and its key is not among the orders' three.
        assertThat(sql).hasSize(7);
        assertThat(binds(sql.get(1))).isEqualTo(3);
        assertThat(binds(sql.get(5))).isEqualTo(3);
    }

    /**
     * Runs {@code calls} with the query of {@code definition} and {@link #CUSTOMER}, comparing each result with the
     * same call on the query without a plan, and its children at every level with the fixture's.
     */
    private static void checkPaths(TckDatabase db, ModelQuery.Builder<CustomerEntity, Long, CustomerOrders> definition,
            BiConsumer<ModelQueryExecutor<CustomerEntity>, Check> calls) {
        var check = new Check(definition.fetch(CUSTOMER).build(), definition.select(QCustomerOrders.ALL).build(),
                grouped(db, "SELECT customer_id, id FROM orders WHERE customer_id <= " + LAST_CUSTOMER
                        + " ORDER BY id"),
                grouped(db, "SELECT i.order_id, i.id FROM order_items i JOIN orders o ON o.id = i.order_id"
                        + " WHERE o.customer_id <= " + LAST_CUSTOMER + " ORDER BY i.id"));
        withExecutor(JoinTestSupport.dataSource(db), CustomerEntity.class, executor -> calls.accept(executor, check));
    }

    /** A query with the plan, the same query without it, and the children the fixture holds. */
    private record Check(ModelQuery<CustomerEntity, Long, CustomerOrders> q,
            ModelQuery<CustomerEntity, Long, CustomerOrders> plain, Map<Long, List<Long>> orders,
            Map<Long, List<Long>> items) {

        void list(ModelQueryExecutor<CustomerEntity> executor) {
            List<CustomerOrders> loaded = executor.list(q, Limit.unlimited());
            assertThat(ids(loaded)).hasSize((int) LAST_CUSTOMER)
                    .containsExactlyElementsOf(ids(executor.list(plain, Limit.unlimited())));
            assertLoaded(loaded);
        }

        void page(ModelQueryExecutor<CustomerEntity> executor, int offset) {
            for (CountMode mode : List.of(CountMode.COUNT, CountMode.NO_COUNT)) {
                Slice<CustomerOrders> page = executor.page(q, PageSpec.ofOffset(offset, 7), mode);
                Slice<CustomerOrders> without = executor.page(plain, PageSpec.ofOffset(offset, 7), mode);
                assertThat(ids(page.content())).hasSize(7).containsExactlyElementsOf(ids(without.content()));
                assertThat(page.hasNext()).isEqualTo(without.hasNext());
                assertThat(page.total()).isEqualTo(without.total());
                assertLoaded(page.content());
            }
        }

        void export(ModelQueryExecutor<CustomerEntity> executor) {
            List<CustomerOrders> exported = new ArrayList<>();
            List<CustomerOrders> without = new ArrayList<>();
            assertThat(executor.export(q, ExportOptions.of(7), page -> page, exported::add)).isEqualTo(LAST_CUSTOMER);
            executor.export(plain, ExportOptions.of(7), page -> page, without::add);
            assertThat(ids(exported)).containsExactlyElementsOf(ids(without));
            assertLoaded(exported);
        }

        private void assertLoaded(List<CustomerOrders> customers) {
            for (CustomerOrders customer : customers) {
                assertThat(customer.orders()).extracting(OrderLines::id).as("orders of customer %s", customer.id())
                        .isNotEmpty().containsExactlyElementsOf(orders.get(customer.id()));
                for (OrderLines order : customer.orders()) {
                    assertThat(order.items()).extracting(Line::id).as("items of order %s", order.id())
                            .isNotEmpty().containsExactlyElementsOf(items.get(order.id()));
                }
            }
        }

        private static List<Long> ids(List<CustomerOrders> customers) {
            return customers.stream().map(CustomerOrders::id).toList();
        }
    }
}
