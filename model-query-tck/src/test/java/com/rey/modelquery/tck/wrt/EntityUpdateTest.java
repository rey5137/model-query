package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PersistenceContextMode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.ins.InsListenedEntity;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;

/**
 * An update {@code throughEntities()} on every Tier-1 vendor: each chunk's keys selected as a chunked update selects
 * them, its entities loaded with one select, assigned through the metamodel member and flushed, so JPA callbacks and
 * Hibernate event listeners run for each changed row and none for an unchanged one; the count is the rows matched;
 * the persistence context cleared per chunk or kept; an {@code OptimisticLockException} a failed chunk to resume after
 * (spec api/14 R-WRT-41 to R-WRT-47). The Hibernate listener lives here, never in {@code model-query-jpa} (INV-7).
 */
class EntityUpdateTest {

    /** An update model over {@link InsListenedEntity}: the amount as {@code #42}, the source by id. */
    record Patch(Long id, String status, String amount, String note, Long source) {}

    private static final TableField<InsListenedEntity, InsListenedEntity> ROOT =
            TableField.root(InsListenedEntity.class);
    private static final TableField<InsListenedEntity, InsSourceEntity> SOURCE = TableField.join(ROOT, "source", INNER);
    private static final ColumnField<Patch, InsListenedEntity, Long> ID =
            ColumnField.of(Patch.class, ROOT, "id", Long.class);
    private static final ColumnField<Patch, InsListenedEntity, String> STATUS =
            ColumnField.of(Patch.class, ROOT, "status", String.class);
    private static final ColumnField<Patch, InsListenedEntity, String> AMOUNT = ColumnField.of(Patch.class, ROOT,
            "amount", String.class, Long.class, InsertValuesTest.RefConverter.INSTANCE);
    private static final ColumnField<Patch, InsListenedEntity, String> NOTE =
            ColumnField.of(Patch.class, ROOT, "note", String.class);
    private static final ColumnField<Patch, InsListenedEntity, Long> SOURCE_ID =
            ColumnField.of(Patch.class, ROOT, "source", Long.class);
    private static final ColumnField<Patch, InsSourceEntity, String> SOURCE_CODE =
            ColumnField.of(Patch.class, SOURCE, "code", String.class);

    private static final String ROWS = "select id, status, amount, note, source_id, version from ins_listened "
            + "order by id";

    /** A change set over {@link Patch}, as a generated one behaves: set columns in the order set. */
    private static final class PatchChanges implements Changes<Patch> {
        private final Map<ColumnField<Patch, ?, ?>, Assignment<Patch, ?>> set = new LinkedHashMap<>();

        <C> PatchChanges with(ColumnField<Patch, ?, C> column, C value) {
            set.put(column, value == null ? Assignment.ofNull(column) : Assignment.of(column, value));
            return this;
        }

        @Override
        public boolean isSet(ColumnField<Patch, ?, ?> column) {
            return set.containsKey(column);
        }

        @Override
        public PatchChanges unset(ColumnField<Patch, ?, ?> column) {
            set.remove(column);
            return this;
        }

        @Override
        public boolean isEmpty() {
            return set.isEmpty();
        }

        @Override
        public List<Assignment<Patch, ?>> assignments() {
            return List.copyOf(set.values());
        }
    }

    /** Records each Hibernate post-update event: the id, and the status before and after. */
    private static final class PostUpdates implements PostUpdateEventListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onPostUpdate(PostUpdateEvent event) {
            int status = List.of(event.getPersister().getPropertyNames()).indexOf("status");
            events.add(event.getId() + ":" + event.getOldState()[status] + "->" + event.getState()[status]);
        }

        @Override
        public boolean requiresPostCommitHandling(EntityPersister persister) {
            return false;
        }
    }

    /** The plain-JPA callback of R-WRT-19: a resource-local {@code EntityManager} per chunk, begin, commit, close. */
    private static final class ResourceLocal implements ChunkTransactions {
        @Override
        public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                T result;
                try {
                    result = chunk.apply(em);
                } catch (RuntimeException e) {
                    em.getTransaction().rollback();
                    throw e;
                }
                em.getTransaction().commit();
                return result;
            } finally {
                em.close();
            }
        }
    }

    @AfterEach
    void forgetCallbacks() {
        InsListenedEntity.reset();
    }

    // ---- AC-WRT-34

    @TckTest
    void ac_wrt_34_an_entity_mode_update_fires_callbacks_and_the_hibernate_listener_once_per_changed_row_only(
            TckDatabase db) {
        // Row 2 is PAID already, so the update leaves it as it was; row 5 does not match.
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID")
                .where(f -> f.lte(ID, 4L)).throughEntities().build();
        var listener = new PostUpdates();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 5, id -> id == 2 ? "PAID" : "NEW");
            p.factory().unwrap(SessionFactoryImplementor.class).getServiceRegistry()
                    .requireService(EventListenerRegistry.class).appendListeners(EventType.POST_UPDATE, listener);
            long written = inCommittedTransaction(p, em -> executor(em).update(update));

            assertThat(written).isEqualTo(4);
            assertThat(InsListenedEntity.CALLBACKS).containsExactlyInAnyOrder("pre:1:PAID", "post:1:1",
                    "pre:3:PAID", "post:3:1", "pre:4:PAID", "post:4:1");
            assertThat(listener.events).containsExactlyInAnyOrder("1:NEW->PAID", "3:NEW->PAID", "4:NEW->PAID");
            assertThat(writes(p.statements(), "update")).hasSize(3);
            assertThat(p.rows(ROWS)).containsExactly("1|PAID|1|null|1|1", "2|PAID|2|null|1|0", "3|PAID|3|null|1|1",
                    "4|PAID|4|null|1|1", "5|NEW|5|null|1|0");
        }
    }

    @TckTest
    void ac_wrt_34_a_change_set_through_entities_writes_null_where_set_to_null_and_leaves_unset_attributes(
            TckDatabase db) {
        // The amount stays unset in the change set: the row keeps its own. The note is set to NULL.
        var changes = new PatchChanges().with(STATUS, "PAID").with(NOTE, null).with(SOURCE_ID, 2L);
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(changes).whereKey(2L)
                .throughEntities().build();
        var amountOnly = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(AMOUNT, "#42").whereKey(3L)
                .throughEntities().build();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 3, id -> "NEW");
            p.jdbc("update ins_listened set note = 'kept'");
            long written = inCommittedTransaction(p, em -> executor(em).update(update));

            assertThat(written).isEqualTo(1);
            // One key select, one load, one update: the source by id is a reference, never read.
            SqlSnapshots.assertMatches(db, "wrt-34-entity-update-change-set", p.statements());
            assertThat(writes(p.statements(), "select")).noneMatch(sql -> sql.contains("ins_source"));

            long amounts = inCommittedTransaction(p, em -> executor(em).update(amountOnly));
            assertThat(amounts).isEqualTo(1);
            // The model converter's #42 is the entity's 42.
            assertThat(p.rows(ROWS)).containsExactly("1|NEW|1|kept|1|0", "2|PAID|2|null|2|1",
                    "3|NEW|42|kept|1|1");
        }
    }

    // ---- AC-WRT-36

    @TckTest
    void ac_wrt_36_each_chunk_loads_with_one_select_and_clear_holds_at_most_one_chunk_of_entities(TckDatabase db) {
        // The where joins the source, so the load renders it in EXISTS and each entity comes once.
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID")
                .where(f -> f.eq(SOURCE_CODE, "s1").lte(ID, 5L)).throughEntities().chunked(ChunkOptions.size(2))
                .build();
        var managed = new ArrayList<Integer>();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 6, id -> "NEW");
            long written = inCommittedTransaction(p, em -> {
                InsListenedEntity.beforeUpdate = entity -> managed.add(entityCount(em));
                long rows = executor(em).update(update);
                assertThat(entityCount(em)).isZero();
                return rows;
            });

            assertThat(written).isEqualTo(5);
            List<String> sql = SqlSnapshots.assertMatches(db, "wrt-36-entity-update-chunks", p.statements());
            // Three key selects of 2, 2 and 1 keys, and one load after each.
            assertThat(loads(sql)).hasSize(3);
            assertThat(writes(sql, "update")).hasSize(5);
            assertThat(managed).hasSize(5).allMatch(count -> count <= 2);
            assertThat(p.rows("select count(*) from ins_listened where status = 'PAID'")).containsExactly("5");
        }
    }

    @TckTest
    void ac_wrt_36_keep_leaves_every_loaded_entity_managed_and_current(TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID")
                .where(f -> f.lte(ID, 5L)).throughEntities().chunked(ChunkOptions.size(2))
                .persistenceContext(PersistenceContextMode.KEEP).build();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 6, id -> "NEW");
            inCommittedTransaction(p, em -> {
                long rows = executor(em).update(update);
                assertThat(entityCount(em)).isEqualTo(5);
                p.forget();
                InsListenedEntity three = em.find(InsListenedEntity.class, 3L);
                // Found in the context, written through it: no select, the new status and version.
                assertThat(p.statements()).isEmpty();
                assertThat(three.status()).isEqualTo("PAID");
                assertThat(three.version()).isEqualTo(1);
                return rows;
            });
        }
    }

    @TckTest
    void ac_wrt_36_commit_each_chunk_resumes_from_chunked_write_exception_after_an_optimistic_lock_failure(
            TckDatabase db) {
        var options = ChunkOptions.size(10).commitEachChunk();
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID").all()
                .throughEntities().chunked(options).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());
        var bumped = new AtomicBoolean();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 30, id -> "NEW");
            // Between the third chunk's load and its write, another transaction moves row 25's version.
            InsListenedEntity.beforeUpdate = entity -> {
                if (entity.id() == 25L && bumped.compareAndSet(false, true)) {
                    p.jdbc("update ins_listened set version = version + 1 where id = 25");
                }
            };
            ChunkedWriteException[] failure = new ChunkedWriteException[1];
            withoutTransaction(p, em -> assertThatThrownBy(() -> executor(em, config).update(update))
                    .isInstanceOfSatisfying(ChunkedWriteException.class, e -> failure[0] = e));

            assertThat(failure[0].code()).isEqualTo(MqCode.MQ2502);
            assertThat(failure[0]).hasCauseInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("the 2 chunks before it stay committed, 20 rows");
            assertThat(failure[0].committedRows()).isEqualTo(20);
            assertThat(failure[0].lastCommittedKey()).contains(20L);
            assertThat(failure[0].inDoubtKeys()).isEmpty();
            assertThat(p.rows("select id from ins_listened where status = 'PAID' order by id"))
                    .containsExactlyElementsOf(ids(1, 20));

            var resume = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID").all()
                    .throughEntities().chunked(options, (Long) failure[0].lastCommittedKey().orElseThrow()).build();
            long[] resumed = new long[1];
            withoutTransaction(p, em -> resumed[0] = executor(em, config).update(resume));

            assertThat(resumed[0]).isEqualTo(10);
            assertThat(p.rows("select id from ins_listened where status = 'PAID' order by id"))
                    .containsExactlyElementsOf(ids(1, 30));
            // Row 25 was written over the version the other transaction left.
            assertThat(p.rows("select version from ins_listened where id = 25")).containsExactly("2");
        }
    }

    @TckTest
    void ac_wrt_36_in_the_callers_transaction_an_optimistic_lock_failure_reaches_the_caller_unwrapped(TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID").all()
                .throughEntities().build();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 3, id -> "NEW");
            // Between the load and the write, another transaction moves row 2's version.
            InsListenedEntity.beforeUpdate = entity -> {
                if (entity.id() == 2L) {
                    p.jdbc("update ins_listened set version = version + 1 where id = 2");
                }
            };
            // Not committing each chunk, the failure is the caller's own, with no ChunkedWriteException around it.
            assertThatThrownBy(() -> inCommittedTransaction(p, em -> executor(em).update(update)))
                    .isInstanceOf(OptimisticLockException.class);

            // The caller's transaction rolled back: only the other transaction's version change stays.
            assertThat(p.rows(ROWS)).containsExactly("1|NEW|1|null|1|0", "2|NEW|2|null|1|1", "3|NEW|3|null|1|0");
        }
    }

    @TckTest
    void ac_wrt_36_commit_each_chunk_under_clear_clears_the_callers_entity_manager_after_the_last_chunk(
            TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID").all()
                .throughEntities().chunked(ChunkOptions.size(2).commitEachChunk()).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 3, id -> "NEW");
            withoutTransaction(p, em -> {
                InsListenedEntity one = em.find(InsListenedEntity.class, 1L);
                assertThat(executor(em, config).update(update)).isEqualTo(3);

                // Each chunk wrote on its own EntityManager, so the caller's stale copy is detached and a find reads
                // the row as written.
                assertThat(em.contains(one)).isFalse();
                assertThat(entityCount(em)).isZero();
                assertThat(em.find(InsListenedEntity.class, 1L).status()).isEqualTo("PAID");
            });
        }
    }

    @TckTest
    void ac_wrt_36_an_entity_mode_update_without_a_transaction_throws_mq2501_before_any_statement(TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID").all()
                .throughEntities().build();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 2, id -> "NEW");
            p.forget();
            withoutTransaction(p, em -> assertThatThrownBy(() -> executor(em).update(update))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2501))
                    .hasMessageContaining("an entity-mode update needs an active transaction"));

            assertThat(p.statements()).isEmpty();
        }
    }

    /** Commits rows {@code 1} to {@code n}, each with its id as its amount, source 1 and version 0. */
    private static void seed(InsertProbes p, int n, Function<Integer, String> status) {
        p.jdbc("insert into ins_listened (id, status, amount, note, source_id, version) values "
                + IntStream.rangeClosed(1, n).mapToObj(id -> "(" + id + ", '" + status.apply(id) + "', " + id
                        + ", null, 1, 0)").collect(Collectors.joining(", ")));
    }

    /** The entity loads of {@code sql}: its selects of the root that are not key selects, which order by the key. */
    private static List<String> loads(List<String> sql) {
        return sql.stream().filter(statement -> statement.startsWith("select") && !statement.contains(" order by "))
                .toList();
    }

    private static int entityCount(EntityManager em) {
        return em.unwrap(Session.class).getStatistics().getEntityCount();
    }

    private static List<String> ids(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(String::valueOf).toList();
    }

    private static ModelQueryExecutor<InsListenedEntity> executor(EntityManager em) {
        return executor(em, ModelQueryConfig.defaults());
    }

    private static ModelQueryExecutor<InsListenedEntity> executor(EntityManager em, ModelQueryConfig config) {
        return ModelQueryExecutor.create(em, InsListenedEntity.class, config);
    }

    /**
     * Runs {@code work} in a transaction of a new session of the probe factory, after forgetting the statements
     * recorded so far, and commits it.
     */
    private static <T> T inCommittedTransaction(InsertProbes p, Function<EntityManager, T> work) {
        p.forget();
        return p.factory().fromTransaction(work::apply);
    }

    /** Runs {@code work} on a session of the probe factory with no transaction. */
    private static void withoutTransaction(InsertProbes p, Consumer<EntityManager> work) {
        p.factory().inSession(work::accept);
    }
}
