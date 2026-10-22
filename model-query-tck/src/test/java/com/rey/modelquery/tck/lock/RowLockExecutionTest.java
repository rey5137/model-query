package com.rey.modelquery.tck.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.RowLock.Wait;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
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
import com.rey.modelquery.tck.vnd.VendorReturning;
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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.hibernate.query.criteria.JpaRoot;
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

    /** The claim test's own orders, above every seeded row: the holder takes the first, the worker claims the next. */
    private static final long JOB_BASE = 900_000L;
    private static final long JOB_FIRST = JOB_BASE + 1;

    private static final FetchPlan<Line> LINE = FetchPlan.of(QLine.ALL);

    /** The claim recipe's locked read (recipes.md, "Claim the next job"); only the claim test's rows are pending. */
    private static final ModelQuery<OrderEntity, Long, OrderLines> NEXT_PENDING = QOrderLines.query()
            .select(QOrderLines.ALL)
            .where(f -> f.eq(QOrderLines.STATUS, "PENDING"))
            .orderBy(QOrderLines.ID.asc())
            .build()
            .withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));

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
        // A customizer's join stays in the statement; a filter-only to-many one renders as a semi-join and runs
        // (AC-EXE-22, R-EXE-16).
        ModelQuery<OrderEntity, Long, OrderLines> joined = QOrderLines.query().select(QOrderLines.ALL)
                .customize((spec, joins, query, cb, phase) -> query.getRoots().iterator().next().join("items"))
                .build()
                .withLock(LOCK);
        withExecutor(db, executor -> assertMq(() -> executor.list(joined, Limit.unlimited()), MqCode.MQ2211,
                "cannot lock a query whose statement has a join"));
    }

    /**
     * A factory-wide {@code jakarta.persistence.lock.timeout} (0: no wait, -2: skip locked) is copied onto every query
     * by Hibernate, so a plain {@code exclusive()} must still override it and wait (R-VND-15, D-127).
     */
    @TckTest
    void ac_exe_17_a_factory_lock_timeout_does_not_turn_a_plain_wait_into_nowait_or_skip_locked(TckDatabase db) {
        for (int timeout : new int[] {0, -2}) {
            List<String> sql = SqlSnapshots.capture(db, dataSource -> {
                try (SessionFactory sessions = JoinTestSupport.sessionFactory(dataSource,
                        Map.of("jakarta.persistence.lock.timeout", timeout))) {
                    sessions.inTransaction(em -> assertThat(executor(em).first(locked(orders(), LOCK))).isPresent());
                }
            });
            assertThat(sql).as("timeout %s", timeout).isNotEmpty();
            String lockStatement = sql.stream().filter(RowLockExecutionTest::locked).findFirst().orElseThrow();
            assertThat(lockStatement.toLowerCase(Locale.ROOT)).as("factory timeout %s, but: %s", timeout, lockStatement)
                    .doesNotContain("nowait").doesNotContain("skip locked");
        }
    }

    /** An entity join or a fetch join a customizer adds is {@code MQ2211} too, whatever Hibernate lists in getJoins(). */
    @TckTest
    void ac_exe_16_a_customizer_entity_join_or_fetch_is_refused_with_mq2211(TckDatabase db) {
        ModelQuery<OrderEntity, Long, OrderLines> entityJoined = orders()
                .customize((spec, joins, query, cb, phase) -> ((JpaRoot<?>) query.getRoots().iterator().next())
                        .join(CustomerEntity.class))
                .build()
                .withLock(LOCK);
        ModelQuery<OrderEntity, Long, OrderLines> fetched = orders()
                .customize((spec, joins, query, cb, phase) -> query.getRoots().iterator().next().fetch("customer"))
                .build()
                .withLock(LOCK);
        List<String> sql = SqlSnapshots.capture(db, dataSource -> withExecutor(dataSource, executor -> {
            assertMq(() -> executor.first(entityJoined), MqCode.MQ2211, "has a join");
            assertMq(() -> executor.first(fetched), MqCode.MQ2211, "has a join");
        }));
        assertThat(sql).as("refused before any statement").isEmpty();
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

    /**
     * The "claim the next job" recipe: a worker reads the pending rows with {@code SKIP_LOCKED} and updates its claim
     * in the same transaction, so the row it takes is locked from the read until it commits.
     */
    @TckTest
    void ac_exe_17_claim_the_next_job_skips_locked_rows_and_updates_its_claim(TckDatabase db) throws SQLException {
        Assumptions.assumeTrue(VendorLocks.blocksOnLockedRow(db),
                "PostgreSQL and MySQL skip rows another connection has locked");
        seedJobs(db, 2);
        try (SessionFactory holder = JoinTestSupport.sessionFactory(db);
                SessionFactory worker = JoinTestSupport.sessionFactory(db)) {
            try (EntityManager em = holder.createEntityManager()) {
                em.getTransaction().begin();
                // The holder takes the first job, so the worker must pass it over and claim the next one.
                assertThat(executor(em).first(jobs(RowLock.exclusive()))).map(OrderLines::id).contains(JOB_FIRST);
                long claimed = claimNextJob(worker);
                assertThat(claimed).isEqualTo(JOB_FIRST + 1);
                // The claim is committed, and the holder's own uncommitted job is untouched.
                assertThat(orderStatus(db, claimed)).isEqualTo("CLAIMED");
                assertThat(orderStatus(db, JOB_FIRST)).isEqualTo("NEW");
                em.getTransaction().rollback();
            }
        } finally {
            deleteJobs(db);
        }
    }

    // ---- AC-WRT-47: the claim recipe, a locked key read then a whereKeys write, by two concurrent workers

    /**
     * Two workers claim at once: each reads the next pending jobs with {@code SKIP_LOCKED}, so the second passes the
     * first's locked rows over, then writes its claim through {@code whereKeys} and reads it back, by
     * {@code updateReturning} where the database returns the rows of a write, else by {@code update} then
     * {@code byKeys}, in its one transaction. The two claims are disjoint and both commit. Where the provider drops
     * {@code SKIP_LOCKED} (H2 on Hibernate 6.6, vendor/41 §6) the locked read is {@code MQ4010} before any statement.
     */
    @TckTest
    void ac_wrt_47_two_concurrent_workers_claim_disjoint_jobs_and_read_their_claims_back(TckDatabase db)
            throws SQLException {
        if (!VendorLocks.rendered(db).contains(RowLock.exclusive(Wait.SKIP_LOCKED))) {
            List<String> sql = SqlSnapshots.capture(db, ds -> {
                try (SessionFactory sessions = JoinTestSupport.sessionFactory(ds)) {
                    sessions.inTransaction(em -> assertMq(() -> executor(em).list(NEXT_PENDING, Limit.of(2)),
                            MqCode.MQ4010, "cannot lock"));
                }
            });
            assertThat(sql).as("no statement ran").isEmpty();
            return;
        }
        boolean returnsRows = VendorReturning.returnsRows(db);
        seedJobs(db, 5, "PENDING");
        try (SessionFactory first = JoinTestSupport.sessionFactory(db);
                SessionFactory second = JoinTestSupport.sessionFactory(db);
                EntityManager one = first.createEntityManager();
                EntityManager two = second.createEntityManager()) {
            one.getTransaction().begin();
            two.getTransaction().begin();
            try {
                List<Long> oneIds = nextPending(executor(one));
                List<Long> twoIds = nextPending(executor(two));
                assertThat(oneIds).as("the first worker's jobs").containsExactly(JOB_FIRST, JOB_FIRST + 1);
                assertThat(twoIds).as("the second worker passes the first's locked jobs over")
                        .containsExactly(JOB_FIRST + 2, JOB_FIRST + 3);
                List<OrderLines> oneClaimed = claim(executor(one), oneIds, returnsRows);
                List<OrderLines> twoClaimed = claim(executor(two), twoIds, returnsRows);
                assertThat(oneClaimed).extracting(OrderLines::id).containsExactlyInAnyOrderElementsOf(oneIds);
                assertThat(twoClaimed).extracting(OrderLines::id).containsExactlyInAnyOrderElementsOf(twoIds);
                assertThat(oneClaimed).extracting(OrderLines::status).containsOnly("CLAIMED");
                assertThat(twoClaimed).extracting(OrderLines::status).containsOnly("CLAIMED");
                one.getTransaction().commit();
                two.getTransaction().commit();
            } finally {
                for (EntityManager em : List.of(one, two)) {
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                }
            }
            for (long id = JOB_FIRST; id < JOB_FIRST + 4; id++) {
                assertThat(orderStatus(db, id)).as("job %d committed claimed", id).isEqualTo("CLAIMED");
            }
            assertThat(orderStatus(db, JOB_FIRST + 4)).as("the job neither worker took").isEqualTo("PENDING");
        } finally {
            deleteJobs(db);
        }
    }

    /** The recipe's step 1: the next two pending jobs' keys, locked until the transaction ends. */
    private static List<Long> nextPending(ModelQueryExecutor<OrderEntity> executor) {
        return executor.list(NEXT_PENDING, Limit.of(2)).stream().map(OrderLines::id).toList();
    }

    /** The recipe's step 2: claim the locked jobs by key and read the claimed rows back. */
    private static List<OrderLines> claim(ModelQueryExecutor<OrderEntity> executor, List<Long> ids,
            boolean returnsRows) {
        var claim = ModelUpdate.builder(QOrderLines.ROOT).primaryKey(QOrderLines.KEY)
                .set(QOrderLines.STATUS, "CLAIMED").whereKeys(ids).build();
        var claimedRows = QOrderLines.query().select(QOrderLines.ALL).build();
        if (returnsRows) {
            // PostgreSQL and H2: one statement writes the claim and returns the rows.
            List<OrderLines> claimed = executor.updateReturning(claim, claimedRows);
            return claimed;
        }
        // MySQL: write, then read the rows back by key.
        executor.update(claim);
        Map<Long, OrderLines> byId = executor.byKeys(claimedRows, ids);
        return List.copyOf(byId.values());
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

    /** Reads the pending rows with {@code SKIP_LOCKED} and claims the first unlocked one, in one transaction. */
    private static long claimNextJob(SessionFactory worker) {
        try (EntityManager em = worker.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<OrderLines> pending = executor(em).list(jobs(RowLock.exclusive(Wait.SKIP_LOCKED)), Limit.of(5));
                assertThat(pending).as("the worker claimed a row").isNotEmpty();
                long id = pending.get(0).id();
                assertThat(em.createQuery("update OrderEntity o set o.status = 'CLAIMED' where o.id = :id")
                        .setParameter("id", id).executeUpdate()).isEqualTo(1);
                em.getTransaction().commit();
                return id;
            } finally {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
            }
        }
    }

    /** The claim test's rows, oldest first, filtered to its own above {@link #JOB_BASE}. */
    private static ModelQuery<OrderEntity, Long, OrderLines> jobs(RowLock lock) {
        return QOrderLines.query().select(QOrderLines.ALL)
                .where(f -> f.gt(QOrderLines.ID, JOB_BASE))
                .orderBy(QOrderLines.ID.asc())
                .build()
                .withLock(lock);
    }

    /** Inserts {@code count} claimable rows of the claim test's own; {@link #deleteJobs} removes them. */
    private static void seedJobs(TckDatabase db, int count) throws SQLException {
        seedJobs(db, count, "NEW");
    }

    /** As {@link #seedJobs(TckDatabase, int)}, each row in {@code status}. */
    private static void seedJobs(TckDatabase db, int count, String status) throws SQLException {
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "insert into orders (id, customer_id, status, total, placed_at) values (?, 1, ?, 0, ?)")) {
                for (int i = 1; i <= count; i++) {
                    statement.setLong(1, JOB_BASE + i);
                    statement.setString(2, status);
                    statement.setTimestamp(3, Timestamp.valueOf("2020-01-01 00:00:00"));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        }
    }

    private static String orderStatus(TckDatabase db, long id) throws SQLException {
        try (Connection connection = db.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("select status from orders where id = " + id)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void deleteJobs(TckDatabase db) throws SQLException {
        try (Connection connection = db.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("delete from orders where id > " + JOB_BASE);
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
