package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.grouped;
import static com.rey.modelquery.tck.fch.FetchTestSupport.limited;
import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeyGroups;
import com.rey.modelquery.core.KeyGroups.GroupingRule;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.LongStream;

/**
 * Child loads in rounds per key group: each group's values bound into one statement text, split within a group by
 * the bind limits, every group and value computed before the first child round, the {@code verifyMissing} re-read
 * and {@code MQ2608}, and the {@code groupWhen} branches (spec api/15 R-FCH-18, D-204..D-208; AC-FCH-19 to
 * AC-FCH-25). The fixture's timestamps are functions of the id: order {@code i} is placed {@code 17 i} minutes, and
 * customer {@code c} created {@code c} hours, after 2020-01-01; order {@code i}'s customer is
 * {@code 37 i mod 1000 + 1}.
 */
class KeyGroupChildTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final QueryParam<LocalDateTime> PLACED_FROM = QueryParam.of("placedFrom", LocalDateTime.class);
    private static final QueryParam<LocalDateTime> CREATED_FROM = QueryParam.of("createdFrom", LocalDateTime.class);
    private static final QueryParam<LocalDateTime> CREATED_TO = QueryParam.of("createdTo", LocalDateTime.class);

    /** A half-open range of creation times. */
    record Range(LocalDateTime from, LocalDateTime to) {}

    /** Limits every Tier-1 vendor binds for a timestamp column (MySQL DATETIME: 1000-01-01 to 9999-12-31). */
    private static final Range ALL = new Range(LocalDateTime.of(1000, 1, 1, 0, 0),
            LocalDateTime.of(9999, 12, 31, 0, 0));

    private static long customerOf(long order) {
        return order * 37 % TckFixture.CUSTOMERS + 1;
    }

    /** The day order {@code order}'s customer was created on. */
    private static Range dayOf(long order) {
        LocalDateTime day = BASE.plusHours(customerOf(order)).toLocalDate().atStartOfDay();
        return new Range(day, day.plusDays(1));
    }

    /** {@code dayOf}, except that order {@code wrong} is put ten days later, outside its buyer's row. */
    private static Function<Long, Range> dayOfExcept(long wrong) {
        return order -> {
            Range day = dayOf(order);
            return order == wrong ? new Range(day.from().plusDays(10), day.to().plusDays(10)) : day;
        };
    }

    // ---- a to-many child: customers and their orders, bounded from below on placedAt

    /** Customers {@code first} to {@code last} and their orders placed no earlier than their group's value. */
    private static ModelQuery<CustomerEntity, Long, StampedCustomer> customers(long first, long last,
            KeyGroups<?, ?> groups) {
        return QStampedCustomer.query().where(f -> f.gte(QStampedCustomer.ID, first).lte(QStampedCustomer.ID, last))
                .orderBy(QStampedCustomer.ID.asc())
                .fetch(FetchPlan.of(QStampedCustomer.ALL).child(QStampedCustomer.ORDERS,
                        FetchPlan.of(QStampedOrder.ALL), q -> q
                                .where(f -> f.compare(QStampedOrder.PLACED_AT, Op.GTE, Expr.param(PLACED_FROM)))
                                .keyGroups(groups)))
                .build();
    }

    /** The orders of customers {@code first} to {@code last} placed at or after {@code from(customer)}, by id. */
    private static Map<Long, List<Long>> ordersFrom(TckDatabase db, long first, long last,
            Function<Long, LocalDateTime> from) {
        Map<Long, List<Long>> all = grouped(db, "SELECT customer_id, id FROM orders WHERE customer_id BETWEEN "
                + first + " AND " + last + " ORDER BY id");
        var result = new LinkedHashMap<Long, List<Long>>();
        all.forEach((customer, orders) -> result.put(customer, orders.stream()
                .filter(order -> !BASE.plusMinutes(17 * order).isBefore(from.apply(customer))).toList()));
        return result;
    }

    private static Map<Long, List<Long>> loaded(List<StampedCustomer> customers) {
        var result = new LinkedHashMap<Long, List<Long>>();
        customers.forEach(customer -> result.put(customer.id(),
                customer.orders().stream().map(StampedOrder::id).toList()));
        return result;
    }

    @TckTest
    void ac_fch_19_a_child_load_over_three_groups_runs_one_round_per_group_with_its_values_bound(TckDatabase db) {
        // Customer c falls in group c % 3, bound from 10 days per group after the fixture's start: each group's
        // value excludes some orders, so every round's bound value shows in the children it returns.
        Function<Long, LocalDateTime> from = customer -> BASE.plusDays(10 * (customer % 3));
        var groups = KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind(PLACED_FROM, g -> BASE.plusDays(10 * g)).assumeInRange();
        Map<Long, List<Long>> expected = ordersFrom(db, 1, 9, from);

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CustomerEntity.class, executor ->
                assertThat(loaded(executor.list(customers(1, 9, groups), Limit.unlimited()))).isEqualTo(expected)));
        // The page, then one round of three keys per group, all one statement text.
        assertThat(sql).hasSize(4);
        assertThat(sql.subList(1, 4)).extracting(FetchTestSupport::binds).containsExactly(4L, 4L, 4L);
        assertThat(sql.subList(1, 4)).containsOnly(sql.get(1));
        assertThat(expected).as("a group's value excludes some orders").isNotEqualTo(ordersFrom(db, 1, 9, c -> BASE));
    }

    @TckTest
    void ac_fch_20_a_group_over_the_bind_limit_is_split_within_the_group_counting_its_binds(TckDatabase db) {
        // A bind limit of 4: the group's one bind leaves three for keys, so rounds take two. Customers 1 to 5 are one
        // group and 6 to 8 another: rounds of 2, 2 and 1 keys, then 2 and 1, each with the group's bind.
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 1024, 4)));
        var groups = KeyGroups.by(Long.class, (Long customer) -> customer <= 5 ? 0L : 1L)
                .bind(PLACED_FROM, g -> BASE).assumeInRange();
        Map<Long, List<Long>> expected = ordersFrom(db, 1, 8, customer -> BASE);

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CustomerEntity.class, config, executor ->
                assertThat(loaded(executor.list(customers(1, 8, groups), Limit.unlimited()))).isEqualTo(expected)));
        assertThat(sql.subList(1, sql.size())).extracting(FetchTestSupport::binds).containsExactly(3L, 3L, 2L, 3L, 2L);
    }

    @TckTest
    void ac_fch_21_definition_refusals_precede_any_statement_even_on_an_empty_page(TckDatabase db) {
        var strings = KeyGroups.by(String.class, (String key) -> key).bind(PLACED_FROM, g -> BASE).assumeInRange();
        var unbound = QueryParam.of("placedTo", LocalDateTime.class);
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CustomerEntity.class, executor -> {
            assertThatThrownBy(() -> customers(1, 0, strings))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1708));
            assertThatThrownBy(() -> FetchPlan.of(QStampedCustomer.ALL).child(QStampedCustomer.ORDERS,
                    FetchPlan.of(QStampedOrder.ALL), q -> q
                            .where(f -> f.compare(QStampedOrder.PLACED_AT, Op.GTE, Expr.param(PLACED_FROM))
                                    .compare(QStampedOrder.PLACED_AT, Op.LT, Expr.param(unbound)))
                            .keyGroups(KeyGroups.by(Long.class, (Long c) -> c).bind(PLACED_FROM, g -> BASE)
                                    .assumeInRange())))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1512));
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_fch_22_group_and_value_refusals_follow_the_parent_statement_and_precede_any_child_round(
            TckDatabase db) {
        var nullGroup = KeyGroups.by(Long.class, (Long customer) -> customer == 3 ? null : customer % 3)
                .bind(PLACED_FROM, g -> BASE).assumeInRange();
        // The value of group 2, the second group seen, is null: no round of group 1 runs before it.
        var nullValue = KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind(PLACED_FROM, g -> g == 2 ? null : BASE).assumeInRange();
        @SuppressWarnings({"unchecked", "rawtypes"})
        KeyGroups<Long, Long> mistyped = KeyGroups.by(Long.class, (Long customer) -> customer % 3)
                .bind((QueryParam) PLACED_FROM, (Function<Long, Object>) g -> "2020-01-01").assumeInRange();
        var boom = new IllegalStateException("boom");
        var throwing = KeyGroups.by(Long.class, (Long customer) -> {
            if (customer == 4) {
                throw boom;
            }
            return customer % 3;
        }).bind(PLACED_FROM, g -> BASE).assumeInRange();

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CustomerEntity.class, executor -> {
            assertThatThrownBy(() -> executor.list(customers(1, 9, nullGroup), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2607))
                    .hasMessageContaining("StampedCustomer.orders").hasMessageContaining("key 3");
            assertThatThrownBy(() -> executor.list(customers(1, 9, nullValue), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("placedFrom: LocalDateTime").hasMessageContaining("the group of key 2");
            assertThatThrownBy(() -> executor.list(customers(1, 9, mistyped), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("is a String");
            assertThatThrownBy(() -> executor.list(customers(1, 9, throwing), Limit.unlimited())).isSameAs(boom);
        }));
        // Each call ran its parent statement, then no child round.
        assertThat(sql).hasSize(4);
    }

    @TckTest
    void ac_fch_23_a_to_many_child_bounded_from_below_loads_a_child_of_a_later_month(TckDatabase db) {
        // Customers 740 to 743 are created in January 2020 and 744 to 750 in February: two monthly groups, each bound
        // from its month's start. A January customer's orders run into February and all load; February's bound
        // excludes the February customers' orders placed in January.
        Function<Long, LocalDateTime> month = customer -> BASE.plusHours(customer).toLocalDate().withDayOfMonth(1)
                .atStartOfDay();
        var byMonth = KeyGroups.by(Long.class, month).bind(PLACED_FROM, from -> from).assumeInRange();
        Map<Long, List<Long>> expected = ordersFrom(db, 740, 750, month);
        Map<Long, List<Long>> all = ordersFrom(db, 740, 750, customer -> BASE);

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CustomerEntity.class, executor -> {
            List<StampedCustomer> customers = executor.list(customers(740, 750, byMonth), Limit.unlimited());
            assertThat(loaded(customers)).isEqualTo(expected);
            assertThat(customers).filteredOn(customer -> customer.id() <= 743)
                    .flatExtracting(StampedCustomer::orders)
                    .anySatisfy(order -> assertThat(order.placedAt().getMonthValue()).isEqualTo(2));
        }));
        assertThat(sql).as("the page, then one round per month").hasSize(3);
        assertThat(LongStream.rangeClosed(740, 743).mapToObj(expected::get))
                .as("a January customer's list is complete").containsExactlyElementsOf(
                        LongStream.rangeClosed(740, 743).mapToObj(all::get).toList());
        assertThat(LongStream.rangeClosed(744, 750)
                .filter(customer -> expected.get(customer).size() < all.get(customer).size()))
                .as("February's bound excludes earlier orders").isNotEmpty();
    }

    // ---- a through child: orders and their buyer, bounded on both sides of createdAt

    /** Orders 1 to {@code last} and their buyer, created in the range of the order's group. */
    private static ModelQuery<OrderEntity, Long, OrderStampedBuyer> orders(long last, KeyGroups<Long, Range> groups) {
        return orders(1, last, groups);
    }

    /** Orders {@code first} to {@code last} and their buyer, created in the range of the order's group. */
    private static ModelQuery<OrderEntity, Long, OrderStampedBuyer> orders(long first, long last,
            KeyGroups<Long, Range> groups) {
        return QOrderStampedBuyer.query()
                .where(f -> f.gte(QOrderStampedBuyer.ID, first).lte(QOrderStampedBuyer.ID, last))
                .orderBy(QOrderStampedBuyer.ID.asc())
                .fetch(FetchPlan.of(QOrderStampedBuyer.ALL).child(QOrderStampedBuyer.BUYER,
                        FetchPlan.of(QStampedBuyer.ALL), q -> q
                                .where(f -> f.compare(QStampedBuyer.CREATED_AT, Op.GTE, Expr.param(CREATED_FROM))
                                        .compare(QStampedBuyer.CREATED_AT, Op.LT, Expr.param(CREATED_TO)))
                                .keyGroups(groups)))
                .build();
    }

    private static KeyGroups.Builder<Long, Range> byDay(Function<Long, Range> day) {
        return KeyGroups.by(Long.class, day).bind(CREATED_FROM, Range::from).bind(CREATED_TO, Range::to);
    }

    /** Each order's buyer id, or 0 for none. */
    private static List<Long> buyers(List<OrderStampedBuyer> orders) {
        return orders.stream().map(order -> order.buyer().map(StampedBuyer::id).orElse(0L)).toList();
    }

    private static List<Long> buyersOf(long last) {
        var result = new ArrayList<Long>();
        for (long order = 1; order <= last; order++) {
            result.add(customerOf(order));
        }
        return result;
    }

    @TckTest
    void ac_fch_24_a_through_child_with_verify_missing_reads_a_row_outside_its_group_again_and_warns_once(
            TckDatabase db) {
        // Orders 1 to 4 have buyers created on four different days; order 2 is put on the wrong day.
        var q = orders(4, byDay(dayOfExcept(2)).verifyMissing(ALL));
        capturing(warnings -> {
            List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
                assertThat(buyers(executor.list(q, Limit.unlimited()))).containsExactlyElementsOf(buyersOf(4));
                assertThat(buyers(executor.list(q, Limit.unlimited()))).containsExactlyElementsOf(buyersOf(4));
            }));
            // Per call: the page, a round per group and the re-read of order 2's one key.
            assertThat(sql).hasSize(12);
            assertThat(sql.get(5)).isEqualTo(sql.get(1)); // the re-read's statement text is a one-key round's
            assertThat(warnings).hasSize(1).first().asString().startsWith("MQ2608: OrderStampedBuyer.buyer")
                    .contains("key 2").doesNotContain("2020-");
        });

        // When every key finds its row, no re-read runs.
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor ->
                assertThat(buyers(executor.list(orders(4, byDay(KeyGroupChildTest::dayOf).verifyMissing(ALL)),
                        Limit.unlimited()))).containsExactlyElementsOf(buyersOf(4))));
        assertThat(sql).hasSize(5);
    }

    @TckTest
    void ac_fch_24_mq2608_names_a_key_over_a_thousand_without_locale_grouping(TckDatabase db) {
        var q = orders(4321, 4321, byDay(dayOfExcept(4321)).verifyMissing(ALL));
        capturing(warnings -> {
            SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor ->
                    assertThat(buyers(executor.list(q, Limit.unlimited()))).containsExactly(customerOf(4321))));
            assertThat(warnings).hasSize(1).first().asString().contains("key 4321").doesNotContain("4,321")
                    .doesNotContain("4.321");
        });
    }

    @TckTest
    void ac_fch_25_group_when_false_runs_the_ungrouped_chunks_with_the_catch_all_and_true_runs_per_group(
            TckDatabase db) {
        var answer = new boolean[1];
        var asked = new ArrayList<String>();
        GroupingRule rule = (keys, groups) -> {
            asked.add(keys + "/" + groups);
            return answer[0];
        };
        // Order 2 is put on the wrong day, which under assumeInRange() only the false branch reads.
        var q = orders(4, byDay(dayOfExcept(2)).groupWhen(rule, ALL).assumeInRange());
        List<Long> outside = new ArrayList<>(buyersOf(4));
        outside.set(1, 0L);

        List<String> ungrouped = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor ->
                assertThat(buyers(executor.list(q, Limit.unlimited()))).containsExactlyElementsOf(buyersOf(4))));
        answer[0] = true;
        List<String> grouped = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor ->
                assertThat(buyers(executor.list(q, Limit.unlimited()))).containsExactlyElementsOf(outside)));
        assertThat(ungrouped).hasSize(2); // the page and one chunk of four keys, no re-read
        assertThat(grouped).hasSize(5); // the page and one round per group
        assertThat(asked).containsExactly("4/4", "4/4");
        // One statement text for both branches: the catch-all's two bounds and four keys, or a group's and one key.
        assertThat(ungrouped.subList(1, 2)).extracting(FetchTestSupport::binds).containsExactly(6L);
        assertThat(grouped.subList(1, 5)).extracting(FetchTestSupport::binds).containsOnly(3L);
    }

    @TckTest
    void ac_fch_25_the_rule_is_not_asked_for_one_group_equal_round_counts_or_no_keys(TckDatabase db) {
        var asked = new AtomicInteger();
        GroupingRule rule = (keys, groups) -> {
            asked.incrementAndGet();
            return false;
        };
        var oneGroup = byDay(order -> ALL).groupWhen(rule, ALL).assumeInRange();
        var daily = byDay(KeyGroupChildTest::dayOf).groupWhen(rule, ALL).assumeInRange();
        // An IN list of one key: four groups of one key take as many rounds as four ungrouped chunks.
        var oneKey = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 1)));

        SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
            assertThat(buyers(executor.list(orders(4, oneGroup), Limit.unlimited())))
                    .containsExactlyElementsOf(buyersOf(4));
            assertThat(executor.list(QOrderStampedBuyer.query().where(f -> f.gt(QOrderStampedBuyer.ID,
                    (long) TckFixture.ORDERS)).fetch(orders(4, daily).fetch().orElseThrow()).build(),
                    Limit.unlimited())).isEmpty();
        }));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, oneKey, executor ->
                assertThat(buyers(executor.list(orders(4, daily), Limit.unlimited())))
                        .containsExactlyElementsOf(buyersOf(4))));
        assertThat(sql).hasSize(5);
        assertThat(asked).hasValue(0);
    }

    @TckTest
    void ac_fch_25_a_null_value_is_mq1502_on_either_branch_and_the_rule_exception_propagates_before_any_round(
            TckDatabase db) {
        var boom = new IllegalStateException("rule");
        GroupingRule failing = (keys, groups) -> {
            throw boom;
        };
        var asked = new AtomicInteger();
        // The third order's group binds a null upper bound. Every value runs before the rule is asked, so whichever
        // branch the rule would take, the call is refused without asking it.
        Function<Long, Range> nullOnThird = order -> order == 3 ? new Range(dayOf(order).from(), null) : dayOf(order);
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
            for (boolean answer : new boolean[] {false, true}) {
                GroupingRule rule = (keys, groups) -> {
                    asked.incrementAndGet();
                    return answer;
                };
                var q = orders(4, byDay(nullOnThird).groupWhen(rule, ALL).assumeInRange());
                assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                        .hasMessageContaining("createdTo: LocalDateTime");
            }
            // The same four groups of one key without the null value do ask the rule.
            assertThatThrownBy(() -> executor.list(orders(4, byDay(KeyGroupChildTest::dayOf).groupWhen(failing, ALL)
                    .assumeInRange()), Limit.unlimited())).isSameAs(boom);
        }));
        assertThat(asked).as("the value refusal precedes the rule on either branch").hasValue(0);
        assertThat(sql).as("each call's parent statement, then no child round").hasSize(3);
    }

    /** Runs {@code work} with the executor's {@code MQ2608} warnings collected, each formatted. */
    private static void capturing(Consumer<List<String>> work) {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("com.rey.modelquery.jpa.DefaultModelQueryExecutor");
        Level previous = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = new SimpleFormatter().formatMessage(record);
                if (message.startsWith("MQ2608:")) {
                    assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                    warnings.add(message);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.WARNING);
        logger.addHandler(handler);
        try {
            work.accept(warnings);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }
}
