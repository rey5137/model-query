package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.OperatorFeature;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.CockroachDialect;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.PostgresPlusDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;

/**
 * The {@code mq_*} functions {@link HibernateOperatorFunctions} registers per dialect, and the provider's
 * {@code operators} answer per dialect built on them: a factory booted without a connection per dialect, so every
 * vendor's answer is checked here, and the TCK checks the same answers on the real databases (spec vendor/40
 * R-VND-23; TCK AC-FLT-24).
 */
class HibernateOperatorFunctionsTest {

    private static final List<String> MQ_FUNCTIONS = List.of(HibernateOperatorFunctions.REGEX_MATCH,
            HibernateOperatorFunctions.ARRAY_CONTAINS, HibernateOperatorFunctions.ARRAY_OVERLAPS,
            HibernateOperatorFunctions.JSONB_CONTAINS);

    private final HibernateProviderSupport support = new HibernateProviderSupport();

    @Test
    void ac_flt_24_postgresql_serves_ilike_and_every_pattern_feature_but_full_text_through_its_registered_functions() {
        on(PostgreSQLDialect.class, sf -> {
            assertThat(MQ_FUNCTIONS).allSatisfy(f -> assertThat(registered(sf, f)).as(f).isTrue());
            assertThat(served(sf)).containsExactlyInAnyOrder(OperatorFeature.ILIKE, OperatorFeature.REGEX_MATCH,
                    OperatorFeature.ARRAY_CONTAINMENT, OperatorFeature.JSONB_CONTAINMENT);
        });
    }

    @Test
    void ac_flt_24_a_postgresql_dialect_subclass_is_served_as_postgresql() {
        on(PostgresPlusDialect.class, sf -> assertThat(served(sf)).containsExactlyInAnyOrder(OperatorFeature.ILIKE,
                OperatorFeature.REGEX_MATCH, OperatorFeature.ARRAY_CONTAINMENT, OperatorFeature.JSONB_CONTAINMENT));
    }

    @Test
    void ac_flt_24_h2_serves_ilike_alone_and_has_no_mq_function_never_hibernates_array_ones() {
        on(H2Dialect.class, sf -> {
            // Hibernate's own array_overlaps is registered on H2 and fails to prepare there; no mq_* function is.
            assertThat(registered(sf, "array_overlaps")).isTrue();
            assertThat(MQ_FUNCTIONS).allSatisfy(f -> assertThat(registered(sf, f)).as(f).isFalse());
            assertThat(served(sf)).containsExactly(OperatorFeature.ILIKE);
        });
    }

    @Test
    void ac_flt_24_mysql_mariadb_and_cockroachdb_serve_nothing_never_the_ilike_emulation() {
        for (Class<? extends Dialect> dialect : Set.of(MySQLDialect.class, MariaDBDialect.class,
                CockroachDialect.class)) {
            on(dialect, sf -> {
                assertThat(MQ_FUNCTIONS).allSatisfy(f -> assertThat(registered(sf, f)).as(f).isFalse());
                assertThat(served(sf)).as(dialect.getName()).isEmpty();
            });
        }
    }

    @Test
    void ac_flt_24_another_providers_factory_is_served_nothing() {
        EntityManagerFactory other = (EntityManagerFactory) Proxy.newProxyInstance(
                HibernateOperatorFunctionsTest.class.getClassLoader(), new Class<?>[] {EntityManagerFactory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unwrap" -> throw new PersistenceException("not a provider factory");
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        for (OperatorFeature feature : OperatorFeature.values()) {
            assertThat(support.operators(other, feature)).as(feature.name()).isFalse();
        }
    }

    private Set<OperatorFeature> served(SessionFactory sf) {
        Set<OperatorFeature> served = EnumSet.noneOf(OperatorFeature.class);
        for (OperatorFeature feature : OperatorFeature.values()) {
            if (support.operators(sf, feature)) {
                served.add(feature);
            }
        }
        return served;
    }

    private static boolean registered(SessionFactory sf, String function) {
        return sf.unwrap(SessionFactoryImplementor.class).getQueryEngine().getSqmFunctionRegistry()
                .findFunctionDescriptor(function) != null;
    }

    /** {@code work} on a factory of {@code dialect}, booted without reading JDBC metadata or touching a database. */
    private static void on(Class<? extends Dialect> dialect, Consumer<SessionFactory> work) {
        Configuration cfg = new Configuration();
        Map.of(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:operator-functions",
                AvailableSettings.DIALECT, dialect.getName(),
                AvailableSettings.ALLOW_METADATA_ON_BOOT, "false",
                AvailableSettings.HBM2DDL_AUTO, "none").forEach(cfg::setProperty);
        try (SessionFactory sf = cfg.buildSessionFactory()) {
            work.accept(sf);
        }
    }
}
