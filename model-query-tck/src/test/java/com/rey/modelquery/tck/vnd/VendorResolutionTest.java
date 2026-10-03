package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.StampedOrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Proxy;
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
        try (SessionFactory sf = JoinTestSupport.sessionFactory(JoinTestSupport.dataSource(db, opened::incrementAndGet))) {
            withHibernate = VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW);
        }
        assertThat(withHibernate.source()).isEqualTo(ResolvedVendor.Source.PROVIDER);
        assertThat(withHibernate.providerSupport()).containsInstanceOf(HibernateProviderSupport.class);
        assertThat(withHibernate.detectedVendor()).isEqualTo(expected(db));
        assertThat(withHibernate.profile().vendor()).isEqualTo(expected(db));

        AtomicReference<ResolvedVendor> without = new AtomicReference<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(JoinTestSupport.dataSource(db, opened::incrementAndGet))) {
            int before = opened.get();
            JoinTestSupport.withoutServices(() -> without.set(VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW)));
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
            assertThat(VendorResolver.resolve(h2, DETECT, MysqlStreamingMode.ROW_BY_ROW).profile().vendor()).isEqualTo(DatabaseVendor.H2);
            assertThat(VendorResolver.resolve(postgresql, DETECT, MysqlStreamingMode.ROW_BY_ROW).profile().vendor())
                    .isEqualTo(DatabaseVendor.POSTGRESQL);
        }
    }

    @Test
    void ac_vnd_03_a_factory_is_resolved_and_logged_once_for_every_executor() {
        List<String> infos = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(database(TckVendor.H2))) {
            capturingResolverLog(Level.INFO, infos, () -> {
                sf.inSession(em -> ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()));
                sf.inSession(em -> ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()));
            });
            assertThat(VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW)).isSameAs(VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW));
        }
        assertThat(infos).singleElement().asString().contains("H2", "PROVIDER");
    }

    @Test
    void ac_vnd_03_a_supplied_profile_is_logged_once_per_factory_naming_the_profile_it_replaces() {
        List<String> infos = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(database(TckVendor.H2))) {
            VendorProfile builtIn = VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW).profile();
            VendorProfile supplied = (VendorProfile) Proxy.newProxyInstance(VendorProfile.class.getClassLoader(),
                    new Class<?>[] {VendorProfile.class}, (proxy, method, args) -> method.invoke(builtIn, args));
            ModelQueryConfig config = ModelQueryConfig.defaults().vendorProfiles(List.of(supplied));
            capturingResolverLog(Level.INFO, infos, () -> {
                sf.inSession(em -> ModelQueryExecutor.create(em, OrderEntity.class, config));
                sf.inSession(em -> ModelQueryExecutor.create(em, OrderEntity.class, config));
            });
            assertThat(infos).singleElement().asString()
                    .contains("supplied profile " + supplied.getClass().getName(), "H2", "built-in H2");
        }
    }

    @Test
    void ac_vnd_03_a_factory_on_another_database_is_resolved_once_whatever_the_mysql_streaming_mode() {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(database(TckVendor.H2))) {
            assertThat(VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.CURSOR_FETCH))
                    .isSameAs(VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW));
        }
    }

    @Test
    void ac_vnd_10_hibernate_reports_one_table_for_two_entities_mapped_to_it_and_none_for_a_non_entity() {
        HibernateProviderSupport support = new HibernateProviderSupport();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(database(TckVendor.H2))) {
            assertThat(support.tablesOf(sf, OrderEntity.class)).singleElement()
                    .satisfies(table -> assertThat(table).isEqualToIgnoringCase("orders"));
            assertThat(support.tablesOf(sf, StampedOrderEntity.class))
                    .isEqualTo(support.tablesOf(sf, OrderEntity.class));
            assertThat(support.tablesOf(sf, CustomerEntity.class)).singleElement()
                    .satisfies(table -> assertThat(table).isEqualToIgnoringCase("customers"));
            assertThat(support.tablesOf(sf, String.class)).isEmpty();
        }
    }

    @Test
    void r_vnd_07_a_default_null_ordering_nothing_reports_is_warned_of_once() {
        TckDatabase h2 = database(TckVendor.H2);
        List<String> warnings = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(h2, "last")) {
            capturingResolverLog(Level.WARNING, warnings, () -> JoinTestSupport.withoutServices(() -> {
                VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW);
                VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW);
            }));
        }
        assertThat(warnings).filteredOn(w -> w.contains("hibernate.order_by.default_null_ordering")).singleElement()
                .asString().contains("last", "model-query-hibernate");

        // Reported by model-query-hibernate, or not set at all: nothing to warn of.
        warnings.clear();
        try (SessionFactory reported = JoinTestSupport.sessionFactory(h2, "last");
                SessionFactory unset = JoinTestSupport.sessionFactory(h2)) {
            capturingResolverLog(Level.WARNING, warnings, () -> {
                VendorResolver.resolve(reported, DETECT, MysqlStreamingMode.ROW_BY_ROW);
                JoinTestSupport.withoutServices(
                        () -> VendorResolver.resolve(unset, DETECT, MysqlStreamingMode.ROW_BY_ROW));
            });
        }
        assertThat(warnings).noneMatch(w -> w.contains("hibernate.order_by.default_null_ordering"));
    }

    @Test
    void ac_vnd_04_an_explicit_vendor_wins_over_an_earlier_detection_of_the_same_factory() {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(database(TckVendor.H2))) {
            ResolvedVendor detected = VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW);
            ResolvedVendor configured = VendorResolver.resolve(sf, Optional.of(DatabaseVendor.OTHER), MysqlStreamingMode.ROW_BY_ROW);
            assertThat(configured.source()).isEqualTo(ResolvedVendor.Source.CONFIG);
            assertThat(configured.profile().vendor()).isEqualTo(DatabaseVendor.OTHER);
            assertThat(VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW)).isSameAs(detected);
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
        ResolvedVendor resolved = VendorResolver.resolve(factory, Optional.of(DatabaseVendor.POSTGRESQL), MysqlStreamingMode.ROW_BY_ROW);
        assertThat(resolved.source()).isEqualTo(ResolvedVendor.Source.CONFIG);
        assertThat(resolved.profile().vendor()).isEqualTo(DatabaseVendor.POSTGRESQL);
        assertThat(resolved.providerSupport()).isEmpty();
        // Only the provider probe asked, and was refused, then the check for an unreported provider setting; nothing
        // asked for a DataSource or a connection.
        assertThat(called).containsOnly("unwrap", "getProperties");
    }

    @TckTest
    void ac_vnd_04_the_dialect_resolves_the_vendor_without_opening_a_connection(TckDatabase db) {
        AtomicInteger opened = new AtomicInteger();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(JoinTestSupport.dataSource(db, opened::incrementAndGet))) {
            int before = opened.get();
            ResolvedVendor resolved = VendorResolver.resolve(sf, DETECT, MysqlStreamingMode.ROW_BY_ROW);
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

    private static void capturingResolverLog(Level captured, List<String> infos, Runnable work) {
        Logger logger = Logger.getLogger(VendorResolver.class.getName());
        Level level = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == captured) {
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
