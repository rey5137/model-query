package com.rey.modelquery.jpa;

import static com.rey.modelquery.core.ColumnField.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.ReturningShape;
import com.rey.modelquery.jpa.spi.ReturningSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
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
 * {@code updateReturning} and {@code deleteReturning} in the engine (spec api/14 R-WRT-50, engine/20 §9): the R-WRT-48
 * clause and provider refusals ({@code MQ1809}, {@code MQ4011}) all before any statement, and the rows a serving
 * provider returns mapped into the query's model. The real Hibernate translation and the shapes of vendor/41 §7 are
 * the TCK's (AC-WRT-42, AC-VND-14).
 */
class ReturningWriteExecutionTest {

    record Row(Long id, Integer a) {}

    private static final String MARKER = "model-query.test.returning";
    private static final String NO_RETURNING = "model-query.test.no-returning";
    private static final String MULTI_TABLE = "model-query.test.multi-table";

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);

    private static final ColumnField<Row, KeysetRowEntity, Long> ID = of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> A = of(Row.class, ROOT, "a", Integer.class);

    private static final ModelQuery.Builder<KeysetRowEntity, Long, Row> RETURNING =
            ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(A))).select(SelectSet.of(ID, A))
                    .primaryKey(PrimaryKey.of(ID));

    private static final InsertColumns<Row, KeysetRowEntity> INSERT_COLUMNS =
            InsertColumns.<Row, KeysetRowEntity>of(ROOT).addKey(ID, Row::id).add(A, Row::a);

    private static final ModelQueryConfig H2 = ModelQueryConfig.defaults().vendor(DatabaseVendor.H2);
    private static final ModelQueryConfig MYSQL = ModelQueryConfig.defaults().vendor(DatabaseVendor.MYSQL);

    /** Every statement any factory of this test ran, so a refusal can be shown to run before any. */
    private static final List<String> SQL = Collections.synchronizedList(new ArrayList<>());

    private static SessionFactory sessions;

    @BeforeAll
    static void seed() {
        sessions = factory("returning", MARKER);
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new KeysetRowEntity(1, 10, 10, 0));
            em.persist(new KeysetRowEntity(2, 20, 20, 0));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @BeforeEach
    void forget() {
        SQL.clear();
        Returning.CALLS.clear();
        Returning.NEXT.set(0);
        Returning.distinct = false;
    }

    // ---- R-WRT-48 clauses are MQ1809, for every statement

    @Test
    void ac_wrt_42_a_returning_query_with_any_r_wrt_48_clause_throws_mq1809_before_any_statement() {
        Map<String, ModelQuery<KeysetRowEntity, ?, Row>> refused = clauses();
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            ModelQueryExecutor<KeysetRowEntity> executor = ModelQueryExecutor.create(em, KeysetRowEntity.class, H2);
            refused.forEach((named, query) -> {
                assertRefused(() -> executor.updateReturning(update(), query), named, "updateReturning");
                assertRefused(() -> executor.deleteReturning(delete(), query), named, "deleteReturning");
                assertRefused(() -> executor.insertReturning(insert(), query), named, "insertReturning");
            });
            assertThat(SQL).isEmpty();
            em.getTransaction().rollback();
        }
    }

    @Test
    void ac_wrt_42_a_through_entities_or_commit_each_chunk_write_throws_mq1809_before_any_statement() {
        ModelUpdate<KeysetRowEntity, Row> entities = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(A, 7)
                .where(f -> f.eq(ID, 1L)).throughEntities().build();
        ModelUpdate<KeysetRowEntity, Row> committed = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(A, 7)
                .where(f -> f.eq(ID, 1L)).chunked(ChunkOptions.size(1).commitEachChunk()).build();
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            ModelQueryExecutor<KeysetRowEntity> executor = ModelQueryExecutor.create(em, KeysetRowEntity.class, H2);
            for (ModelUpdate<KeysetRowEntity, Row> update : List.of(entities, committed)) {
                assertThatThrownBy(() -> executor.updateReturning(update, RETURNING.build()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1809))
                        .hasMessageContaining("returning cannot combine with");
            }
            assertThatThrownBy(() -> executor.insertReturning(ValuesInsert.builder(INSERT_COLUMNS, Long.class,
                    List.of(new Row(3L, 30), new Row(4L, 40))).chunked(ChunkOptions.size(1).commitEachChunk()).build(),
                    RETURNING.build()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1809))
                    .hasMessage("MQ1809: INSERT returning cannot combine with commitEachChunk(): the rows of a "
                            + "callback's own writes, or of a committed chunk, are not this statement's");
            assertThat(SQL).isEmpty();
            assertThat(Returning.CALLS).isEmpty();
            em.getTransaction().rollback();
        }
    }

    // ---- which factories refuse a returning write

    @Test
    void ac_wrt_42_43_a_factory_that_cannot_return_rows_throws_mq4011_before_any_statement() {
        withTransaction(sessions, em -> assertMq4011(ModelQueryExecutor.create(em, KeysetRowEntity.class, MYSQL),
                "the vendor profile reports no returning shape for it (VendorProfile#returning)"));
        try (SessionFactory none = factory("none", NO_RETURNING)) {
            withTransaction(none, em -> assertMq4011(ModelQueryExecutor.create(em, KeysetRowEntity.class, H2),
                    "has no ReturningSupport (ProviderSupport#returning())"));
        }
        try (SessionFactory multi = factory("multi", MULTI_TABLE)) {
            withTransaction(multi, em -> assertMq4011(ModelQueryExecutor.create(em, KeysetRowEntity.class, H2),
                    "cannot return rows for KeysetRowEntity (ReturningSupport#rendered)"));
        }
        assertThat(SQL).isEmpty();
        assertThat(Returning.CALLS).isEmpty();
    }

    // ---- the rows a serving provider returns

    @Test
    void ac_wrt_42_the_rows_a_provider_returns_are_mapped_into_the_query_model() {
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            ModelQueryExecutor<KeysetRowEntity> executor = ModelQueryExecutor.create(em, KeysetRowEntity.class, H2);
            List<Row> updated = executor.updateReturning(update(), RETURNING.build());
            List<Row> deleted = executor.deleteReturning(delete(), RETURNING.build());
            assertThat(updated).containsExactly(new Row(1L, 7));
            assertThat(deleted).containsExactly(new Row(1L, 7));
            assertThat(Returning.CALLS).containsExactly(List.of("id", "a"), List.of("id", "a"));
            em.getTransaction().rollback();
        }
    }

    @Test
    void ac_wrt_42_a_chunked_write_concatenates_its_chunks_rows_in_statement_order_with_no_row_twice() {
        ModelUpdate<KeysetRowEntity, Row> chunked = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(A, 7)
                .where(f -> f.lte(ID, 2L)).chunked(ChunkOptions.size(1)).build();
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            ModelQueryExecutor<KeysetRowEntity> executor = ModelQueryExecutor.create(em, KeysetRowEntity.class, H2);
            Returning.distinct = true; // statement n returns row n, so a dropped or repeated chunk shows
            List<Row> rows = executor.updateReturning(chunked, RETURNING.build());
            assertThat(rows).containsExactly(new Row(1L, 7), new Row(2L, 7));
            assertThat(Returning.CALLS).hasSize(2);
            em.getTransaction().rollback();
        }
    }

    // ---- helpers

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String named,
            String which) {
        assertThatThrownBy(call).as("%s + %s", which, named)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1809))
                .hasMessageContaining(named);
    }

    private static void assertMq4011(ModelQueryExecutor<KeysetRowEntity> executor, String because) {
        assertThatThrownBy(() -> executor.updateReturning(update(), RETURNING.build()))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                .hasMessageContaining("MQ4011: KeysetRowEntity: UPDATE returning cannot run")
                .hasMessageContaining(because);
        assertThatThrownBy(() -> executor.deleteReturning(delete(), RETURNING.build()))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                .hasMessageContaining("MQ4011: KeysetRowEntity: DELETE returning cannot run")
                .hasMessageContaining(because);
        assertThatThrownBy(() -> executor.insertReturning(insert(), RETURNING.build()))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                .hasMessageContaining("MQ4011: KeysetRowEntity: INSERT returning cannot run")
                .hasMessageContaining(because);
    }

    /** Every R-WRT-48 clause, each named as {@code checkReturning} names it. */
    private static Map<String, ModelQuery<KeysetRowEntity, ?, Row>> clauses() {
        Map<String, ModelQuery<KeysetRowEntity, ?, Row>> refused = new LinkedHashMap<>();
        refused.put("where(...)", RETURNING.where(f -> f.eq(A, 1)).build());
        refused.put("having(...)", ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(A)))
                .select(SelectSet.of(A)).primaryKey(PrimaryKey.of(ID)).groupBy(A)
                .having(h -> h.gt(Agg.<Row>count(ROOT), 0L)).build());
        refused.put("groupBy(...)", ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(A)))
                .select(SelectSet.of(A)).primaryKey(PrimaryKey.of(ID)).groupBy(A).build());
        refused.put("a fetch plan", RETURNING.fetch(FetchPlan.of(SelectSet.of(ID, A))).build());
        refused.put("customize(...)", RETURNING.customize((spec, joins, query, cb, phase) -> { }).build());
        refused.put("orderBy(...)", RETURNING.orderBy(ID.asc()).build());
        refused.put("keyset()", RETURNING.keyset().build());
        refused.put("primaryKeyFirst(...)", RETURNING.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100)).build());
        refused.put("a lock", RETURNING.lock(RowLock.exclusive()).build());
        return refused;
    }

    private static ModelUpdate<KeysetRowEntity, Row> update() {
        return ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(A, 7).where(f -> f.eq(ID, 1L)).build();
    }

    private static ModelInsert<KeysetRowEntity, Row> insert() {
        return ValuesInsert.builder(INSERT_COLUMNS, Long.class, List.of(new Row(3L, 30))).build();
    }

    private static ModelDelete<KeysetRowEntity, Row> delete() {
        return ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).where(f -> f.eq(ID, 1L)).build();
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

    private static SessionFactory factory(String name, String marker) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:returning-write-" + name)
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
        return new Configuration().addAnnotatedClass(KeysetRowEntity.class).buildSessionFactory(registry.build());
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

    /** A provider that returns the rows of every statement it is asked for. */
    public static final class Returning extends Stub {

        /** The attributes the engine asked this provider for, one entry per statement it returned rows for. */
        static final List<List<String>> CALLS = Collections.synchronizedList(new ArrayList<>());

        /** The row the next statement describes: with {@link #distinct}, the nth statement returns the row id n. */
        static final AtomicInteger NEXT = new AtomicInteger();

        /** Whether each statement returns its own row, so a chunked write's statements differ ({@link #NEXT}). */
        static volatile boolean distinct;

        public Returning() {
            super(MARKER);
        }

        /** Also the multi-table factory, so its refusal comes from {@code rendered} and not from a missing provider. */
        @Override
        public boolean supports(EntityManagerFactory emf) {
            return super.supports(emf) || emf.getProperties().containsKey(MULTI_TABLE);
        }

        @Override
        public Optional<ReturningSupport> returning() {
            return Optional.of(new ReturningSupport() {
                @Override
                public boolean rendered(EntityManagerFactory emf, Class<?> entity, ReturningShape shape) {
                    return !emf.getProperties().containsKey(MULTI_TABLE);
                }

                @Override
                public List<Object[]> rows(Query statement, Class<?> entity, List<String> attributes,
                        ReturningShape shape) {
                    CALLS.add(List.copyOf(attributes));
                    long id = distinct ? NEXT.incrementAndGet() : 1L;
                    return List.<Object[]>of(new Object[] {id, 7});
                }
            });
        }
    }

    /** A provider that supports nothing beyond JPA, so a returning write is {@code MQ4011}. */
    public static final class NoReturning extends Stub {

        public NoReturning() {
            super(NO_RETURNING);
        }
    }
}
