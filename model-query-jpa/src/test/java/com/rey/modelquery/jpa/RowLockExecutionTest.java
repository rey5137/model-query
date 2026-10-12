package com.rey.modelquery.jpa;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.LockSupport;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The executor's locking reads (engine/20 R-EXE-14, D-127): which reads lock, which refuse a lock with {@code MQ2211},
 * which factories refuse it with {@code MQ4010}, and the transaction {@code MQ2102} needs. The {@code LockSupport}
 * stubs record what the engine asked them to apply. That a fetch plan's own statements carry no lock is asserted where
 * they are visible, in the TCK's SQL snapshots ({@code tck/lock/RowLockExecutionTest}).
 */
class RowLockExecutionTest {

    record Row(Long id, Integer a) {}

    record ParentRow(Long id, String childName) {}

    record NewRow(Long id, Integer a) {}

    private static final String MARKER = "model-query.test.row-locks";

    private static final String TWO_TABLES = "model-query.test.two-tables";

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);

    private static final ColumnField<Row, KeysetRowEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);

    private static final ColumnField<Row, KeysetRowEntity, Integer> A =
            ColumnField.of(Row.class, ROOT, "a", Integer.class);

    private static final TableField<LockParentEntity, LockParentEntity> PARENTS =
            TableField.root(LockParentEntity.class);

    private static final TableField<LockParentEntity, LockChildEntity> CHILDREN = TableField.join(PARENTS, "child",
            INNER);

    private static final ColumnField<ParentRow, LockParentEntity, Long> PARENT_ID =
            ColumnField.of(ParentRow.class, PARENTS, "id", Long.class);

    private static final ColumnField<ParentRow, LockChildEntity, String> CHILD_NAME =
            ColumnField.of(ParentRow.class, CHILDREN, "name", String.class);

    private static final ColumnField<NewRow, KeysetRowEntity, Long> NEW_ID =
            ColumnField.of(NewRow.class, ROOT, "id", Long.class);

    private static final ColumnField<NewRow, KeysetRowEntity, Integer> NEW_A =
            ColumnField.of(NewRow.class, ROOT, "a", Integer.class);

    private static final InsertColumns<NewRow, KeysetRowEntity> PERSISTED = InsertColumns
            .<NewRow, KeysetRowEntity>of(ROOT).addKey(NEW_ID, NewRow::id).add(NEW_A, NewRow::a);

    private static final RowLock LOCK = RowLock.exclusive();

    /** Every statement any factory of this test ran, so a refusal can be shown to run before any. */
    private static final List<String> SQL = Collections.synchronizedList(new ArrayList<>());

    private static final ModelQueryConfig H2 = ModelQueryConfig.defaults().vendor(DatabaseVendor.H2);

    private static final ModelQuery.Builder<KeysetRowEntity, Long, Row> ROWS =
            ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(A))).select(SelectSet.of(ID, A))
                    .primaryKey(PrimaryKey.of(ID));

    /** A query whose statement always joins: the selected column lives on the to-one child's table. */
    private static final ModelQuery.Builder<LockParentEntity, Long, ParentRow> PARENT_ROWS =
            ModelQuery.builder(PARENTS, row -> new ParentRow(row.get(PARENT_ID), row.get(CHILD_NAME)))
                    .select(SelectSet.of(PARENT_ID, CHILD_NAME)).primaryKey(PrimaryKey.of(PARENT_ID));

    private static SessionFactory sessions;

    @BeforeAll
    static void seed() {
        sessions = factory("locked", MARKER);
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new KeysetRowEntity(1, null, 10, 0));
            em.persist(new KeysetRowEntity(2, 2, 20, 0));
            em.persist(new LockChildEntity(1, "child-1"));
            em.persist(new LockParentEntity(1, em.getReference(LockChildEntity.class, 1L)));
            em.getTransaction().commit();
        }
    }

    /** Each test asserts on what its own reads applied, so the record starts empty. */
    @BeforeEach
    void forget() {
        Locks.APPLIED.clear();
        Locks.KEYS.clear();
        SQL.clear();
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    // ---- which reads lock

    @Test
    void ac_exe_16_every_read_that_returns_rows_locks_the_statement_that_returns_them() {
        withTransaction(em -> {
            ModelQueryExecutor<KeysetRowEntity> executor = executor(em);
            ModelQuery<KeysetRowEntity, Long, Row> locked = ROWS.build().withLock(LOCK);
            assertThat(executor.list(locked, Limit.unlimited())).containsExactly(new Row(1L, null), new Row(2L, 2));
            assertThat(executor.list(locked, Limit.of(1))).containsExactly(new Row(1L, null));
            assertThat(executor.first(locked)).contains(new Row(1L, null));
            assertThat(executor.one(ROWS.where(f -> f.eq(ID, 2L)).build().withLock(LOCK))).contains(new Row(2L, 2));
            assertThat(executor.one(locked, 2L)).contains(new Row(2L, 2));
            assertThat(executor.byKeys(locked, List.of(1L, 2L))).containsOnlyKeys(1L, 2L);
            assertThat(Locks.APPLIED).hasSize(6).allSatisfy(applied -> assertThat(applied).isEqualTo(LOCK));
        });
    }

    @Test
    void ac_exe_16_by_keys_locks_every_chunk_statement_in_primary_key_order() {
        withTransaction(em -> {
            ModelQueryExecutor<KeysetRowEntity> executor = executor(em);
            assertThat(executor.byKeys(ROWS.build(), List.of(1L, 2L))).containsOnlyKeys(1L, 2L);
            // An unlocked chunk takes no lock and keeps R-EXE-13's no order (exe-14-by-keys).
            assertThat(Locks.APPLIED).isEmpty();
            assertThat(executor.byKeys(ROWS.build().withLock(LOCK), List.of(1L, 2L))).containsOnlyKeys(1L, 2L);
            // One statement per chunk, each locked; each SQL orders by the primary key so concurrent callers lock in
            // one order, which the TCK's locked-byKeys snapshot asserts (tck/lock/RowLockExecutionTest).
            assertThat(Locks.APPLIED).hasSize(1).allSatisfy(applied -> assertThat(applied).isEqualTo(LOCK));
        });
    }

    @Test
    void ac_exe_16_a_fetch_plan_runs_without_the_lock_the_read_carries() {
        AtomicInteger enriched = new AtomicInteger();
        withTransaction(em -> {
            ModelQuery<KeysetRowEntity, Long, Row> locked = ROWS
                    .fetch(FetchPlan.of(SelectSet.of(ID, A)).enrich(Enricher.of(page -> {
                        enriched.set(page.size());
                        return page;
                    })))
                    .build()
                    .withLock(LOCK);
            assertThat(executor(em).list(locked, Limit.unlimited())).hasSize(2);
            // The plan ran, and only the statement that returns the model's rows was locked (R-FCH-*, R-EXE-14).
            assertThat(enriched).hasValue(2);
            assertThat(Locks.APPLIED).singleElement()
                    .satisfies(applied -> assertThat(applied).isEqualTo(LOCK));
        });
    }

    // ---- which reads refuse a lock

    @Test
    void ac_exe_16_the_reads_with_no_row_to_lock_refuse_with_mq2211() {
        withTransaction(em -> {
            ModelQueryExecutor<KeysetRowEntity> executor = executor(em);
            ModelQuery<KeysetRowEntity, Long, Row> locked = ROWS.build().withLock(LOCK);
            assertMq(() -> executor.page(locked, PageSpec.of(1, 2), CountMode.COUNT), MqCode.MQ2211,
                    "cannot run a locked query");
            assertMq(() -> executor.page(locked, KeysetSpec.first(2)), MqCode.MQ2211, "cannot run a locked query");
            assertMq(() -> executor.count(locked), MqCode.MQ2211, "cannot run a locked query");
            assertMq(() -> executor.stream(locked, Limit.unlimited(), Stream::count), MqCode.MQ2211,
                    "cannot run a locked query");
            assertMq(() -> executor.export(locked, ExportOptions.of(2), page -> page, row -> { }), MqCode.MQ2211,
                    "cannot run a locked query");
            // A refusing read refuses before its own zero-limit short cut (R-EXE-06, R-EXE-14), while a read that
            // locks returns nothing for a zero limit without a statement, as an unlocked read does.
            assertMq(() -> executor.stream(locked, Limit.of(0), Stream::count), MqCode.MQ2211,
                    "cannot run a locked query");
            assertThat(SQL).isEmpty();
            assertThat(executor.list(locked, Limit.of(0))).isEmpty();
            assertThat(SQL).isEmpty();
            assertThat(Locks.APPLIED).isEmpty();
        });
    }

    @Test
    void ac_exe_16_one_with_skip_locked_is_refused_with_mq2211() {
        withTransaction(em -> {
            ModelQuery<KeysetRowEntity, Long, Row> skipped =
                    ROWS.build().withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));
            assertMq(() -> executor(em).one(skipped), MqCode.MQ2211, "cannot lock with SKIP_LOCKED");
            assertThat(SQL).isEmpty();
            // first(...) claims the next unlocked row, which is what one(query) cannot do.
            assertThat(executor(em).first(skipped)).isPresent();
            assertThat(Locks.APPLIED).singleElement().satisfies(applied ->
                    assertThat(applied.waitPolicy()).isEqualTo(RowLock.Wait.SKIP_LOCKED));
        });
    }

    @Test
    void ac_exe_16_a_locked_by_keys_sorts_its_keys_before_chunking_and_keeps_the_callers_order() {
        withTransaction(em -> {
            ModelQueryExecutor<KeysetRowEntity> executor = executor(em);
            var rows = executor.byKeys(ROWS.build().withLock(LOCK), List.of(2L, 1L));
            assertThat(rows.keySet()).containsExactly(2L, 1L);
            assertThat(Locks.KEYS).singleElement().isEqualTo(List.of(1L, 2L));
            Locks.KEYS.clear();
            executor.byKeys(ROWS.build(), List.of(2L, 1L));
            assertThat(Locks.KEYS).isEmpty();
        });
    }

    @Test
    void ac_exe_16_a_locked_by_keys_with_keys_that_are_not_comparable_keeps_the_callers_order() {
        withTransaction(em -> {
            // A composite key is a List, which is not Comparable, so sorting it fails and the order given is kept.
            ColumnField<Row, KeysetRowEntity, Integer> c = ColumnField.of(Row.class, ROOT, "c", Integer.class);
            ModelQuery<KeysetRowEntity, List<Object>, Row> composite = ModelQuery
                    .builder(ROOT, row -> new Row(row.get(ID), row.get(A))).select(SelectSet.of(ID, A, c))
                    .primaryKey(PrimaryKey.composite(ID, c)).build().withLock(LOCK);
            List<Object> second = List.of(2L, 0);
            List<Object> first = List.of(1L, 0);
            var rows = executor(em).byKeys(composite, List.of(second, first));
            assertThat(rows.keySet()).containsExactly(second, first);
            assertThat(Locks.KEYS).singleElement().isEqualTo(List.of(2L, 0, 1L, 0));
        });
    }

    @Test
    void ac_exe_16_a_by_keys_customizer_that_joins_only_in_a_chunk_statement_is_refused_with_mq2211() {
        withTransaction(em -> {
            int[] builds = {0};
            ModelQuery<KeysetRowEntity, Long, Row> late = ROWS
                    .customize((spec, joins, query, cb, phase) -> {
                        if (++builds[0] > 1) { // the first build is the executor's bind count, before any chunk
                            query.from(LockChildEntity.class);
                        }
                    })
                    .build()
                    .withLock(LOCK);
            assertMq(() -> executor(em).byKeys(late, List.of(1L, 2L)), MqCode.MQ2211, "has a join");
            assertThat(SQL).isEmpty();
        });
    }

    @Test
    void ac_exe_16_a_query_whose_statement_joins_is_refused_with_mq2211() {
        withTransaction(em -> {
            assertMq(() -> ModelQueryExecutor.create(em, LockParentEntity.class, H2)
                    .list(PARENT_ROWS.build().withLock(LOCK), Limit.unlimited()), MqCode.MQ2211, "has a join");
            // A second root a customizer adds is a cross join, which the clause would lock too.
            ModelQuery<KeysetRowEntity, Long, Row> crossed = ROWS
                    .customize((spec, joins, query, cb, phase) -> query.from(LockChildEntity.class))
                    .build()
                    .withLock(LOCK);
            assertMq(() -> executor(em).first(crossed), MqCode.MQ2211, "has a join");
            assertThat(SQL).isEmpty();
        });
    }

    @Test
    void ac_exe_16_a_locked_persist_returning_query_is_refused_with_mq1809() {
        withTransaction(em -> {
            ModelPersist<KeysetRowEntity, Long, NewRow> persist =
                    ModelPersist.of(PERSISTED, Long.class, new NewRow(3L, 7));
            assertThatThrownBy(() -> executor(em).persist(persist, ROWS.build().withLock(LOCK)))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1809))
                    .hasMessageContaining("a lock");
            assertThat(Locks.APPLIED).isEmpty();
        });
    }

    // ---- which factories refuse a lock

    @Test
    void ac_exe_16_a_factory_whose_provider_renders_no_lock_throws_mq4010_before_any_statement() {
        try (SessionFactory unserved = factory("unserved", null);
                SessionFactory without = factory("without-locks", NoLocks.MARKER);
                SessionFactory unrendered = factory("unrendered", Unrendered.MARKER)) {
            for (SessionFactory factory : List.of(unserved, without, unrendered)) {
                withTransaction(factory, em -> assertMq(() -> executor(em).list(ROWS.build().withLock(LOCK),
                        Limit.unlimited()), MqCode.MQ4010, "cannot lock RowLock.exclusive(WAIT) on H2, because"));
            }
            assertThat(SQL).isEmpty();
            assertThat(Locks.APPLIED).isEmpty();
        }
    }

    @Test
    void ac_exe_16_a_root_on_several_tables_throws_mq4010() {
        try (SessionFactory factory = factory("two-tables", MARKER, TWO_TABLES)) {
            withTransaction(factory, em -> assertMq(() -> executor(em).list(ROWS.build().withLock(LOCK),
                    Limit.unlimited()), MqCode.MQ4010, "is mapped to more than one table"));
            assertThat(SQL).isEmpty();
        }
    }

    @Test
    void ac_exe_16_a_locked_read_outside_a_transaction_throws_mq2102_before_any_statement() {
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQuery<KeysetRowEntity, Long, Row> locked = ROWS.build().withLock(LOCK);
            assertMq(() -> executor(em).list(locked, Limit.unlimited()), MqCode.MQ2102,
                    "locks RowLock.exclusive(WAIT) and needs an active transaction");
            // Before the zero-limit and no-keys short cuts too, so the refusal does not depend on the request.
            assertMq(() -> executor(em).list(locked, Limit.of(0)), MqCode.MQ2102, "needs an active transaction");
            assertMq(() -> executor(em).byKeys(locked, List.of()), MqCode.MQ2102, "needs an active transaction");
            assertThat(SQL).isEmpty();
            assertThat(Locks.APPLIED).isEmpty();
        }
    }

    // ---- helpers

    private static void assertMq(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(code.code() + ": ").hasMessageContaining(message);
    }

    private static void withTransaction(Consumer<EntityManager> work) {
        withTransaction(sessions, work);
    }

    private static void withTransaction(SessionFactory factory, Consumer<EntityManager> work) {
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            try {
                work.accept(em);
            } finally {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
            }
        }
    }

    private static ModelQueryExecutor<KeysetRowEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, KeysetRowEntity.class, H2);
    }

    private static SessionFactory factory(String name, String marker, String... properties) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:row-lock-" + name)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .applySetting(AvailableSettings.STATEMENT_INSPECTOR, (StatementInspector) sql -> {
                    SQL.add(sql);
                    return sql;
                });
        if (marker != null) {
            registry.applySetting(marker, "true");
        }
        for (String property : properties) {
            registry.applySetting(property, "true");
        }
        return new Configuration().addAnnotatedClass(KeysetRowEntity.class).addAnnotatedClass(LockParentEntity.class)
                .addAnnotatedClass(LockChildEntity.class).buildSessionFactory(registry.build());
    }

    /** A {@link ProviderSupport} that only identifies the factory it serves. */
    public abstract static class Stub implements ProviderSupport {

        private final String marker;

        Stub(String marker) {
            this.marker = marker;
        }

        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(marker);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }
    }

    /** A provider that renders every lock, recording what the engine asked it to apply. */
    public static final class Locks extends Stub {

        static final List<RowLock> APPLIED = Collections.synchronizedList(new ArrayList<>());

        /** The values bound to each applied statement's parameters, in parameter order. */
        static final List<List<Object>> KEYS = Collections.synchronizedList(new ArrayList<>());

        public Locks() {
            super(MARKER);
        }

        @Override
        public Optional<LockSupport> locks() {
            return Optional.of(new LockSupport() {
                @Override
                public boolean rendered(EntityManagerFactory emf, RowLock lock) {
                    return true;
                }

                @Override
                public void apply(TypedQuery<?> query, RowLock lock) {
                    APPLIED.add(lock);
                    KEYS.add(query.getParameters().stream().<Object>map(query::getParameterValue).toList());
                }
            });
        }

        /** Two tables only under the two-tables marker, so a root on several tables is {@code MQ4010} (R-VND-13). */
        @Override
        public Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity) {
            return emf.getProperties().containsKey(TWO_TABLES)
                    ? Set.of("lock_parents", "lock_children") : Set.of("lock_parents");
        }
    }

    /** A provider that supports nothing beyond JPA: a locked read is {@code MQ4010}. */
    public static final class NoLocks extends Stub {

        static final String MARKER = "model-query.test.no-locks";

        public NoLocks() {
            super(MARKER);
        }
    }

    /** A provider whose {@code LockSupport} renders no lock, as Hibernate 6.6 does not on H2. */
    public static final class Unrendered extends Stub {

        static final String MARKER = "model-query.test.unrendered-locks";

        public Unrendered() {
            super(MARKER);
        }

        @Override
        public Optional<LockSupport> locks() {
            return Optional.of(new LockSupport() {
                @Override
                public boolean rendered(EntityManagerFactory emf, RowLock lock) {
                    return false;
                }

                @Override
                public void apply(TypedQuery<?> query, RowLock lock) {
                    throw new AssertionError("apply must not be reached for a lock that is not rendered");
                }
            });
        }
    }
}
