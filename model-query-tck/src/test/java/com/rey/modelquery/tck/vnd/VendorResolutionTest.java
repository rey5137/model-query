package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.MysqlStreamingMode;
import com.rey.modelquery.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/**
 * Vendor resolution per {@code EntityManagerFactory} (vendor/40 §2). The one TCK package that expects a vendor per
 * database, as R-VND-04 allows.
 */
class VendorResolutionTest {

    private static final Optional<DatabaseVendor> DETECT = Optional.empty();

    @TckTest
    void ac_vnd_02_each_database_resolves_to_its_profile_with_and_without_model_query_hibernate(TckDatabase db) {
        AtomicInteger opened = new AtomicInteger();
        ResolvedVendor withHibernate;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(countingDataSource(db, opened))) {
            withHibernate = VendorResolver.resolve(sf, DETECT);
        }
        assertThat(withHibernate.source()).isEqualTo(ResolvedVendor.Source.PROVIDER);
        assertThat(withHibernate.providerSupport()).containsInstanceOf(HibernateProviderSupport.class);
        assertThat(withHibernate.detectedVendor()).isEqualTo(expected(db));
        assertThat(withHibernate.profile().vendor()).isEqualTo(expected(db));

        AtomicReference<ResolvedVendor> without = new AtomicReference<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(countingDataSource(db, opened))) {
            int before = opened.get();
            JoinTestSupport.withoutServices(() -> without.set(VendorResolver.resolve(sf, DETECT)));
            assertThat(opened.get() - before).as("DatabaseMetaData is read once, on one connection").isOne();
        }
        assertThat(without.get().source()).isEqualTo(ResolvedVendor.Source.METADATA);
        assertThat(without.get().providerSupport()).isEmpty();
        assertThat(without.get().profile()).isSameAs(withHibernate.profile());
    }

    @Test
    void ac_vnd_03_two_factories_on_different_databases_resolve_to_different_profiles() {
        try (SessionFactory h2 = JoinTestSupport.sessionFactory(database(TckVendor.H2));
                SessionFactory postgresql = JoinTestSupport.sessionFactory(database(TckVendor.POSTGRESQL))) {
            assertThat(VendorResolver.resolve(h2, DETECT).profile().vendor()).isEqualTo(DatabaseVendor.H2);
            assertThat(VendorResolver.resolve(postgresql, DETECT).profile().vendor())
                    .isEqualTo(DatabaseVendor.POSTGRESQL);
        }
    }

    @Test
    void ac_vnd_03_a_factory_is_resolved_and_logged_once_for_every_executor() {
        List<String> infos = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(database(TckVendor.H2))) {
            capturingResolverLog(infos, () -> {
                sf.inSession(em -> ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()));
                sf.inSession(em -> ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()));
            });
            assertThat(VendorResolver.resolve(sf, DETECT)).isSameAs(VendorResolver.resolve(sf, DETECT));
        }
        assertThat(infos).singleElement().asString().contains("H2", "PROVIDER");
    }

    @Test
    void ac_vnd_03_a_factory_on_another_database_is_resolved_once_whatever_the_mysql_streaming_mode() {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(database(TckVendor.H2))) {
            assertThat(VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.CURSOR_FETCH))
                    .isSameAs(VendorResolver.resolve(sf, DETECT));
        }
    }

    @Test
    void ac_vnd_04_an_explicit_vendor_wins_over_an_earlier_detection_of_the_same_factory() {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(database(TckVendor.H2))) {
            ResolvedVendor detected = VendorResolver.resolve(sf, DETECT);
            ResolvedVendor configured = VendorResolver.resolve(sf, Optional.of(DatabaseVendor.OTHER));
            assertThat(configured.source()).isEqualTo(ResolvedVendor.Source.CONFIG);
            assertThat(configured.profile().vendor()).isEqualTo(DatabaseVendor.OTHER);
            assertThat(VendorResolver.resolve(sf, DETECT)).isSameAs(detected);
        }
    }

    @Test
    void ac_vnd_04_an_explicit_vendor_is_resolved_without_touching_the_factory() {
        List<String> called = new ArrayList<>();
        var factory = (EntityManagerFactory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("toString") ? "throwing factory"
                                : method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                : proxy == args[0];
                    }
                    called.add(method.getName());
                    throw new PersistenceException("not a real factory: " + method.getName());
                });
        ResolvedVendor resolved = VendorResolver.resolve(factory, Optional.of(DatabaseVendor.POSTGRESQL));
        assertThat(resolved.source()).isEqualTo(ResolvedVendor.Source.CONFIG);
        assertThat(resolved.profile().vendor()).isEqualTo(DatabaseVendor.POSTGRESQL);
        assertThat(resolved.providerSupport()).isEmpty();
        // Only the provider probe asked, and was refused; nothing read the factory's properties or a DataSource.
        assertThat(called).containsOnly("unwrap");
    }

    @TckTest
    void ac_vnd_04_the_dialect_resolves_the_vendor_without_opening_a_connection(TckDatabase db) {
        AtomicInteger opened = new AtomicInteger();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(countingDataSource(db, opened))) {
            int before = opened.get();
            ResolvedVendor resolved = VendorResolver.resolve(sf, DETECT);
            assertThat(opened.get() - before).isZero();
            assertThat(resolved.source()).isEqualTo(ResolvedVendor.Source.PROVIDER);
            assertThat(resolved.profile().vendor()).isEqualTo(expected(db));
        }
    }

    private static DatabaseVendor expected(TckDatabase db) {
        return switch (db.vendor()) {
            case H2 -> DatabaseVendor.H2;
            case POSTGRESQL -> DatabaseVendor.POSTGRESQL;
            case MYSQL -> DatabaseVendor.MYSQL;
        };
    }

    private static TckDatabase database(TckVendor vendor) {
        return TckDatabases.get(TckTarget.defaultTargets().stream().filter(t -> t.vendor() == vendor).findFirst()
                .orElseThrow());
    }

    /** A DataSource over {@code db} that counts the connections it opens in {@code opened}. */
    private static DataSource countingDataSource(TckDatabase db, AtomicInteger opened) {
        return (DataSource) Proxy.newProxyInstance(VendorResolutionTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("toString") ? "counting DataSource of " + db
                                : method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                : proxy == args[0];
                    }
                    if (!method.getName().equals("getConnection")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    Connection connection = db.getConnection();
                    opened.incrementAndGet();
                    return connection;
                });
    }

    private static void capturingResolverLog(List<String> infos, Runnable work) {
        Logger logger = Logger.getLogger(VendorResolver.class.getName());
        Level level = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.INFO) {
                    infos.add(new SimpleFormatter().formatMessage(r));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        try {
            work.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
    }
}
