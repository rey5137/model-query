package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.ins.InsListenedEntity;
import com.rey.modelquery.tck.vnd.ins.InsParentEntity;
import com.rey.modelquery.tck.vnd.ins.InsSoftDeletedEntity;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;

/**
 * A delete {@code throughEntities()} on every Tier-1 vendor: each chunk's keys selected as a chunked delete selects
 * them, its entities loaded with one select, removed through the {@code EntityManager} and flushed, so JPA callbacks,
 * Hibernate event listeners, cascades ({@code REMOVE}, {@code orphanRemoval}) and {@code @SQLDelete} run for each row;
 * the count is the entities matched; the persistence context cleared per chunk; an {@code OptimisticLockException} a
 * failed chunk to resume after (spec api/14 R-WRT-41 to R-WRT-47). The Hibernate listener lives here, never in
 * {@code model-query-jpa} (INV-7).
 */
class EntityDeleteTest {

    /** A delete model over {@link InsListenedEntity}. */
    record Gone(Long id, String code) {}

    /** A delete model over {@link InsParentEntity} or {@link InsSoftDeletedEntity}. */
    record Key(Long id) {}

    private static final TableField<InsListenedEntity, InsListenedEntity> ROOT =
            TableField.root(InsListenedEntity.class);
    private static final TableField<InsListenedEntity, InsSourceEntity> SOURCE = TableField.join(ROOT, "source", INNER);
    private static final ColumnField<Gone, InsListenedEntity, Long> ID =
            ColumnField.of(Gone.class, ROOT, "id", Long.class);
    private static final ColumnField<Gone, InsSourceEntity, String> SOURCE_CODE =
            ColumnField.of(Gone.class, SOURCE, "code", String.class);

    private static final TableField<InsParentEntity, InsParentEntity> PARENT = TableField.root(InsParentEntity.class);
    private static final ColumnField<Key, InsParentEntity, Long> PARENT_ID =
            ColumnField.of(Key.class, PARENT, "id", Long.class);

    private static final TableField<InsSoftDeletedEntity, InsSoftDeletedEntity> SOFT =
            TableField.root(InsSoftDeletedEntity.class);
    private static final ColumnField<Key, InsSoftDeletedEntity, Long> SOFT_ID =
            ColumnField.of(Key.class, SOFT, "id", Long.class);

    private static final String IDS = "select id from ins_listened order by id";

    /** Records the id of each Hibernate post-delete event. */
    private static final class PostDeletes implements PostDeleteEventListener {
        final List<Object> events = new ArrayList<>();

        @Override
        public void onPostDelete(PostDeleteEvent event) {
            events.add(event.getId());
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

    // ---- AC-WRT-35

    @TckTest
    void ac_wrt_35_an_entity_mode_delete_fires_remove_callbacks_and_the_hibernate_listener_once_per_row(
            TckDatabase db) {
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).where(f -> f.lte(ID, 3L))
                .throughEntities().build();
        var listener = new PostDeletes();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 5);
            p.factory().unwrap(SessionFactoryImplementor.class).getServiceRegistry()
                    .requireService(EventListenerRegistry.class).appendListeners(EventType.POST_DELETE, listener);
            long deleted = inCommittedTransaction(p, em -> executor(em).delete(delete));

            assertThat(deleted).isEqualTo(3);
            assertThat(InsListenedEntity.CALLBACKS).containsExactlyInAnyOrder("preRemove:1", "postRemove:1",
                    "preRemove:2", "postRemove:2", "preRemove:3", "postRemove:3");
            assertThat(listener.events).containsExactlyInAnyOrder(1L, 2L, 3L);
            assertThat(writes(p.statements(), "delete")).hasSize(3);
            assertThat(p.rows(IDS)).containsExactly("4", "5");
        }
    }

    @TckTest
    void ac_wrt_35_an_entity_mode_delete_cascades_remove_and_orphan_removal_and_counts_the_entities_matched(
            TckDatabase db) {
        var delete = ModelDelete.builder(PARENT).primaryKey(PrimaryKey.of(PARENT_ID)).where(f -> f.lte(PARENT_ID, 2L))
                .throughEntities().build();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            p.jdbc("insert into ins_parent (id, name) values (1, 'p1'), (2, 'p2'), (3, 'p3')");
            // Children 1, 2 and 4 cascade by REMOVE, 3 and 5 by orphanRemoval; 6 and 7 belong to parent 3.
            p.jdbc("insert into ins_child (id, parent_id, holder_id) values (1, 1, null), (2, 1, null), (3, null, 1), "
                    + "(4, 2, null), (5, null, 2), (6, 3, null), (7, null, 3)");
            long deleted = inCommittedTransaction(p, em -> executor(em, InsParentEntity.class).delete(delete));

            // The two parents matched, not the seven rows removed with them.
            assertThat(deleted).isEqualTo(2);
            assertThat(p.rows("select id from ins_parent order by id")).containsExactly("3");
            assertThat(p.rows("select id from ins_child order by id")).containsExactly("6", "7");
        }
    }

    @TckTest
    void ac_wrt_35_an_entity_mode_delete_runs_the_mappings_sql_delete(TckDatabase db) {
        var delete = ModelDelete.builder(SOFT).primaryKey(PrimaryKey.of(SOFT_ID)).whereKeys(List.of(1L, 2L))
                .throughEntities().build();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            p.jdbc("insert into ins_soft_deleted (id, name, deleted) values (1, 'a', false), (2, 'b', false), "
                    + "(3, 'c', false)");
            long deleted = inCommittedTransaction(p, em -> executor(em, InsSoftDeletedEntity.class).delete(delete));

            assertThat(deleted).isEqualTo(2);
            // The @SQLDelete marked the rows instead of deleting them.
            assertThat(writes(p.statements(), "delete")).isEmpty();
            assertThat(writes(p.statements(), "update")).hasSize(2);
            assertThat(p.rows("select id from ins_soft_deleted where deleted = true order by id"))
                    .containsExactly("1", "2");
            assertThat(p.rows("select count(*) from ins_soft_deleted")).containsExactly("3");
        }
    }

    // ---- AC-WRT-36

    @TckTest
    void ac_wrt_36_each_chunk_loads_with_one_select_and_clear_holds_at_most_one_chunk_of_entities(TckDatabase db) {
        // The where joins the source, so the load renders it in EXISTS and each entity comes once.
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.eq(SOURCE_CODE, "s1").lte(ID, 5L)).throughEntities().chunked(ChunkOptions.size(2))
                .build();
        var managed = new ArrayList<Integer>();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 6);
            long deleted = inCommittedTransaction(p, em -> {
                InsListenedEntity.beforeRemove = entity -> managed.add(entityCount(em));
                long rows = executor(em).delete(delete);
                assertThat(entityCount(em)).isZero();
                return rows;
            });

            assertThat(deleted).isEqualTo(5);
            List<String> sql = SqlSnapshots.assertMatches(db, "wrt-36-entity-delete-chunks", p.statements());
            // Three key selects of 2, 2 and 1 keys, and one load after each.
            assertThat(loads(sql)).hasSize(3);
            assertThat(writes(sql, "delete")).hasSize(5);
            assertThat(managed).hasSize(5).allMatch(count -> count <= 2);
            assertThat(p.rows(IDS)).containsExactly("6");
        }
    }

    @TckTest
    void ac_wrt_36_commit_each_chunk_resumes_from_chunked_write_exception_after_an_optimistic_lock_failure(
            TckDatabase db) {
        var options = ChunkOptions.size(10).commitEachChunk();
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).all().throughEntities().chunked(options)
                .build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());
        var bumped = new AtomicBoolean();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 30);
            // Between the third chunk's load and its flush, another transaction moves row 25's version.
            InsListenedEntity.beforeRemove = entity -> {
                if (entity.id() == 25L && bumped.compareAndSet(false, true)) {
                    p.jdbc("update ins_listened set version = version + 1 where id = 25");
                }
            };
            ChunkedWriteException[] failure = new ChunkedWriteException[1];
            withoutTransaction(p, em -> assertThatThrownBy(() -> executor(em, config).delete(delete))
                    .isInstanceOfSatisfying(ChunkedWriteException.class, e -> failure[0] = e));

            assertThat(failure[0].code()).isEqualTo(MqCode.MQ2502);
            assertThat(failure[0]).hasCauseInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("the 2 chunks before it stay committed, 20 rows");
            assertThat(failure[0].committedRows()).isEqualTo(20);
            assertThat(failure[0].lastCommittedKey()).contains(20L);
            assertThat(failure[0].inDoubtKeys()).isEmpty();
            assertThat(p.rows(IDS)).containsExactlyElementsOf(ids(21, 30));

            var resume = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).all().throughEntities()
                    .chunked(options, (Long) failure[0].lastCommittedKey().orElseThrow()).build();
            long[] resumed = new long[1];
            withoutTransaction(p, em -> resumed[0] = executor(em, config).delete(resume));

            // Row 25 was removed at the version the other transaction left.
            assertThat(resumed[0]).isEqualTo(10);
            assertThat(p.rows(IDS)).isEmpty();
        }
    }

    @TckTest
    void ac_wrt_36_in_the_callers_transaction_an_optimistic_lock_failure_reaches_the_caller_unwrapped(TckDatabase db) {
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).all().throughEntities().build();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 3);
            // Between the load and the flush, another transaction moves row 2's version.
            InsListenedEntity.beforeRemove = entity -> {
                if (entity.id() == 2L) {
                    p.jdbc("update ins_listened set version = version + 1 where id = 2");
                }
            };
            // Not committing each chunk, the failure is the caller's own, with no ChunkedWriteException around it.
            assertThatThrownBy(() -> inCommittedTransaction(p, em -> executor(em).delete(delete)))
                    .isInstanceOf(OptimisticLockException.class);

            // The caller's transaction rolled back: every row stays, row 2 at the other transaction's version.
            assertThat(p.rows("select id, version from ins_listened order by id")).containsExactly("1|0", "2|1",
                    "3|0");
        }
    }

    @TckTest
    void ac_wrt_36_commit_each_chunk_under_clear_clears_the_callers_entity_manager_after_the_last_chunk(
            TckDatabase db) {
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).all().throughEntities()
                .chunked(ChunkOptions.size(2).commitEachChunk()).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 3);
            withoutTransaction(p, em -> {
                InsListenedEntity one = em.find(InsListenedEntity.class, 1L);
                assertThat(executor(em, config).delete(delete)).isEqualTo(3);

                // Each chunk removed on its own EntityManager, so the caller's stale copy is detached and a find
                // reads the row as gone.
                assertThat(em.contains(one)).isFalse();
                assertThat(entityCount(em)).isZero();
                assertThat(em.find(InsListenedEntity.class, 1L)).isNull();
            });
        }
    }

    @TckTest
    void ac_wrt_36_an_entity_mode_delete_without_a_transaction_throws_mq2501_before_any_statement(TckDatabase db) {
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).all().throughEntities().build();

        try (InsertProbes p = InsertProbes.withEntityWriteRoots(db)) {
            seed(p, 2);
            p.forget();
            withoutTransaction(p, em -> assertThatThrownBy(() -> executor(em).delete(delete))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2501))
                    .hasMessageContaining("an entity-mode delete needs an active transaction"));

            assertThat(p.statements()).isEmpty();
            assertThat(p.rows(IDS)).containsExactly("1", "2");
        }
    }

    /** Commits rows {@code 1} to {@code n}, each NEW, with its id as its amount, source 1 and version 0. */
    private static void seed(InsertProbes p, int n) {
        p.jdbc("insert into ins_listened (id, status, amount, note, source_id, version) values "
                + IntStream.rangeClosed(1, n).mapToObj(id -> "(" + id + ", 'NEW', " + id + ", null, 1, 0)")
                        .collect(Collectors.joining(", ")));
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

    private static <T> ModelQueryExecutor<T> executor(EntityManager em, Class<T> root) {
        return ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults());
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
