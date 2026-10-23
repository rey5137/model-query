package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.AggregateRenderer;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The executor offers a provider's {@link AggregateRenderer} to a filtered aggregate, and with none the engine's CASE
 * form runs to the same answer (R-AGG-17, R-VND-30).
 */
class AggregateFoldTest {

    record Row(Long sum) {}

    private static final String WITH_HOOK = "modelquery.test.agg-hook";
    private static final String NO_HOOK = "modelquery.test.agg-nohook";
    private static final AtomicInteger CALLS = new AtomicInteger();

    /** A hook that counts its calls and renders the default form. */
    public static final class Support implements ProviderSupport {
        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(WITH_HOOK);
        }

        @Override
        public Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
            return Optional.of(DatabaseVendor.H2);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }

        @Override
        public Optional<AggregateRenderer> aggregates() {
            return Optional.of(new AggregateRenderer() {
                @Override
                public Expression<?> filtered(CriteriaBuilder cb, AggregateField.Kind kind, Expression<?> argument,
                        Predicate condition) {
                    CALLS.incrementAndGet();
                    return AggregateRenderer.super.filtered(cb, kind, argument, condition);
                }
            });
        }
    }

    /** The same vendor with a provider that has no aggregate hook. */
    public static final class NoHook implements ProviderSupport {
        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(NO_HOOK);
        }

        @Override
        public Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
            return Optional.of(DatabaseVendor.H2);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }
    }

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> A =
            ColumnField.of(Row.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> B =
            ColumnField.of(Row.class, ROOT, "b", Integer.class);
    private static final AggregateField<Row, Long> SUM = Agg.sumAsLong(A).filter(f -> f.eq(B, 1));

    private static final AggregateField<Row, Long> COUNT = Agg.<Row>count(ROOT).filter(f -> f.eq(B, 1));

    private static SessionFactory withHook;
    private static SessionFactory noHook;

    @BeforeAll
    static void seed() {
        withHook = factory(WITH_HOOK, "agg-hook");
        noHook = factory(NO_HOOK, "agg-nohook");
        for (SessionFactory factory : List.of(withHook, noHook)) {
            try (EntityManager em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new KeysetRowEntity(1, 10, 1, 0));
                em.persist(new KeysetRowEntity(2, 20, 2, 0));
                em.persist(new KeysetRowEntity(3, 30, 1, 0));
                em.getTransaction().commit();
            }
        }
    }

    @AfterAll
    static void close() {
        withHook.close();
        noHook.close();
    }

    @Test
    void ac_agg_21_a_provider_hook_is_offered_the_filtered_aggregate() {
        CALLS.set(0);
        assertThat(sum(withHook)).isEqualTo(40L);
        assertThat(CALLS.get()).isPositive();
    }

    @Test
    void ac_agg_21_without_a_hook_the_engine_case_form_runs_to_the_same_answer() {
        CALLS.set(0);
        assertThat(sum(noHook)).isEqualTo(40L);
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void ac_agg_21_a_filtered_table_count_runs_in_the_engine_case_form_and_with_a_hook() {
        assertThat(sum(noHook, COUNT)).isEqualTo(2L);
        assertThat(sum(withHook, COUNT)).isEqualTo(2L);
    }

    @Test
    void ac_agg_23_an_exact_average_without_the_providers_renderer_is_mq4014() {
        AggregateField<Row, java.math.BigDecimal> average = Agg.avgExact(A);
        ModelQuery<KeysetRowEntity, ?, Row> q = ModelQuery.builder(ROOT, row -> new Row(row.get(average).longValue()))
                .select(SelectSet.of(average)).build();
        try (EntityManager em = noHook.createEntityManager()) {
            var executor = ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults());
            assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                    .isInstanceOfSatisfying(com.rey.modelquery.core.ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(com.rey.modelquery.core.MqCode.MQ4014));
        }
    }

    private static Long sum(SessionFactory factory) {
        return sum(factory, SUM);
    }

    private static Long sum(SessionFactory factory, AggregateField<Row, Long> field) {
        ModelQuery<KeysetRowEntity, ?, Row> q = ModelQuery.builder(ROOT, row -> new Row(row.get(field)))
                .select(SelectSet.of(field)).build();
        try (EntityManager em = factory.createEntityManager()) {
            return ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults())
                    .list(q, Limit.unlimited()).get(0).sum();
        }
    }

    private static SessionFactory factory(String marker, String name) {
        return new Configuration().addAnnotatedClass(KeysetRowEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .applySetting(marker, "true")
                        .build());
    }
}
