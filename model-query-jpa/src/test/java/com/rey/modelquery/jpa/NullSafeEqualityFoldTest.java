package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.NullSafeEqualityRenderer;
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
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The executor offers a provider's {@link NullSafeEqualityRenderer} only where the vendor profile says the database
 * has a native null-safe equality; with no hook, or on a vendor without the fact, the two-column form renders the
 * portable OR form (R-VND-29, R-VND-30, R-FLT-29). The stub hook renders "no row", so the rows returned show
 * which form ran.
 */
class NullSafeEqualityFoldTest {

    record Row(Long id) {}

    private static final String NATIVE_VENDOR = "modelquery.test.nse-native";
    private static final String NO_HOOK = "modelquery.test.nse-nohook";
    private static final String PLAIN_VENDOR = "modelquery.test.nse-plain";

    /** A hook that matches nothing; H2 and PostgreSQL stand for a vendor with and one without the fact. */
    public static final class Support implements ProviderSupport {
        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(NATIVE_VENDOR) || emf.getProperties().containsKey(PLAIN_VENDOR);
        }

        @Override
        public Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
            return Optional.of(emf.getProperties().containsKey(PLAIN_VENDOR) ? DatabaseVendor.POSTGRESQL
                    : DatabaseVendor.H2);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }

        @Override
        public Optional<NullSafeEqualityRenderer> nullSafeEquality() {
            return Optional.of(new NullSafeEqualityRenderer() {
                @Override
                public Predicate notDistinctFrom(CriteriaBuilder cb, Expression<?> a, Expression<?> b) {
                    return cb.disjunction();
                }

                @Override
                public Predicate distinctFrom(CriteriaBuilder cb, Expression<?> a, Expression<?> b) {
                    return cb.disjunction();
                }
            });
        }
    }

    /** The same vendor as the native one, with a provider that has no hook. */
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
    private static final ColumnField<Row, KeysetRowEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> A =
            ColumnField.of(Row.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> B =
            ColumnField.of(Row.class, ROOT, "b", Integer.class);

    private static SessionFactory nativeVendor;
    private static SessionFactory noHook;
    private static SessionFactory plainVendor;

    @BeforeAll
    static void seed() {
        nativeVendor = factory(NATIVE_VENDOR, "nse-native");
        noHook = factory(NO_HOOK, "nse-nohook");
        plainVendor = factory(PLAIN_VENDOR, "nse-plain");
        for (SessionFactory factory : List.of(nativeVendor, noHook, plainVendor)) {
            try (EntityManager em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new KeysetRowEntity(1, 1, 1, 0)); // equal
                em.persist(new KeysetRowEntity(2, null, null, 0)); // both NULL
                em.persist(new KeysetRowEntity(3, 1, null, 0)); // one NULL
                em.persist(new KeysetRowEntity(4, 1, 2, 0)); // different
                em.getTransaction().commit();
            }
        }
    }

    @AfterAll
    static void close() {
        nativeVendor.close();
        noHook.close();
        plainVendor.close();
    }

    @Test
    void ac_flt_38_a_vendor_with_the_fact_and_a_provider_with_the_hook_renders_the_native_form() {
        assertThat(ids(nativeVendor, true)).isEmpty();
        assertThat(ids(nativeVendor, false)).isEmpty();
    }

    @Test
    void ac_flt_38_a_vendor_with_the_fact_and_no_hook_renders_the_portable_form_silently() {
        assertThat(ids(noHook, true)).containsExactly(1L, 2L);
        assertThat(ids(noHook, false)).containsExactly(3L, 4L);
    }

    @Test
    void ac_flt_38_a_vendor_without_the_fact_renders_the_portable_form_though_the_provider_has_the_hook() {
        assertThat(ids(plainVendor, true)).containsExactly(1L, 2L);
        assertThat(ids(plainVendor, false)).containsExactly(3L, 4L);
    }

    private static List<Long> ids(SessionFactory factory, boolean notDistinct) {
        ModelQuery<KeysetRowEntity, ?, Row> q = ModelQuery.builder(ROOT, row -> new Row(row.get(ID)))
                .select(SelectSet.of(ID))
                .where(f -> notDistinct ? f.notDistinctFromColumn(A, B) : f.distinctFromColumn(A, B))
                .orderBy(ID.asc())
                .build();
        try (EntityManager em = factory.createEntityManager()) {
            return ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults())
                    .list(q, Limit.unlimited()).stream().map(Row::id).toList();
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
