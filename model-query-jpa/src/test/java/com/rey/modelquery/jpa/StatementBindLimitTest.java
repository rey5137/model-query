package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Consumer;
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

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row2, KeysetRowEntity, Long> ID =
            ColumnField.of(Row2.class, ROOT, "id", Long.class);
    private static final ColumnField<Row2, KeysetRowEntity, Integer> A =
            ColumnField.of(Row2.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row2, KeysetRowEntity, Integer> B =
            ColumnField.of(Row2.class, ROOT, "b", Integer.class);

    private static final ModelQuery.Builder<KeysetRowEntity, Long, Row2> ROWS = ModelQuery
            .builder(ROOT, row -> new Row2(row.get(ID), row.get(A)))
            .columns(ColumnSet.of(ID, A))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());

    /** The {@code OTHER} profile: 2 000 binds per statement (R-VND-06). */
    private static final ModelQueryConfig OTHER = ModelQueryConfig.defaults().vendor(DatabaseVendor.OTHER);

    private static SessionFactory sessions;

    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
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
                    () -> executor.page(offset, new PageSpec(0, 2), CountMode.NO_COUNT),
                    () -> executor.page(offset, new PageSpec(0, 2), CountMode.ONLY_COUNT),
                    () -> executor.page(keyset, new PageSpec(0, 2), CountMode.NO_COUNT),
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
