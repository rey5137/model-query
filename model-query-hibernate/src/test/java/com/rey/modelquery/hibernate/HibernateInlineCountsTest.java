package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.junit.jupiter.api.Test;

/**
 * The provider's {@code inlineCounts} answer, from the dialect's {@code supportsWindowFunctions()}: a factory booted
 * without a connection per dialect; the TCK checks the window on the real databases (spec vendor/40 R-VND-27,
 * engine/20 R-EXE-15; TCK AC-EXE-18).
 */
class HibernateInlineCountsTest {

    /** A dialect that renders no window function, as an older database's would. */
    public static final class NoWindowsDialect extends H2Dialect {

        @Override
        public boolean supportsWindowFunctions() {
            return false;
        }
    }

    private final HibernateProviderSupport support = new HibernateProviderSupport();

    @Test
    void ac_exe_18_every_tier_1_dialect_renders_the_window_and_its_sources_are_served() {
        assertThat(support.sources()).isPresent();
        for (Class<? extends Dialect> dialect : List.of(PostgreSQLDialect.class, MariaDBDialect.class,
                H2Dialect.class, OracleDialect.class, SQLServerDialect.class)) {
            on(dialect, null, sf -> assertThat(support.inlineCounts(sf)).as(dialect.getName()).isTrue());
        }
        on(MySQLDialect.class, "8.0.36", sf -> assertThat(support.inlineCounts(sf)).isTrue());
    }

    @Test
    void ac_exe_20_the_answer_is_the_dialects_own_mysql_before_8_0_2_has_no_window() {
        on(MySQLDialect.class, "8.0.0", sf -> assertThat(support.inlineCounts(sf)).isFalse());
    }

    @Test
    void ac_exe_20_a_dialect_without_window_functions_answers_false() {
        on(NoWindowsDialect.class, null, sf -> assertThat(support.inlineCounts(sf)).isFalse());
    }

    @Test
    void ac_exe_20_another_providers_factory_answers_false() {
        EntityManagerFactory other = (EntityManagerFactory) Proxy.newProxyInstance(
                HibernateInlineCountsTest.class.getClassLoader(), new Class<?>[] {EntityManagerFactory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "unwrap" -> throw new PersistenceException("not a provider factory");
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertThat(support.inlineCounts(other)).isFalse();
    }

    /**
     * {@code work} on a factory of {@code dialect}, booted without reading JDBC metadata or touching a database, at the
     * database {@code version} when set, else the dialect's minimum.
     */
    private static void on(Class<? extends Dialect> dialect, String version, Consumer<SessionFactory> work) {
        Configuration cfg = new Configuration();
        Map.of(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:inline-counts",
                AvailableSettings.DIALECT, dialect.getName(),
                AvailableSettings.ALLOW_METADATA_ON_BOOT, "false",
                AvailableSettings.HBM2DDL_AUTO, "none").forEach(cfg::setProperty);
        if (version != null) {
            String[] parts = version.split("\\.");
            cfg.setProperty(AvailableSettings.JAKARTA_HBM2DDL_DB_VERSION, version);
            cfg.setProperty(AvailableSettings.JAKARTA_HBM2DDL_DB_MAJOR_VERSION, parts[0]);
            cfg.setProperty(AvailableSettings.JAKARTA_HBM2DDL_DB_MINOR_VERSION, parts[1]);
        }
        try (SessionFactory sf = cfg.buildSessionFactory()) {
            work.accept(sf);
        }
    }
}
