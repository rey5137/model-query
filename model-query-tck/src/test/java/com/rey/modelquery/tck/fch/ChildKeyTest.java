package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.grouped;
import static com.rey.modelquery.tck.fch.FetchTestSupport.limited;
import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ChildQuery;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * Child keys: to-one children by key, deduplication per key and child, many-to-many children, key rounds, keys matched
 * on attribute values and refused when a collation equates unequal ones, child filters and order, and the per-parent
 * bound (spec api/15 R-FCH-03, R-FCH-04, R-FCH-05, R-FCH-11, D-99; AC-FCH-02, -03, -04, -08, -11).
 */
class ChildKeyTest {

    private static final FetchPlan<OrderLines> ORDER = FetchPlan.of(QOrderLines.ALL);
    private static final FetchPlan<Buyer> BUYER = FetchPlan.of(QBuyer.ALL);

    /** Orders 1 to {@code last} with {@code plan}, by id. */
    private static ModelQuery<OrderEntity, Long, OrderBuyer> orders(long last, FetchPlan<OrderBuyer> plan) {
        return QOrderBuyer.query().where(f -> f.lte(QOrderBuyer.ID, last)).orderBy(QOrderBuyer.ID.asc())
                .fetch(plan).build();
    }

    /** Customers 1 to {@code last} with their orders, loaded by {@code child}, by id. */
    private static ModelQuery<CustomerEntity, Long, CustomerOrders> customers(long last,
            UnaryOperator<ChildQuery<OrderLines>> child) {
        return QCustomerOrders.query().where(f -> f.lte(QCustomerOrders.ID, last))
                .orderBy(QCustomerOrders.ID.asc())
                .fetch(FetchPlan.of(QCustomerOrders.ALL).child(QCustomerOrders.ORDERS, ORDER, child)).build();
    }

    /** The fixture's orders of customers 1 to {@code last}, by customer, in {@code order}. */
    private static Map<Long, List<Long>> ordersOf(TckDatabase db, long last, String where, String order) {
        return grouped(db, "SELECT customer_id, id FROM orders WHERE customer_id <= " + last + where + " ORDER BY "
                + order);
    }

    @TckTest
    void ac_fch_02_a_to_one_child_by_key_loads(TckDatabase db) {
        var plan = FetchPlan.of(QOrderBuyer.ALL).child(QOrderBuyer.BUYER, BUYER).child(QOrderBuyer.REFERRER, BUYER);
        Map<Long, List<Long>> buyers = grouped(db, "SELECT id, customer_id FROM orders WHERE id <= 9");
        Map<Long, List<Long>> referrers =
                grouped(db, "SELECT id, referrer_id FROM orders WHERE id <= 9 AND referrer_id IS NOT NULL");

        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor -> {
            List<OrderBuyer> loaded = executor.list(orders(9, plan), Limit.unlimited());
            assertThat(loaded).hasSize(9).allSatisfy(order -> {
                assertThat(order.buyer()).map(Buyer::id).hasValue(buyers.get(order.id()).get(0));
                assertThat(order.buyer().orElseThrow().name()).startsWith("Customer ");
                // A null key loads nothing: two thirds of the orders have no referrer.
                assertThat(order.referrer().map(Buyer::id))
                        .isEqualTo(Optional.ofNullable(referrers.get(order.id())).map(ids -> ids.get(0)));
            });
            assertThat(loaded).filteredOn(order -> order.referrer().isPresent()).hasSize(3);
        });
    }

    @TckTest
    void ac_fch_02_two_distinct_rows_for_one_key_throw_mq2601(TckDatabase db) {
        var plan = FetchPlan.of(QOrderBuyer.ALL).child(QOrderBuyer.SAME_BUYER_ORDER, FetchPlan.of(QOrderRef.ALL));

        // Order 1's buyer, customer 38, also placed order 1001, which the child statement reads first.
        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor -> assertCode(
                () -> executor.list(orders(3, plan), Limit.unlimited()), MqCode.MQ2601,
                "MQ2601: OrderBuyer.sameBuyerOrder: the to-one child found two distinct rows for key 38; key it on a "
                        + "column unique per child, or declare the field a List"));
    }

    @TckTest
    void ac_fch_02_repeated_child_rows_from_a_to_many_child_filter_are_deduplicated(TckDatabase db) {
        // Every order has four items, so the filter through the to-many join reads each order four times.
        var q = customers(10, c -> c.where(f -> f.like(QOrderLines.ITEM_CODE, "P0", LikeMode.STARTS_WITH)));
        Map<Long, List<Long>> expected = ordersOf(db, 10, "", "id");

        withExecutor(JoinTestSupport.dataSource(db), CustomerEntity.class, executor ->
                assertThat(executor.list(q, Limit.unlimited())).hasSize(10).allSatisfy(customer ->
                        assertThat(customer.orders()).extracting(OrderLines::id)
                                .containsExactlyElementsOf(expected.get(customer.id()))));
    }

    @TckTest
    void ac_fch_11_a_foreign_key_crossing_the_child_collection_loads_a_child_shared_by_two_parents(TckDatabase db) {
        // Orders 191 to 200 have labels, 201 to 210 none; each label belongs to many of them.
        var q = QOrderLabels.query().where(f -> f.between(QOrderLabels.ID, 191L, 210L))
                .orderBy(QOrderLabels.ID.asc())
                .fetch(FetchPlan.of(QOrderLabels.ALL).child(QOrderLabels.LABELS, FetchPlan.of(QLabelView.ALL)))
                .build();

        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor -> {
            List<OrderLabels> loaded = executor.list(q, Limit.unlimited());
            assertThat(loaded).hasSize(20).allSatisfy(order -> {
                assertThat(order.labels()).extracting(LabelView::id)
                        .containsExactlyElementsOf(TckFixture.labelsOf(order.id()));
                assertThat(order.labels()).allSatisfy(label ->
                        assertThat(label.name()).isEqualTo(String.format("label-%02d", label.id())));
            });
            // Orders 192 and 194 share label 3, which is read once for each.
            assertThat(loaded.get(1).labels()).extracting(LabelView::id).contains(3L);
            assertThat(loaded.get(3).labels()).extracting(LabelView::id).contains(3L);
            assertThat(loaded.subList(10, 20)).allSatisfy(order -> assertThat(order.labels()).isEmpty());
        });
    }

    @TckTest
    void ac_fch_03_child_keys_above_the_vendor_limit_split_into_rounds(TckDatabase db) {
        var q = customers(10, UnaryOperator.identity());
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 4)));
        Map<Long, List<Long>> expected = ordersOf(db, 10, "", "id");

        // The page, then rounds of four, four and two keys.
        List<String> sql = SqlSnapshots.assertMatches(db, "fch-03-key-rounds", ds -> withExecutor(ds,
                CustomerEntity.class, config, executor -> assertThat(executor.list(q, Limit.unlimited()))
                        .hasSize(10).allSatisfy(customer -> assertThat(customer.orders()).extracting(OrderLines::id)
                                .containsExactlyElementsOf(expected.get(customer.id())))));
        assertThat(sql).hasSize(4);
        assertThat(sql.subList(1, 4)).extracting(FetchTestSupport::binds).containsExactly(4L, 4L, 2L);
    }

    @TckTest
    void ac_fch_03_a_page_with_no_keys_runs_no_child_statement(TckDatabase db) {
        var plan = FetchPlan.of(QOrderBuyer.ALL).child(QOrderBuyer.REFERRER, BUYER);

        // Orders 1 and 2 have no referrer, and a page past the last order has no row.
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
            assertThat(executor.list(orders(2, plan), Limit.unlimited())).hasSize(2)
                    .allSatisfy(order -> assertThat(order.referrer()).isEmpty());
            assertThat(executor.list(QOrderBuyer.query().where(f -> f.gt(QOrderBuyer.ID, (long) TckFixture.ORDERS))
                    .fetch(plan).build(), Limit.unlimited())).isEmpty();
        }));
        assertThat(sql).hasSize(2);
    }

    @TckTest
    void ac_fch_03_a_converted_key_column_matches_on_its_attribute_value(TckDatabase db) {
        // The parent's key read through a converter: the child's foreign key equals its attribute value, never the
        // converted "C-1".
        ColumnField<CustomerOrders, CustomerEntity, String> code = ColumnField.of(CustomerOrders.class,
                QCustomerOrders.ROOT, "id", String.class, Long.class, new ColumnConverter<String, Long>() {
                    @Override
                    public String toModel(Long id) {
                        return "C-" + id;
                    }

                    @Override
                    public Long toAttribute(String model) {
                        return Long.valueOf(model.substring(2));
                    }
                });
        var q = QCustomerOrders.query().where(f -> f.lte(QCustomerOrders.ID, 5L)).orderBy(QCustomerOrders.ID.asc())
                .fetch(FetchPlan.of(QCustomerOrders.ALL).child(keyedOn(code), ORDER)).build();
        Map<Long, List<Long>> expected = ordersOf(db, 5, "", "id");

        withExecutor(JoinTestSupport.dataSource(db), CustomerEntity.class, executor ->
                assertThat(executor.list(q, Limit.unlimited())).hasSize(5).allSatisfy(customer ->
                        assertThat(customer.orders()).extracting(OrderLines::id)
                                .containsExactlyElementsOf(expected.get(customer.id()))));
    }

    @TckTest
    void ac_fch_03_a_case_insensitive_collation_unmatched_child_row_throws_mq2604(TckDatabase db) {
        var plan = FetchPlan.of(QCustomerNotes.ALL).child(QCustomerNotes.NOTES, FetchPlan.of(QNote.ALL));

        withExecutor(JoinTestSupport.dataSource(db), CustomerEntity.class, executor -> {
            // Customer 1's three notes match its email exactly.
            assertThat(executor.list(notes(1, plan), Limit.unlimited())).singleElement().satisfies(customer ->
                    assertThat(customer.notes()).extracting(Note::id).containsExactly(1L, 2L, 3L));
            // Customer 2's email matches note 5 only by the column's case-insensitive collation.
            assertCode(() -> executor.list(notes(3, plan), Limit.unlimited()), MqCode.MQ2604,
                    "MQ2604: CustomerNotes.notes: a child row's Note.customerEmail is CUSTOMER0002@EXAMPLE.TEST, "
                            + "which equals none of the 3 keys of the statement that matched it; the column's "
                            + "collation equates values Java tells apart, by case or trailing spaces, so key the "
                            + "child on a column with a binary collation");
        });
    }

    @TckTest
    void ac_fch_04_child_filters_and_order_apply_closed_by_the_child_primary_key(TckDatabase db) {
        var q = customers(10, c -> c.where(f -> f.eq(QOrderLines.STATUS, "PAID")).orderBy(QOrderLines.TOTAL.desc()));
        Map<Long, List<Long>> expected = ordersOf(db, 10, " AND status = 'PAID'", "total DESC, id");

        SqlSnapshots.assertMatches(db, "fch-04-child-filter-order", ds -> withExecutor(ds, CustomerEntity.class,
                executor -> assertThat(executor.list(q, Limit.unlimited())).hasSize(10).allSatisfy(customer ->
                        assertThat(customer.orders()).extracting(OrderLines::id)
                                .containsExactlyElementsOf(expected.getOrDefault(customer.id(), List.of())))));
    }

    @TckTest
    void ac_fch_04_the_default_child_order_is_the_primary_key(TckDatabase db) {
        var q = customers(3, UnaryOperator.identity());
        Map<Long, List<Long>> expected = ordersOf(db, 3, "", "id");

        SqlSnapshots.assertMatches(db, "fch-04-default-order", ds -> withExecutor(ds, CustomerEntity.class,
                executor -> assertThat(executor.list(q, Limit.unlimited())).hasSize(3).allSatisfy(customer ->
                        assertThat(customer.orders()).extracting(OrderLines::id)
                                .containsExactlyElementsOf(expected.get(customer.id())))));
    }

    @TckTest
    void ac_fch_08_max_per_parent_throws_mq2603_within_one_round_cap(TckDatabase db) {
        // Each order is read once per item, four times, so 3 x 5 + 1 rows hold no customer's sixth order.
        var q = customers(3, c -> c.where(f -> f.like(QOrderLines.ITEM_CODE, "P0", LikeMode.STARTS_WITH))
                .maxPerParent(5));

        withExecutor(JoinTestSupport.dataSource(db), CustomerEntity.class, executor ->
                assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2603))
                        .hasMessageStartingWith("MQ2603: CustomerOrders.orders: a round of 3 keys read its cap of 3 x "
                                + "maxPerParent(5) + 1 rows, key "));
    }

    @TckTest
    void ac_fch_08_a_parent_with_more_children_than_max_per_parent_throws_mq2603(TckDatabase db) {
        var plan = FetchPlan.of(QCustomerNotes.ALL)
                .child(QCustomerNotes.NOTES, FetchPlan.of(QNote.ALL), c -> c.maxPerParent(2));
        var q = QCustomerNotes.query().where(f -> f.in(QCustomerNotes.ID, List.of(1L, 3L, 4L))).fetch(plan).build();

        withExecutor(JoinTestSupport.dataSource(db), CustomerEntity.class, executor -> assertCode(
                () -> executor.list(q, Limit.unlimited()), MqCode.MQ2603,
                "MQ2603: CustomerNotes.notes: key customer0001@example.test has 3 children, more than "
                        + "maxPerParent(2); raise the bound or narrow the child filters"));
    }

    @TckTest
    void ac_fch_08_max_per_parent_at_the_largest_count_loads_every_child(TckDatabase db) {
        var q = customers(3, c -> c.maxPerParent(5));
        Map<Long, List<Long>> expected = ordersOf(db, 3, "", "id");

        withExecutor(JoinTestSupport.dataSource(db), CustomerEntity.class, executor ->
                assertThat(executor.list(q, Limit.unlimited())).hasSize(3).allSatisfy(customer ->
                        assertThat(customer.orders()).extracting(OrderLines::id).hasSize(5)
                                .containsExactlyElementsOf(expected.get(customer.id()))));
    }

    @TckTest
    void ac_fch_08_max_per_parent_below_one_throws_mq2001(TckDatabase db) {
        assertCode(() -> customers(3, c -> c.maxPerParent(0)), MqCode.MQ2001,
                "MQ2001: maxPerParent(0) must be positive");
    }

    /** Customers 1 to {@code last} with their notes. */
    private static ModelQuery<CustomerEntity, Long, CustomerNotes> notes(long last, FetchPlan<CustomerNotes> plan) {
        return QCustomerNotes.query().where(f -> f.lte(QCustomerNotes.ID, last)).orderBy(QCustomerNotes.ID.asc())
                .fetch(plan).build();
    }

    /** The generated orders child, keyed on {@code key} instead. */
    private static ChildField<CustomerOrders, OrderLines> keyedOn(ColumnField<CustomerOrders, ?, ?> key) {
        ChildField<CustomerOrders, OrderLines> generated = QCustomerOrders.ORDERS;
        return new ChildField<>() {
            @Override
            public String name() {
                return generated.name();
            }

            @Override
            public ColumnField<CustomerOrders, ?, ?> key() {
                return key;
            }

            @Override
            public ColumnField<OrderLines, ?, ?> foreignKey() {
                return generated.foreignKey();
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, OrderLines> query() {
                return generated.query();
            }

            @Override
            public CustomerOrders with(CustomerOrders parent, List<OrderLines> children) {
                return generated.with(parent, children);
            }
        };
    }

    private static void assertCode(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(code)).hasMessage(message);
    }
}
