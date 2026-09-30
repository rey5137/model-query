package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.MysqlStreamingMode;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.QueryTimeoutException;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Streaming and the query timeout through the executor (engine/20 R-EXE-08, R-EXE-09; vendor/41 R-PRF-03..07,
 * R-PRF-07). The streaming tests prove the driver streams from what the connection does while the result is still
 * open, not from a heap measurement, which a garbage collection would make flaky. This package may name a vendor
 * (R-VND-04).
 */
class StreamingAndTimeoutTest {

    record ItemRow(Long orderId, String product) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS =
            TableField.join(ORDERS, "items", jakarta.persistence.criteria.JoinType.INNER);
    private static final ColumnField<ItemRow, OrderEntity, Long> ORDER_ID =
            ColumnField.of(ItemRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, String> PRODUCT =
            ColumnField.of(ItemRow.class, ITEMS, "productCode", String.class);

    /** One row per order item: {@link TckFixture#ORDER_ITEMS}, 20 000 rows. */
    private static final ModelQuery<OrderEntity, ?, ItemRow> ITEM_ROWS = ModelQuery
            .builder(ORDERS, row -> new ItemRow(row.get(ORDER_ID), row.get(PRODUCT)))
            .columns(ColumnSet.of(ORDER_ID, PRODUCT))
            .build();

    /** Orders x items x items with a predicate no row satisfies and no index answers: about 2e12 candidate rows. */
    private static final ModelQuery<OrderEntity, ?, ItemRow> NEVER_FINISHES = ModelQuery
            .builder(ORDERS, row -> new ItemRow(row.get(ORDER_ID), row.get(PRODUCT)))
            .columns(ColumnSet.of(ORDER_ID, PRODUCT))
            .customize((spec, joins, query, cb, phase) -> {
                Root<OrderItemEntity> a = query.from(OrderItemEntity.class);
                Root<OrderItemEntity> b = query.from(OrderItemEntity.class);
                Expression<Long> sum = cb.sum(cb.sum(a.<Long>get("id"), b.<Long>get("id")), 0L);
                query.where(cb.lt(sum, 0L));
            })
            .build();

    /** The portals pgjdbc opens by name (C_1, ...) when it reads by fetch size. */
    private static final String NAMED_PORTALS = "select count(*) from pg_cursors where name like 'C\\_%'";

    @TckTest
    void ac_exe_08_streaming_outside_a_transaction_throws_mq2101_on_postgresql_and_runs_no_statement(TckDatabase db) {
        List<String> statements = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                    if (db.vendor() == TckVendor.POSTGRESQL) {
                        assertThatThrownBy(() -> executor.stream(ITEM_ROWS, Limit.unlimited(), s -> s.count()))
                                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2101));
                    } else {
                        long streamed = executor.stream(ITEM_ROWS, Limit.of(10), s -> s.count());
                        assertThat(streamed).isEqualTo(10);
                    }
                });
            }
        });
        if (db.vendor() == TckVendor.POSTGRESQL) {
            assertThat(statements).as("statements run before the refusal").isEmpty();
        } else {
            assertThat(statements).isNotEmpty();
        }
    }

    @TckTest
    void ac_prf_04_postgresql_streams_inside_a_transaction_through_a_cursor(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            AtomicLong cursors = new AtomicLong(-1);
            long rows = sf.fromTransaction(em -> {
                var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                return executor.stream(ITEM_ROWS, Limit.unlimited(), stream -> stream.peek(row -> {
                    if (cursors.get() < 0) {
                        boolean postgres = db.vendor() == TckVendor.POSTGRESQL;
                        cursors.set(postgres ? count(em, NAMED_PORTALS) : 0);
                    }
                }).count());
            });
            assertThat(rows).isEqualTo(TckFixture.ORDER_ITEMS);
            if (db.vendor() == TckVendor.POSTGRESQL) {
                // pgjdbc names a portal (C_1) only when it reads by fetch size; otherwise it reads the result at once.
                assertThat(cursors.get()).as("open cursors while the first row is read").isPositive();
            }
        }
    }

    @TckTest
    void ac_prf_05_mysql_row_by_row_streams_20_000_rows_and_holds_the_connection(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            AtomicLong rows = new AtomicLong();
            AtomicLong refused = new AtomicLong();
            sf.inTransaction(em -> {
                var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                executor.stream(ITEM_ROWS, Limit.unlimited(), stream -> {
                    stream.forEach(row -> {
                        if (rows.incrementAndGet() == 1 && db.vendor() == TckVendor.MYSQL) {
                            refused.set(otherStatementFails(em) ? 1 : 0);
                        }
                    });
                    return null;
                });
            });
            assertThat(rows).hasValue(TckFixture.ORDER_ITEMS);
            if (db.vendor() == TckVendor.MYSQL) {
                // Connector/J refuses any other statement while a row-by-row result is open (R-PRF-04): only a
                // driver that streams does, one that had buffered the result would have run it.
                assertThat(refused).as("another statement while the result is open was refused").hasValue(1);
            }
        }
    }

    @TckTest
    void ac_prf_05_mysql_cursor_fetch_streams_20_000_rows_by_server_side_fetches(TckDatabase db) throws SQLException {
        TckDatabase cursorDb = db.vendor() == TckVendor.MYSQL ? db.withJdbcUrlProperty("useCursorFetch", "true") : db;
        ModelQueryConfig config = ModelQueryConfig.defaults().mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH);
        long before = db.vendor() == TckVendor.MYSQL ? statusCounter(db, "Com_stmt_fetch") : 0;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(cursorDb)) {
            AtomicLong rows = new AtomicLong();
            AtomicLong otherStatementRan = new AtomicLong();
            sf.inTransaction(em -> {
                var executor = ModelQueryExecutor.create(em, OrderEntity.class, config);
                executor.stream(ITEM_ROWS, Limit.unlimited(), stream -> {
                    stream.forEach(row -> {
                        if (rows.incrementAndGet() == 1 && db.vendor() == TckVendor.MYSQL) {
                            otherStatementRan.set(otherStatementFails(em) ? 0 : 1);
                        }
                    });
                    return null;
                });
            });
            assertThat(rows).hasValue(TckFixture.ORDER_ITEMS);
            if (db.vendor() == TckVendor.MYSQL) {
                assertThat(otherStatementRan).as("the connection stays usable between fetches").hasValue(1);
                // 20 000 rows at the fetch size of 500 take 40 COM_STMT_FETCH round trips; a buffered one takes none.
                assertThat(statusCounter(db, "Com_stmt_fetch") - before).isGreaterThanOrEqualTo(39);
            }
        }
    }

    @TckTest
    void ac_exe_09_a_query_slower_than_the_configured_timeout_is_cancelled(TckDatabase db) {
        ModelQueryConfig config = ModelQueryConfig.defaults().queryTimeout(Duration.ofSeconds(1));
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> sf.inSession(em -> ModelQueryExecutor.create(em, OrderEntity.class, config)
                    .list(NEVER_FINISHES, Limit.of(10)))).isInstanceOf(QueryTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
        }
    }

    @TckTest
    void ac_prf_08_the_timeout_also_cancels_a_streamed_query(TckDatabase db) {
        ModelQueryConfig config = ModelQueryConfig.defaults().queryTimeout(Duration.ofSeconds(1));
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            assertThatThrownBy(() -> sf.inTransaction(em -> ModelQueryExecutor.create(em, OrderEntity.class, config)
                    .stream(NEVER_FINISHES, Limit.unlimited(), s -> s.count())))
                    // The stream path leaves Hibernate's own exception, which the list path converts to JPA's.
                    .isInstanceOfAny(QueryTimeoutException.class, org.hibernate.QueryTimeoutException.class);
        }
    }

    private static long count(EntityManager em, String sql) {
        return em.unwrap(Session.class).doReturningWork(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    /** Whether a second statement on the session's own connection fails while a result of it is open. */
    private static boolean otherStatementFails(EntityManager em) {
        try {
            count(em, "select 1");
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static long statusCounter(TckDatabase db, String name) throws SQLException {
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("show global status like '" + name + "'")) {
            rs.next();
            return rs.getLong(2);
        }
    }
}
