package com.rey.modelquery.tck.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.RowLock.Wait;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.fch.Line;
import com.rey.modelquery.tck.fch.OrderLines;
import com.rey.modelquery.tck.fch.QLine;
import com.rey.modelquery.tck.fch.QOrderLines;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.VendorLocks;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Assumptions;

/**
 * The executor's locking reads against real databases: what Hibernate renders when the engine applies a lock
 * (engine/20 R-EXE-14, AC-EXE-16), the locks its provider refuses ({@code MQ4010}, vendor/40 R-VND-15, AC-VND-13), and
 * what two connections see of each other's lock (AC-EXE-17). The refusals that need no vendor are unit-tested in
 * {@code model-query-jpa} ({@code RowLockExecutionTest}); here the same reads run their statements on H2, PostgreSQL 17
 * and MySQL 8.4, both Hibernate lines, and are pinned as SQL snapshots.
 *
 * <p>The executor's own reads render the same statement on both Hibernate lines — its criteria column projection
 * carries no {@code OF <root>} list, and its locked reads take {@code WAIT} alone, which H2 renders on either line — so
 * one shared snapshot per vendor covers both; the {@code hibernate7/} {@code lock-render} snapshots pin the rows that
 * do differ. The provider's answer is per strength × wait, so the refusals are stated the same way:
 * {@link VendorLocks#refused(TckDatabase)} against {@link VendorLocks#rendered(TckDatabase)}.
 */
class RowLockExecutionTest {

    private static final RowLock LOCK = RowLock.exclusive();

    private static final FetchPlan<Line> LINE = FetchPlan.of(QLine.ALL);

    private static final TableField<TwoTableRoot, TwoTableRoot> TWO_TABLE_ROOT = TableField.root(TwoTableRoot.class);

    private static final ColumnField<RootRow, TwoTableRoot, Long> ROOT_ID =
            ColumnField.of(RootRow.class, TWO_TABLE_ROOT, "id", Long.class);

    record RootRow(Long id) {}

    // ---- AC-EXE-16, as SQL on every vendor

    /** The reads that lock: each renders one statement, and that statement carries the locking clause (R-EXE-14). */
    @TckTest
    void ac_exe_16_a_locked_read_renders_the_locking_clause_on_the_reads_that_take_it(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "lock-exec-reads", dataSource -> withExecutor(dataSource,
                executor -> {
                    assertThat(executor.list(locked(unordered(), LOCK), Limit.of(2))).hasSize(2);
                    assertThat(executor.first(locked(orders(), LOCK))).isPresent();
                    assertThat(executor.one(locked(unordered(), LOCK), 7L)).isPresent();
                }));
        assertThat(sql).hasSize(3).allSatisfy(statement ->
                assertThat(locked(statement)).as("locked statement, but: %s", statement).isTrue());
    }

    /** A locked {@code byKeys} orders each chunk's statement by primary key, so callers lock in one order. */
    @TckTest
    void ac_exe_16_locked_by_keys_orders_each_chunk_by_primary_key(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "lock-exec-by-keys", dataSource -> withExecutor(dataSource,
                executor -> assertThat(executor.byKeys(locked(unordered(), LOCK), List.of(7L, 3L, 5L)).keySet())
                        .containsExactly(7L, 3L, 5L)));
        assertThat(sql).hasSize(1);
        assertThat(locked(sql.get(0))).as("locked statement, but: %s", sql.get(0)).isTrue();
        assertThat(sql.get(0).toLowerCase(Locale.ROOT)).as("ordered by the primary key, but: %s", sql.get(0))
                .contains("order by").contains("id");
    }

    /** A fetch plan's own statements carry no lock: only the statement returning the model's rows is locked. */
    @TckTest
    void ac_exe_16_a_locked_read_leaves_its_fetch_plan_statements_unlocked(TckDatabase db) {
        ModelQuery<OrderEntity, Long, OrderLines> planned = QOrderLines.query().select(QOrderLines.ALL)
                .where(f -> f.in(QOrderLines.ID, List.of(1L, 2L, 3L)))
                .fetch(FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS, LINE))
                .build()
                .withLock(LOCK);
        List<String> sql = SqlSnapshots.assertMatches(db, "lock-exec-fetch", dataSource -> withExecutor(dataSource,
                executor -> assertThat(executor.list(planned, Limit.unlimited())).hasSize(3)));
        // The model's own statement, then the plan's child load for those three keys.
        assertThat(sql).hasSize(2);
        assertThat(locked(sql.get(0))).as("the model's statement is locked, but: %s", sql.get(0)).isTrue();
        assertThat(locked(sql.get(1))).as("the plan's statement is not, but: %s", sql.get(1)).isFalse();
    }

    /** A query whose statement joins is {@code MQ2211} before any statement: a projection gives no lock root. */
    @TckTest
    void ac_exe_16_a_joined_query_is_refused_with_mq2211(TckDatabase db) {
        ModelQuery<OrderEntity, Long, OrderLines> joined = QOrderLines.query().select(QOrderLines.ALL)
                .where(f -> f.eq(QOrderLines.ITEM_CODE, "P001"))
                .build()
                .withLock(LOCK);
        withExecutor(db, executor -> assertMq(() -> executor.list(joined, Limit.unlimited()), MqCode.MQ2211,
                "cannot lock a query whose statement has a join"));
    }

    // ---- AC-VND-13: the locks the provider refuses

    /** Every lock the provider would drop or widen is {@code MQ4010} before any statement; the rest run. */
    @TckTest
    void ac_vnd_13_a_wait_the_provider_would_drop_is_refused_with_mq4010(TckDatabase db) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(db)) {
            sessions.inTransaction(em -> {
                ModelQueryExecutor<OrderEntity> executor = executor(em);
                for (RowLock lock : VendorLocks.refused(db)) {
                    assertMq(() -> executor.first(locked(orders(), lock)), MqCode.MQ4010,
                            "cannot lock " + lock + " on " + db.target().displayName() + ", because provider "
                                    + "HibernateLockSupport would not render it");
                }
                for (RowLock lock : VendorLocks.rendered(db)) {
                    assertThat(executor.first(locked(orders(), lock)))
                            .as("%s renders on %s (spec vendor/41 §6)", lock, db.target().displayName())
                            .isPresent();
                }
            });
        }
    }

    /** A root on a secondary table too is {@code MQ4010}: only the root table would be locked (R-VND-13). */
    @TckTest
    void ac_vnd_13_a_root_on_several_tables_is_refused_with_mq4010(TckDatabase db) {
        try (SessionFactory sessions = twoTableFactory(db)) {
            sessions.inTransaction(em -> {
                em.persist(new TwoTableRoot(1L, "detail"));
                ModelQueryExecutor<TwoTableRoot> executor =
                        ModelQueryExecutor.create(em, TwoTableRoot.class, ModelQueryConfig.defaults());
                assertThat(executor.list(twoTableRows().build(), Limit.unlimited())).hasSize(1);
                assertMq(() -> executor.list(twoTableRows().build().withLock(LOCK), Limit.unlimited()), MqCode.MQ4010,
                        "is mapped to more than one table");
            });
        }
    }

    // ---- AC-EXE-17: two connections on PostgreSQL and MySQL

    /** A second reader's {@code WAIT} read stays blocked until the holder commits, then reads the locked row. */
    @TckTest
    void ac_exe_17_a_wait_lock_blocks_a_second_reader_until_the_first_commits(TckDatabase db) throws Exception {
        Assumptions.assumeTrue(VendorLocks.blocksOnLockedRow(db),
                "PostgreSQL and MySQL block a second reader on a locked row");
        try (SessionFactory holder = JoinTestSupport.sessionFactory(db);
                SessionFactory blocked = JoinTestSupport.sessionFactory(db)) {
            ExecutorService pool = readerPool();
            try (EntityManager em = holder.createEntityManager()) {
                em.getTransaction().begin();
                assertThat(executor(em).first(locked(orders(), LOCK))).isPresent();
                Future<Optional<OrderLines>> second = pool.submit(() -> lockedFirst(blocked, LOCK));
                // Still waiting a second later: it blocked instead of reading around the lock.
                assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                em.getTransaction().commit();
                assertThat(second.get(30, TimeUnit.SECONDS).map(OrderLines::id)).contains(1L);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** {@code SKIP_LOCKED} passes the locked row over and reads the next one, without waiting. */
    @TckTest
    void ac_exe_17_skip_locked_reads_the_next_unlocked_row(TckDatabase db) {
        Assumptions.assumeTrue(VendorLocks.blocksOnLockedRow(db),
                "PostgreSQL and MySQL skip rows another connection has locked");
        try (SessionFactory holder = JoinTestSupport.sessionFactory(db);
                SessionFactory skipping = JoinTestSupport.sessionFactory(db)) {
            try (EntityManager em = holder.createEntityManager()) {
                em.getTransaction().begin();
                assertThat(executor(em).first(locked(orders(), LOCK))).isPresent();
                assertThat(lockedFirst(skipping, RowLock.exclusive(Wait.SKIP_LOCKED)).map(OrderLines::id))
                        .contains(2L);
                em.getTransaction().rollback();
            }
        }
    }

    /** {@code NO_WAIT} fails at once; which of JPA's two exceptions it is, is the vendor's (recorded below). */
    @TckTest
    void ac_exe_17_no_wait_surfaces_a_lock_conflict_as_the_vendor_reports_it(TckDatabase db) {
        Assumptions.assumeTrue(VendorLocks.blocksOnLockedRow(db),
                "PostgreSQL and MySQL refuse rather than wait for a locked row");
        try (SessionFactory holder = JoinTestSupport.sessionFactory(db);
                SessionFactory refused = JoinTestSupport.sessionFactory(db)) {
            try (EntityManager em = holder.createEntityManager()) {
                em.getTransaction().begin();
                assertThat(executor(em).first(locked(orders(), LOCK))).isPresent();
                Throwable thrown = catchThrowable(() -> lockedFirst(refused, RowLock.exclusive(Wait.NO_WAIT)));
                assertThat(thrown).as("a NO_WAIT conflict must surface, not read around the lock").isNotNull();
                // R-EXE-14 lets either pass through; a third type is a change worth failing on.
                assertThat(thrown.getClass()).isIn(PessimisticLockException.class, LockTimeoutException.class);
                note(db, "NO_WAIT -> " + thrown.getClass().getName() + ": " + thrown.getMessage());
                em.getTransaction().rollback();
            }
        }
    }

    /** What a read-only transaction does with a lock is a vendor fact: it is recorded, never asserted. */
    @TckTest
    void ac_exe_17_a_read_only_transaction_records_what_a_locked_read_does_there(TckDatabase db) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(readOnlyDataSource(db))) {
            long before = countOrders(sessions);
            note(db, "read-only transaction: " + readOnlyOutcome(sessions));
            // Whatever it did, a locking read took no row and changed no data (INV-1).
            assertThat(countOrders(sessions)).isEqualTo(before);
        }
    }

    // ---- helpers

    /** Orders by id: {@code first} reads the lowest key, so a blocked, skipped or no-wait read is predictable. */
    private static ModelQuery.Builder<OrderEntity, Long, OrderLines> orders() {
        return QOrderLines.query().select(QOrderLines.ALL).orderBy(QOrderLines.ID.asc());
    }

    /** The same rows with no order, for the reads that log a {@code WARNING} on an ordered query (AC-EXE-15). */
    private static ModelQuery.Builder<OrderEntity, Long, OrderLines> unordered() {
        return QOrderLines.query().select(QOrderLines.ALL);
    }

    private static ModelQuery<OrderEntity, Long, OrderLines> locked(
            ModelQuery.Builder<OrderEntity, Long, OrderLines> query, RowLock lock) {
        return query.build().withLock(lock);
    }

    /**
     * Whether {@code statement} carries a locking clause: {@code FOR UPDATE}, {@code FOR NO KEY UPDATE} or
     * {@code FOR SHARE}.
     */
    private static boolean locked(String statement) {
        String lower = statement.toLowerCase(Locale.ROOT);
        return lower.contains("for update") || lower.contains("for no key update") || lower.contains("for share");
    }

    /** A locked {@code first} on its own connection; it blocks in the statement while a holder keeps the row locked. */
    private static Optional<OrderLines> lockedFirst(SessionFactory sessions, RowLock lock) {
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                return executor(em).first(locked(orders(), lock));
            } finally {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
            }
        }
    }

    /**
     * Daemon threads: a reader that is still blocked when an assertion fails must not keep the JVM alive, and its lock
     * wait is not interruptible.
     */
    private static ExecutorService readerPool() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "tck-lock-reader");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static long countOrders(SessionFactory sessions) {
        try (EntityManager em = sessions.createEntityManager()) {
            return executor(em).count(QOrderLines.query().select(QOrderLines.ALL).build());
        }
    }

    /** One locked read on a read-only connection, described rather than asserted (RFC 0009 §2). */
    private static String readOnlyOutcome(SessionFactory sessions) {
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Optional<Long> id = executor(em).first(locked(orders(), LOCK)).map(OrderLines::id);
                return "locked and read id " + id.orElse(null);
            } catch (RuntimeException e) {
                return e.getClass().getName() + ": " + e.getMessage();
            } finally {
                em.getTransaction().rollback();
            }
        }
    }

    /** Records a per-vendor observation in the test output, which is all RFC 0009 §2 asks for these two cases. */
    private static void note(TckDatabase db, String observation) {
        System.out.println("[lock] " + db.target().displayName() + " " + observation);
    }

    private static ModelQueryExecutor<OrderEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
    }

    /**
     * A DataSource over {@code db} that marks every connection read-only as it is opened, before Hibernate's own
     * {@code autoCommit=false}: PostgreSQL's driver refuses the change once a transaction is under way.
     */
    private static DataSource readOnlyDataSource(TckDatabase db) {
        return (DataSource) Proxy.newProxyInstance(RowLockExecutionTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("toString") ? "Read-only DataSource of " + db
                                : method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                : proxy == args[0];
                    }
                    if (!method.getName().equals("getConnection")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Connection connection = db.getConnection();
                    connection.setReadOnly(true);
                    return connection;
                });
    }

    private static void withExecutor(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(db)) {
            sessions.inTransaction(em -> work.accept(executor(em)));
        }
    }

    private static void withExecutor(DataSource dataSource, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(dataSource)) {
            sessions.inTransaction(em -> work.accept(executor(em)));
        }
    }

    private static void assertMq(Runnable call, MqCode code, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(code.code() + ": ").hasMessageContaining(message);
    }

    private static ModelQuery.Builder<TwoTableRoot, Long, RootRow> twoTableRows() {
        return ModelQuery.builder(TWO_TABLE_ROOT, row -> new RootRow(row.get(ROOT_ID)))
                .select(SelectSet.of(ROOT_ID))
                .primaryKey(PrimaryKey.of(ROOT_ID));
    }

    /** A factory of the two-table fixture alone, created and dropped on {@code db}: it needs no seeded rows. */
    private static SessionFactory twoTableFactory(TckDatabase db) {
        return new Configuration().addAnnotatedClass(TwoTableRoot.class).buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .build());
    }

    /** A root with a secondary table, so {@code tablesOf} has two tables for it and a lock is refused (R-VND-13). */
    @Entity
    @Table(name = "lock_two_table_root")
    @SecondaryTable(name = "lock_two_table_detail")
    static class TwoTableRoot {

        @Id
        Long id;

        @Column(table = "lock_two_table_detail")
        String detail;

        protected TwoTableRoot() {}

        TwoTableRoot(Long id, String detail) {
            this.id = id;
            this.detail = detail;
        }
    }
}
