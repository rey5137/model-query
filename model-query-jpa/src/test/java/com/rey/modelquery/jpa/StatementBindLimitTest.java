package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A query's own statement over the bind limit is refused before it runs, never split (api/12 R-FLT-09, D-80). H2
 * takes far more binds than the {@code OTHER} profile's 2 000, so a refusal here is the library's, not the database's.
 */
class StatementBindLimitTest {

    record Row2(Long id, Integer a) {}

    record Pair(Integer first, Integer second) {}

    private static final TableField<BindLimitPairEntity, BindLimitPairEntity> PAIRS =
            TableField.root(BindLimitPairEntity.class);
    private static final ColumnField<Pair, BindLimitPairEntity, Integer> FIRST =
            ColumnField.of(Pair.class, PAIRS, "firstNo", Integer.class);
    private static final ColumnField<Pair, BindLimitPairEntity, Integer> SECOND =
            ColumnField.of(Pair.class, PAIRS, "secondNo", Integer.class);
    private static final ColumnField<Pair, BindLimitPairEntity, Integer> TAG =
            ColumnField.of(Pair.class, PAIRS, "tag", Integer.class);

    private static final ModelQuery.Builder<BindLimitPairEntity, Object, Pair> PAIR_ROWS = ModelQuery
            .builder(PAIRS, row -> new Pair(row.get(FIRST), row.get(SECOND)))
            .select(SelectSet.of(FIRST, SECOND));

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row2, KeysetRowEntity, Long> ID =
            ColumnField.of(Row2.class, ROOT, "id", Long.class);
    private static final ColumnField<Row2, KeysetRowEntity, Integer> A =
            ColumnField.of(Row2.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row2, KeysetRowEntity, Integer> B =
            ColumnField.of(Row2.class, ROOT, "b", Integer.class);

    private static final ModelQuery.Builder<KeysetRowEntity, Long, Row2> ROWS = ModelQuery
            .builder(ROOT, row -> new Row2(row.get(ID), row.get(A)))
            .select(SelectSet.of(ID, A))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());

    /** The {@code OTHER} profile: 2 000 binds per statement (R-VND-06). */
    private static final ModelQueryConfig OTHER = ModelQueryConfig.defaults().vendor(DatabaseVendor.OTHER);

    private static SessionFactory sessions;

    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
                .addAnnotatedClass(BindLimitPairEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:bindlimit;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .build());
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            for (long id = 1; id <= 3; id++) {
                em.persist(new KeysetRowEntity(id, (int) id, (int) id * 10, 0));
            }
            em.persist(new BindLimitPairEntity(1, 1, 1));
            em.persist(new BindLimitPairEntity(1, 2, 2));
            em.persist(new BindLimitPairEntity(2, 1, 3));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @Test
    void ac_prf_03_filters_that_only_together_pass_the_bind_limit_throw_mq1307_before_the_statement_runs() {
        var over = ROWS.where(f -> f.in(A, values(1_500)).in(B, values(501))).build();
        withExecutor(executor -> {
            assertThatThrownBy(() -> executor.list(over, Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1307))
                    .hasMessage(MqCode.MQ1307.code() + ": Row2: a statement binds 2001 values, more than the 2000 "
                            + "bind parameters one statement takes; narrow its filters, since a query's own statement "
                            + "is never split across statements");
            assertThatThrownBy(() -> executor.count(over))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1307));
        });
    }

    @Test
    void ac_prf_03_every_read_path_refuses_a_statement_over_the_bind_limit_with_mq1307() {
        var over = ROWS.where(f -> f.in(A, values(1_500)).in(B, values(501)));
        var offset = over.build();
        var keyset = over.keyset().build();
        withExecutor(executor -> {
            List<ThrowingCallable> reads = List.of(
                    () -> executor.page(offset, PageSpec.ofOffset(0, 2), CountMode.NO_COUNT),
                    () -> executor.page(offset, PageSpec.ofOffset(0, 2), CountMode.ONLY_COUNT),
                    () -> executor.page(keyset, PageSpec.ofOffset(0, 2), CountMode.NO_COUNT),
                    () -> executor.stream(offset, Limit.unlimited(), Stream::count),
                    () -> executor.export(offset, ExportOptions.of(2), page -> page, row -> { }),
                    () -> executor.export(keyset, ExportOptions.of(2), page -> page, row -> { }));
            assertThat(reads).allSatisfy(read -> assertThatThrownBy(read)
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1307)));
        });
    }

    @Test
    void ac_prf_03_filters_that_together_reach_the_bind_limit_run() {
        var atLimit = ROWS.where(f -> f.in(A, values(1_500)).in(B, values(500))).build();
        withExecutor(executor -> {
            assertThat(executor.list(atLimit, Limit.unlimited())).extracting(Row2::id).containsExactly(1L, 2L, 3L);
            assertThat(executor.count(atLimit)).isEqualTo(3);
        });
    }

    @Test
    void ac_prf_03_one_filter_over_the_bind_limit_still_throws_mq1306_naming_the_column() {
        var over = ROWS.where(f -> f.in(A, values(2_001))).build();
        withExecutor(executor -> assertThatThrownBy(() -> executor.list(over, Limit.unlimited()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1306))
                .hasMessageStartingWith(MqCode.MQ1306.code() + ": Row2.a: in(...) received 2001 values"));
    }

    @Test
    void ac_prf_03_a_write_whose_filters_together_pass_the_bind_limit_throws_mq1307_and_writes_nothing() {
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.in(A, values(1_500)).in(B, values(501))).build();
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, KeysetRowEntity.class, OTHER);
                assertThatThrownBy(() -> executor.delete(delete))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1307))
                        .hasMessageStartingWith(MqCode.MQ1307.code() + ": KeysetRowEntity: a statement binds 2001");
                assertThat(executor.count(ROWS.build())).isEqualTo(3);
            } finally {
                em.getTransaction().rollback();
            }
        }
    }

    /** The up-front refusal of a keyset statement whose own binds plus its worst cursor pass 2 000 by one (D-82). */
    private static String cursorRefusal(String label, int own, int worst) {
        return MqCode.MQ1307.code() + ": " + label + ": a keyset statement binds " + own + " values of its own, and "
                + "the keyset cursor's values can add up to " + worst + " more, over the 2000 bind parameters one "
                + "statement takes; narrow the query's own filters by at least 1 or use fewer keyset columns";
    }

    @Test
    void ac_prf_03_a_keyset_export_within_the_worst_cursor_of_the_limit_is_refused_before_any_row_reaches_the_sink() {
        // Own binds 2 000 fit the first page, which has no cursor, but one key's cursor takes 1 more (D-82).
        var fitsFirstPage = ROWS.where(f -> f.in(A, values(1_500)).in(B, values(500))).keyset().build();
        List<Row2> sunk = new ArrayList<>();
        withExecutor(executor -> assertThatThrownBy(
                () -> executor.export(fitsFirstPage, ExportOptions.of(2), page -> page, sunk::add))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1307))
                .hasMessage(cursorRefusal(fitsFirstPage.toString(), 2_000, 1)));
        assertThat(sunk).isEmpty();
    }

    @Test
    void ac_prf_03_a_keyset_export_whose_worst_cursor_still_fits_the_limit_runs_every_page() {
        var fits = ROWS.where(f -> f.in(A, values(1_500)).in(B, values(499))).keyset().build();
        List<Row2> sunk = new ArrayList<>();
        withExecutor(executor -> executor.export(fits, ExportOptions.of(2), page -> page, sunk::add));
        assertThat(sunk).extracting(Row2::id).containsExactly(1L, 2L, 3L);
    }

    /** Own binds 1 998: a two-key cursor takes at most 3 (k(k+1)/2), so one more than a statement takes. */
    private static ModelDelete<BindLimitPairEntity, Pair> pairDelete(boolean startAfter, boolean commitEachChunk) {
        var where = ModelDelete.builder(PAIRS).primaryKey(PrimaryKey.composite(FIRST, SECOND))
                .where(f -> f.in(TAG, values(1_500)).in(FIRST, values(498)));
        ChunkOptions options = commitEachChunk ? ChunkOptions.size(2).commitEachChunk() : ChunkOptions.size(2);
        return startAfter ? where.chunked(options, List.of(0, 0)).build() : where.chunked(options).build();
    }

    @Test
    void ac_prf_03_a_first_key_first_round_over_the_worst_cursor_is_refused_before_it_writes() {
        assertCursorRefused(pairDelete(false, false));
    }

    @Test
    void ac_prf_03_a_key_first_round_starting_after_a_key_is_refused_with_the_same_mq1307() {
        assertCursorRefused(pairDelete(true, false));
    }

    @Test
    void ac_prf_03_a_commit_each_chunk_write_over_the_worst_cursor_is_refused_before_any_round_commits() {
        ChunkTransactions resourceLocal = new ChunkTransactions() {
            @Override
            public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
                try (EntityManager own = emf.createEntityManager()) {
                    own.getTransaction().begin();
                    try {
                        T result = chunk.apply(own);
                        own.getTransaction().commit();
                        return result;
                    } catch (RuntimeException e) {
                        own.getTransaction().rollback();
                        throw e;
                    }
                }
            }
        };
        try (EntityManager em = sessions.createEntityManager()) {
            var executor = ModelQueryExecutor.create(em, BindLimitPairEntity.class,
                    OTHER.chunkTransactions(resourceLocal));
            // The refusal comes from the first round, wrapped as the failed chunk; nothing was committed before it.
            assertThatThrownBy(() -> executor.delete(pairDelete(false, true)))
                    .rootCause().hasMessageContaining(MqCode.MQ1307.code() + ": BindLimitPairEntity: a keyset statement binds "
                            + "1998 values of its own");
            assertThat(executor.count(PAIR_ROWS.build())).isEqualTo(3);
        }
    }

    private static void assertCursorRefused(ModelDelete<BindLimitPairEntity, Pair> rounds) {
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, BindLimitPairEntity.class, OTHER);
                assertThatThrownBy(() -> executor.delete(rounds))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1307))
                        .hasMessage(cursorRefusal("BindLimitPairEntity", 1_998, 3));
                assertThat(executor.count(PAIR_ROWS.build())).isEqualTo(3);
            } finally {
                em.getTransaction().rollback();
            }
        }
    }

    /** The values 1 to {@code count}, of which the seeded rows hold 1 to 3 in {@code a} and 10 to 30 in {@code b}. */
    private static List<Integer> values(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    private static void withExecutor(Consumer<ModelQueryExecutor<KeysetRowEntity>> work) {
        try (EntityManager em = sessions.createEntityManager()) {
            work.accept(ModelQueryExecutor.create(em, KeysetRowEntity.class, OTHER));
        }
    }
}
