package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.H2Dialect;
import org.junit.jupiter.api.Test;

/**
 * The provider's {@code inlinesValues} answer, from the session factory's criteria value handling mode, on a factory
 * booted without a connection; the TCK runs the filters under the setting on the real databases (spec vendor/40
 * R-VND-28, api/12 R-FLT-08; TCK AC-FLT-35).
 */
class HibernateInlinesValuesTest {

    private final HibernateProviderSupport support = new HibernateProviderSupport();

    @Test
    void ac_flt_35_value_handling_mode_inline_answers_true() {
        on("inline", sf -> assertThat(support.inlinesValues(sf)).isTrue());
        on("INLINE", sf -> assertThat(support.inlinesValues(sf)).isTrue());
    }

    @Test
    void ac_flt_36_the_default_and_bind_answer_false() {
        on(null, sf -> assertThat(support.inlinesValues(sf)).isFalse());
        on("bind", sf -> assertThat(support.inlinesValues(sf)).isFalse());
    }

    @Test
    void ac_flt_36_the_spi_default_and_another_providers_factory_answer_false() {
        EntityManagerFactory other = (EntityManagerFactory) Proxy.newProxyInstance(
                HibernateInlinesValuesTest.class.getClassLoader(), new Class<?>[] {EntityManagerFactory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unwrap" -> throw new PersistenceException("not a provider factory");
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertThat(support.inlinesValues(other)).isFalse();
        // The SPI's own default, which any provider without model-query-hibernate gets: never inlined (R-VND-28).
        on("inline", sf -> assertThat(new ProviderSupport() {
            @Override
            public boolean supports(EntityManagerFactory emf) {
                return true;
            }

            @Override
            public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
                return query.getResultStream();
            }
        }.inlinesValues(sf)).isFalse());
    }

    /** {@code work} on an H2 factory booted without touching a database, with {@code mode} as its value handling. */
    private static void on(String mode, Consumer<SessionFactory> work) {
        Configuration cfg = new Configuration();
        Map.of(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:inlines-values",
                AvailableSettings.DIALECT, H2Dialect.class.getName(),
                AvailableSettings.ALLOW_METADATA_ON_BOOT, "false",
                AvailableSettings.HBM2DDL_AUTO, "none").forEach(cfg::setProperty);
        if (mode != null) {
            cfg.setProperty("hibernate.criteria.value_handling_mode", mode);
        }
        try (SessionFactory sf = cfg.buildSessionFactory()) {
            work.accept(sf);
        }
    }
}
