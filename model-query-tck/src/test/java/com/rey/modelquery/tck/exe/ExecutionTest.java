package com.rey.modelquery.tck.exe;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** {@code list}, {@code page}, {@code count} and {@code stream} of the executor (engine/20). */
class ExecutionTest {

    record OrderRow(Long id, String status, BigDecimal total) {}

    record ItemRow(Long id, String product) {}

    record Group(Long customerId, BigDecimal total) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);

    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<OrderRow, OrderItemEntity, String> ITEM_PRODUCT_FILTER =
            ColumnField.of(OrderRow.class, ITEMS, "productCode", String.class);

    private static final ColumnField<ItemRow, OrderEntity, Long> ITEM_ORDER_ID =
            ColumnField.of(ItemRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(ItemRow.class, ITEMS, "productCode", String.class);

    private static final ColumnField<Group, CustomerEntity, Long> GROUP_CUSTOMER =
            ColumnField.of(Group.class, CUSTOMER, "id", Long.class);
    private static final ColumnField<Group, OrderEntity, BigDecimal> GROUP_TOTAL_COLUMN =
            ColumnField.of(Group.class, ORDERS, "total", BigDecimal.class);
    private static final AggregateField<Group, BigDecimal> GROUP_TOTAL = Agg.sum(GROUP_TOTAL_COLUMN);

    /** Every order, in id order. */
    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ORDER_ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
            .columns(ColumnSet.of(ID, STATUS, TOTAL))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());

    private static final ModelQuery<OrderEntity, ?, OrderRow> NEW_ORDERS =
            ORDER_ROWS.where(f -> f.eq(STATUS, Optional.of("NEW"))).build();

    // ---- AC-EXE-01

    @TckTest
    void ac_exe_01_a_zero_limit_runs_no_query(TckDatabase db) {
        List<List<OrderRow>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-01-zero-limit",
                ds -> withExecutor(ds, executor -> results.add(executor.list(NEW_ORDERS, Limit.of(0)))));
        assertThat(sql).isEmpty();
        assertThat(results).containsExactly(List.of());
    }

    @TckTest
    void ac_exe_01_list_applies_the_limit_and_unlimited_applies_none(TckDatabase db) {
        withExecutor(db, executor -> {
            List<OrderRow> five = executor.list(ORDER_ROWS.build(), Limit.of(5));
            assertThat(five).extracting(OrderRow::id).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(executor.list(ORDER_ROWS.build(), Limit.unlimited())).hasSize(TckFixture.ORDERS);
            assertThat(executor.list(NEW_ORDERS, Limit.unlimited())).hasSize(TckFixture.ORDERS / 4);
        });
    }

    @TckTest
    void ac_exe_01_no_count_page_fetches_one_row_beyond_the_page(TckDatabase db) {
        List<Slice<OrderRow>> slices = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-01-page-probe", ds -> withExecutor(ds,
                executor -> slices.add(executor.page(NEW_ORDERS, PageSpec.of(2, 10), CountMode.NO_COUNT))));
        assertThat(sql).hasSize(1);
        assertThat(slices.get(0).content()).hasSize(10);
        assertThat(slices.get(0).hasNext()).isTrue();
    }

    // ---- AC-EXE-02

    @TckTest
    void ac_exe_02_no_count_reports_has_next_on_an_exact_multiple_of_the_page_size(TckDatabase db) {
        int total = TckFixture.ORDERS / 4; // 1 250 NEW orders, five pages of 250
        withExecutor(db, executor -> {
            for (int page = 0; page < 5; page++) {
                Slice<OrderRow> slice = executor.page(NEW_ORDERS, PageSpec.of(page, 250), CountMode.NO_COUNT);
                assertThat(slice.content()).as("page %d", page).hasSize(250);
                assertThat(slice.hasNext()).as("hasNext of page %d", page).isEqualTo(page < 4);
                assertThat(slice.total()).isEmpty();
                assertThat(slice.pageNumber()).isEqualTo(page);
                assertThat(slice.pageSize()).isEqualTo(250);
            }
            Slice<OrderRow> beyond = executor.page(NEW_ORDERS, PageSpec.of(5, 250), CountMode.NO_COUNT);
            assertThat(beyond.content()).isEmpty();
            assertThat(beyond.hasNext()).isFalse();
            // 250 rows remain from the last page's offset, so a page of 249 there has one more row to report.
            assertThat(executor.page(NEW_ORDERS, new PageSpec(total - 250, 249), CountMode.NO_COUNT).hasNext())
                    .isTrue();
        });
    }

    @TckTest
    void ac_exe_02_count_and_only_count_report_an_exact_total(TckDatabase db) {
        int total = TckFixture.ORDERS / 4;
        withExecutor(db, executor -> {
            Slice<OrderRow> counted = executor.page(NEW_ORDERS, PageSpec.of(4, 250), CountMode.COUNT);
            assertThat(counted.content()).hasSize(250);
            assertThat(counted.total()).hasValue(total);
            assertThat(counted.hasNext()).isFalse();
            assertThat(executor.page(NEW_ORDERS, PageSpec.of(3, 250), CountMode.COUNT).hasNext()).isTrue();

            Slice<OrderRow> beyond = executor.page(NEW_ORDERS, PageSpec.of(9, 250), CountMode.COUNT);
            assertThat(beyond.content()).isEmpty();
            assertThat(beyond.total()).hasValue(total);

            Slice<OrderRow> only = executor.page(NEW_ORDERS, PageSpec.of(1, 250), CountMode.ONLY_COUNT);
            assertThat(only.content()).isEmpty();
            assertThat(only.total()).hasValue(total);
            assertThat(only.hasNext()).isTrue();
            assertThat(only.pageNumber()).isEqualTo(1);
            assertThat(only.pageSize()).isEqualTo(250);
        });
    }

    @TckTest
    void page_closes_a_tied_order_with_the_primary_key(TckDatabase db) {
        // Four statuses over 5 000 orders: every sort key is shared by 1 250 rows.
        var tied = ModelQuery.builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
                .columns(ColumnSet.of(ID, STATUS, TOTAL))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(STATUS.asc())
                .build();
        withExecutor(db, executor -> {
            List<Long> visited = new ArrayList<>();
            for (int page = 0; page < 50; page++) {
                executor.page(tied, PageSpec.of(page, 100), CountMode.NO_COUNT).content()
                        .forEach(row -> visited.add(row.id()));
            }
            assertThat(visited).hasSize(TckFixture.ORDERS);
            assertThat(new HashSet<>(visited)).hasSize(TckFixture.ORDERS);
        });
    }

    // ---- AC-EXE-03

    private static final ModelQuery.Builder<OrderEntity, Object, Group> PER_CUSTOMER = ModelQuery
            .builder(ORDERS, (com.rey.modelquery.core.Row row) -> new Group(row.get(GROUP_CUSTOMER), row.get(GROUP_TOTAL)))
            .columns(ColumnSet.of(GROUP_CUSTOMER, GROUP_TOTAL))
            .groupBy(GROUP_CUSTOMER)
            .orderBy(GROUP_CUSTOMER.asc());

    @TckTest
    void ac_exe_03_grouped_count_equals_the_number_of_groups_with_hibernate(TckDatabase db) {
        List<Long> counts = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-03-grouped-count-hibernate", ds -> withExecutor(ds,
                executor -> {
                    counts.add(executor.count(PER_CUSTOMER.build()));
                    counts.add(executor.count(PER_CUSTOMER.having(h -> h.gt(GROUP_TOTAL, new BigDecimal("2500"))).build()));
                }));
        assertThat(sql).hasSize(2).allMatch(s -> s.startsWith("select count(*) from (select "));
        assertThat(counts).containsExactly(groups(db, false), groups(db, true));
        assertThat(counts.get(0)).isEqualTo(TckFixture.CUSTOMERS);
        assertThat(counts.get(1)).isPositive().isLessThan(counts.get(0));
    }

    @TckTest
    void ac_exe_03_grouped_count_equals_the_number_of_groups_without_hibernate(TckDatabase db) {
        List<Long> counts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-03-grouped-count-portable", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> JoinTestSupport.withoutServices(() -> {
                    ModelQueryExecutor<OrderEntity> executor =
                            ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                    warnings.addAll(capturingWarnings(() -> {
                        counts.add(executor.count(PER_CUSTOMER.build()));
                        counts.add(executor.count(
                                PER_CUSTOMER.having(h -> h.gt(GROUP_TOTAL, new BigDecimal("2500"))).build()));
                    }));
                }));
            }
        });
        assertThat(sql).hasSize(2).noneMatch(s -> s.contains("count(*)"));
        assertThat(counts).containsExactly(groups(db, false), groups(db, true));
        assertThat(warnings).hasSize(2).allMatch(w -> w.contains("counts its rows in memory"));
    }

    /** The reference count, from JPQL run directly. */
    private static long groups(TckDatabase db, boolean having) {
        long[] count = new long[1];
        inSession(db, em -> count[0] = em.createQuery("select o.customer.id from OrderEntity o group by o.customer.id"
                + (having ? " having sum(o.total) > 2500" : ""), Long.class).getResultList().size());
        return count[0];
    }

    // ---- AC-EXE-04

    @TckTest
    void ac_exe_04_count_over_a_to_many_join_equals_the_distinct_roots(TckDatabase db) {
        var withItem = ORDER_ROWS.where(f -> f.eq(ITEM_PRODUCT_FILTER, Optional.of("P001"))).build();
        List<Long> counts = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-04-distinct-roots",
                ds -> withExecutor(ds, executor -> counts.add(executor.count(withItem))));
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("count(distinct");

        long[] expected = new long[2];
        inSession(db, em -> {
            expected[0] = em.createQuery("select count(distinct o) from OrderEntity o join o.items i"
                    + " where i.productCode = 'P001'", Long.class).getSingleResult();
            expected[1] = em.createQuery("select count(o) from OrderEntity o join o.items i"
                    + " where i.productCode = 'P001'", Long.class).getSingleResult();
        });
        assertThat(counts).containsExactly(expected[0]);
        assertThat(expected[0]).isPositive().isLessThan(expected[1]); // the join does repeat the roots
    }

    // ---- AC-EXE-10

    @TckTest
    void ac_exe_10_count_over_a_column_read_through_a_to_many_join_equals_the_rows_list_returns(TckDatabase db) {
        var itemRows = ModelQuery.builder(ORDERS, row -> new ItemRow(row.get(ITEM_ORDER_ID), row.get(ITEM_PRODUCT)))
                .columns(ColumnSet.of(ITEM_ORDER_ID, ITEM_PRODUCT))
                .build();
        var filtered = ModelQuery.builder(ORDERS, row -> new ItemRow(row.get(ITEM_ORDER_ID), row.get(ITEM_PRODUCT)))
                .columns(ColumnSet.of(ITEM_ORDER_ID, ITEM_PRODUCT))
                .where(f -> f.eq(ITEM_PRODUCT, Optional.of("P001")))
                .orderBy(ITEM_PRODUCT.asc())
                .build();
        List<Long> counts = new ArrayList<>();
        List<Integer> listed = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-10-rows", ds -> withExecutor(ds, executor -> {
            counts.add(executor.count(itemRows));
            counts.add(executor.count(filtered));
        }));
        assertThat(sql).hasSize(2).noneMatch(s -> s.contains("distinct"));
        withExecutor(db, executor -> {
            listed.add(executor.list(itemRows, Limit.unlimited()).size());
            listed.add(executor.list(filtered, Limit.unlimited()).size());
        });
        assertThat(counts).containsExactly((long) TckFixture.ORDER_ITEMS, (long) TckFixture.ORDER_ITEMS / 50);
        assertThat(counts).containsExactly(listed.get(0).longValue(), listed.get(1).longValue());
    }

    // ---- AC-EXE-05

    @TckTest
    void ac_exe_05_a_page_size_that_is_not_positive_throws_mq2001(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-05-invalid-page", ds -> withExecutor(ds, executor -> {
            for (int size : new int[] {0, -1}) {
                assertThatThrownBy(() -> executor.page(NEW_ORDERS, PageSpec.of(0, size), CountMode.COUNT))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2001))
                        .hasMessageStartingWith(MqCode.MQ2001.code() + ":");
                assertThatThrownBy(() -> executor.page(NEW_ORDERS, new PageSpec(0, size), CountMode.NO_COUNT))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2001));
            }
            assertThatThrownBy(() -> Limit.of(-1)).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ2001));
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_exe_05_a_negative_offset_throws_mq2002(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-05-negative-offset", ds -> withExecutor(ds, executor -> {
            assertThatThrownBy(() -> executor.page(NEW_ORDERS, new PageSpec(-1, 10), CountMode.COUNT))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2002))
                    .hasMessageStartingWith(MqCode.MQ2002.code() + ":");
            assertThatThrownBy(() -> executor.page(NEW_ORDERS, PageSpec.of(-1, 10), CountMode.COUNT))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2002));
        }));
        assertThat(sql).isEmpty();
    }

    // ---- AC-EXE-06, AC-EXE-07

    @TckTest
    void ac_exe_06_streaming_20_000_rows_maps_them_one_at_a_time(TckDatabase db) {
        // The mapper counts the models the engine has built. An engine that buffered (mapAll into a list, then
        // list.stream()) would have built all 20 000 before body sees the first, so the bound below would fail; a
        // lazy stream has built the first one or two, so the live models never exceed a small constant. The
        // assertion is on the engine's own buffering: a driver that buffers its rows (PostgreSQL outside a
        // transaction, MySQL without a streaming fetch size) does not change how many models are mapped, and the
        // vendor-side streaming setup is M3's (R-EXE-08).
        AtomicLong mapped = new AtomicLong();
        AtomicLong consumed = new AtomicLong();
        AtomicLong maxAhead = new AtomicLong();
        var itemRows = ModelQuery.builder(ORDERS, row -> {
                    mapped.incrementAndGet();
                    return new ItemRow(row.get(ITEM_ORDER_ID), row.get(ITEM_PRODUCT));
                })
                .columns(ColumnSet.of(ITEM_ORDER_ID, ITEM_PRODUCT))
                .build();
        withExecutor(db, executor -> {
            // forEach, not count(): count() skips a SIZED stream's pipeline, which is what a buffered list would be
            long total = executor.stream(itemRows, Limit.unlimited(), rows -> {
                rows.forEach(row -> {
                    consumed.incrementAndGet();
                    maxAhead.accumulateAndGet(mapped.get() - consumed.get(), Math::max);
                });
                return consumed.get();
            });
            assertThat(total).isEqualTo(TckFixture.ORDER_ITEMS);
        });
        assertThat(mapped).hasValue(TckFixture.ORDER_ITEMS);
        assertThat(maxAhead.get()).as("models built ahead of the one body is reading").isLessThanOrEqualTo(2);
    }

    @TckTest
    void ac_exe_06_a_zero_limit_streams_nothing_and_runs_no_query(TckDatabase db) {
        List<Long> counts = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-06-zero-limit", ds -> withExecutor(ds,
                executor -> counts.add(executor.stream(NEW_ORDERS, Limit.of(0), Stream::count))));
        assertThat(sql).isEmpty();
        assertThat(counts).containsExactly(0L);
    }

    @TckTest
    void ac_exe_06_stream_applies_the_limit_and_keeps_the_order(TckDatabase db) {
        withExecutor(db, executor -> {
            List<Long> ids = executor.stream(ORDER_ROWS.build(), Limit.of(5),
                    rows -> rows.map(OrderRow::id).toList());
            assertThat(ids).containsExactly(1L, 2L, 3L, 4L, 5L);
        });
    }

    @TckTest
    void ac_exe_07_an_early_exit_from_body_releases_the_connection(TckDatabase db) {
        // A leak check that cannot pass by accident: the control opens a stream through JPA and never closes it, and
        // the pool's active count must show it (the session releases a connection as soon as no result set is open); then the same count must return to zero after every way body can end.
        try (SessionFactory sf = JoinTestSupport.sessionFactoryReleasingAfterStatement(countingPool(db, ACTIVE))) {
            ACTIVE.set(0);
            sf.inSession(em -> {
                Stream<?> leaked = em.createQuery("select o from OrderEntity o", OrderEntity.class).getResultStream();
                assertThat(ACTIVE.get()).as("control: an unclosed stream holds a connection").isPositive();
                leaked.close();
            });
            assertThat(ACTIVE.get()).as("control: closing the stream releases it").isZero();

            var rows = ORDER_ROWS.build();
            sf.inSession(em -> {
                ModelQueryExecutor<OrderEntity> executor =
                        ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                // findFirst stops after one row of 5 000
                Optional<OrderRow> first = executor.<OrderRow, Optional<OrderRow>>stream(rows, Limit.unlimited(), Stream::findFirst);
                assertThat(first).isPresent();
                assertThat(ACTIVE.get()).as("after findFirst").isZero();
                // an exception thrown from body
                assertThatThrownBy(() -> executor.stream(rows, Limit.unlimited(), s -> {
                    s.limit(3).forEach(r -> {});
                    throw new IllegalStateException("boom");
                })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
                assertThat(ACTIVE.get()).as("after an exception").isZero();
                // body that never touches the stream
                String untouched = executor.stream(rows, Limit.unlimited(), s -> "untouched");
                assertThat(untouched).isEqualTo("untouched");
                assertThat(ACTIVE.get()).as("after ignoring the stream").isZero();
                // a full pass
                long all = executor.stream(rows, Limit.unlimited(), Stream::count);
                assertThat(all).isEqualTo(TckFixture.ORDERS);
                assertThat(ACTIVE.get()).as("after a full pass").isZero();
            });
        }
    }

    private static final AtomicInteger ACTIVE = new AtomicInteger();

    /** A DataSource whose connections count themselves in {@code active} from opening to closing. */
    private static DataSource countingPool(TckDatabase db, AtomicInteger active) {
        return (DataSource) Proxy.newProxyInstance(ExecutionTest.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("toString") ? "counting pool of " + db
                                : method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                : proxy == args[0];
                    }
                    if (!method.getName().equals("getConnection")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Connection connection = db.getConnection();
                    active.incrementAndGet();
                    AtomicInteger closed = new AtomicInteger();
                    return Proxy.newProxyInstance(ExecutionTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (c, m, a) -> {
                                if (m.getName().equals("close") && closed.getAndIncrement() == 0) {
                                    active.decrementAndGet();
                                }
                                try {
                                    return m.invoke(connection, a);
                                } catch (java.lang.reflect.InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                });
    }

    // ---- support

    private static void withExecutor(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        inSession(db, em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        inSession(ds, em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
    }

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }

    private static void inSession(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(work::accept);
        }
    }

    private static List<String> capturingWarnings(Runnable work) {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("com.rey.modelquery.jpa.DefaultModelQueryExecutor");
        Level level = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING) {
                    warnings.add(new SimpleFormatter().formatMessage(r));
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
            work.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
        return warnings;
    }
}
