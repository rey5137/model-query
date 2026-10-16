package com.rey.modelquery.tck.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.portability.internal.JdbcRecording;
import com.rey.modelquery.portability.internal.OfflineFactory;
import com.rey.modelquery.portability.internal.OfflineUnit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-INS-10: the dry run boots the TCK unit offline per vendor on the Hibernate line on the classpath, with no
 * container and no JDBC call, and overrides or refuses each unit setting that would reach a database.
 */
class OfflineBootTest {

    /** The TCK unit: sequences, table, identity and UUID generators, {@code @Formula}, secondary tables, a view. */
    static final List<String> TCK_UNIT = Stream.of(
            "col.CardEntity", "col.CardHolderEntity", "col.CompositeKeyCopyEntity", "col.CompositeKeyItemEntity",
            "col.CompositeNodeEntity",
            "col.ConstantRowEntity", "col.CounterRowEntity", "col.CustomerEntity", "col.CustomerNoteEntity",
            "col.DatedRowEntity", "col.DeliveryEntity", "col.EmbeddedKeyEntity", "col.FormulaLineEntity",
            "col.FormulaProductEntity", "col.FolderNodeEntity", "col.KeysetTypeEntity", "col.LabelEntity",
            "col.NullableSortEntity",
            "col.OrderArchiveEntity", "col.OrderEntity", "col.OrderItemEntity", "col.PatronEntity",
            "col.PlainOrderItemEntity", "col.SkuOrderLineEntity", "col.SkuProductEntity", "col.StampedOrderEntity",
            "col.StringKeyProductEntity", "col.SubselectCustomerEntity", "col.ViewOrderEntity",
            "secondary.CardExtraEntity",
            "vnd.ins.InsSourceEntity", "vnd.ins.InsIdentityEntity", "vnd.ins.InsAssignedEntity",
            "vnd.ins.InsPooledEntity", "vnd.ins.InsSequenceEntity", "vnd.ins.InsTableEntity",
            "vnd.ins.InsUuidEntity", "vnd.ins.InsMapsIdEntity", "vnd.ins.InsKeyedEntity",
            "vnd.ins.InsCompositeEntity", "vnd.ins.InsGeneratedUuidEntity")
            .map(name -> "com.rey.modelquery.tck." + name)
            .toList();

    private static final Map<DatabaseVendor, String> VERSIONS = Map.of(
            DatabaseVendor.H2, "2.3.232",
            DatabaseVendor.POSTGRESQL, "15.4",
            DatabaseVendor.MYSQL, "8.0.36",
            DatabaseVendor.MARIADB, "10.11.6");

    private static final String HBM2DDL_AUTO = "hibernate.hbm2ddl.auto";
    private static final String DIALECT = "hibernate.dialect";

    @Test
    void ac_ins_10_the_tck_unit_boots_offline_per_vendor_with_no_jdbc_call() {
        for (Map.Entry<DatabaseVendor, String> entry : VERSIONS.entrySet()) {
            DatabaseVendor vendor = entry.getKey();
            // A boot that reached JDBC is MQ4013, so a returned factory recorded no call.
            EntityManagerFactory minimum = OfflineFactory.boot(unit(Map.of()), Target.of(vendor));
            assertThat(new HibernateProviderSupport().detectVendor(minimum)).contains(vendor);

            EntityManagerFactory full = OfflineFactory.boot(unit(Map.of()), Target.of(vendor, entry.getValue()));
            assertThat(new HibernateProviderSupport().detectVendor(full)).contains(vendor);
            String[] parts = entry.getValue().split("\\.");
            DatabaseVersion version = dialect(full).getVersion();
            assertThat(version.getDatabaseMajorVersion()).as("%s major", vendor).isEqualTo(Integer.parseInt(parts[0]));
            assertThat(version.getDatabaseMinorVersion()).as("%s minor", vendor).isEqualTo(Integer.parseInt(parts[1]));
            if (parts.length > 2) {
                assertThat(version.getDatabaseMicroVersion()).as("%s micro", vendor)
                        .isEqualTo(Integer.parseInt(parts[2]));
            }
            // One boot per unit and target: the second request is the cached factory, another version another one.
            assertThat(OfflineFactory.boot(unit(Map.of()), Target.of(vendor, entry.getValue()))).isSameAs(full);
            assertThat(full).isNotSameAs(minimum);
        }
    }

    @Test
    void ac_ins_10_a_unit_hbm2ddl_create_and_scripts_create_are_overridden(@TempDir Path dir) {
        Path script = dir.resolve("create.sql");
        Map<String, Object> unit = Map.of(
                HBM2DDL_AUTO, "create",
                "jakarta.persistence.schema-generation.database.action", "create",
                "jakarta.persistence.schema-generation.scripts.action", "create",
                "jakarta.persistence.schema-generation.scripts.create-target", script.toString());
        EntityManagerFactory emf = OfflineFactory.boot(unit(unit), Target.of(DatabaseVendor.H2, "2.3.232"));
        assertThat(dialect(emf)).isInstanceOf(H2Dialect.class);
        assertThat(script).doesNotExist();
    }

    @Test
    void ac_ins_10_a_unit_explicit_dialect_is_overridden_by_the_targets() {
        Map<String, Object> unit = Map.of(DIALECT, H2Dialect.class.getName());
        EntityManagerFactory emf = OfflineFactory.boot(unit(unit), Target.of(DatabaseVendor.POSTGRESQL, "15.4"));
        assertThat(dialect(emf)).isInstanceOf(PostgreSQLDialect.class);
        assertThat(dialect(emf).getVersion().getDatabaseMajorVersion()).isEqualTo(15);
    }

    @Test
    void ac_ins_10_a_unit_connection_provider_is_overridden_so_a_call_reaches_the_stub() {
        Map<String, Object> unit = Map.of(
                "hibernate.connection.provider_class",
                "org.hibernate.engine.jdbc.connections.internal.DriverManagerConnectionProviderImpl",
                "hibernate.connection.url", "jdbc:h2:mem:never-opened",
                "jakarta.persistence.jdbc.url", "jdbc:h2:mem:never-opened");
        EntityManagerFactory emf = OfflineFactory.boot(unit(unit), Target.of(DatabaseVendor.H2, "2.3.232"));
        assertThat(readCustomers(emf)).singleElement().asString().startsWith("select").contains("customers");
    }

    @Test
    void ac_ins_10_a_reached_statement_is_recorded_through_hibernates_exception_wrapping() {
        for (DatabaseVendor vendor : VERSIONS.keySet()) {
            EntityManagerFactory emf = OfflineFactory.boot(unit(Map.of()), Target.of(vendor));
            assertThat(readCustomers(emf)).as("%s", vendor).singleElement().asString().contains("customers");
        }
    }

    @Test
    void ac_ins_10_a_named_unit_boots_through_persistence_with_its_xml_properties_overridden(@TempDir Path dir)
            throws IOException {
        Path xml = dir.resolve("persistence.xml");
        StringBuilder unit = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0">
                <persistence-unit name="dry-run-tck" transaction-type="JTA">
                """);
        unit.append("<provider>").append(HibernatePersistenceProvider.class.getName()).append("</provider>\n");
        TCK_UNIT.forEach(name -> unit.append("<class>").append(name).append("</class>\n"));
        unit.append("""
                <exclude-unlisted-classes>true</exclude-unlisted-classes>
                <properties>
                <property name="hibernate.dialect" value="org.hibernate.dialect.H2Dialect"/>
                <property name="hibernate.hbm2ddl.auto" value="create"/>
                <property name="hibernate.connection.datasource" value="java:comp/env/jdbc/never"/>
                </properties>
                </persistence-unit>
                </persistence>
                """);
        Files.writeString(xml, unit);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new PersistenceXmlLoader(previous, xml.toUri().toURL()));
        try {
            EntityManagerFactory emf = OfflineFactory.boot(OfflineUnit.named("dry-run-tck", Map.of()),
                    Target.of(DatabaseVendor.MYSQL, "8.0.36"));
            assertThat(new HibernateProviderSupport().detectVendor(emf)).contains(DatabaseVendor.MYSQL);
            assertThat(readCustomers(emf)).singleElement().asString().contains("customers");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void ac_ins_10_a_provider_support_without_offline_properties_is_mq4013() {
        assertThatThrownBy(() -> OfflineFactory.boot(unit(Map.of()), Target.of(DatabaseVendor.POSTGRESQL, "15.4"),
                List.of(new NoOfflineSupport())))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4013))
                .hasMessageContaining("No ProviderSupport has offline properties for POSTGRESQL")
                .hasMessageContaining(NoOfflineSupport.class.getName());
    }

    @Test
    void ac_ins_10_a_boot_reaching_jdbc_is_mq4013_read_from_the_recording() {
        // Without the metadata flag the boot asks the stub for a connection.
        assertThatThrownBy(() -> OfflineFactory.boot(unit(Map.of()), Target.of(DatabaseVendor.POSTGRESQL, "15.4"),
                List.of(new WithoutMetadataFlag())))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4013))
                .hasMessageContaining("reached JDBC: [DataSource.getConnection")
                .hasMessageContaining(WithoutMetadataFlag.class.getName());
        // H2's global temporary tables swallow the stub's failure, so only the recording shows the boot reached JDBC.
        assertThatThrownBy(() -> OfflineFactory.boot(unit(Map.of()), Target.of(DatabaseVendor.H2, "2.3.232"),
                List.of(new WithTemporaryTables())))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4013))
                .hasMessageContaining("reached JDBC: [DataSource.getConnection");
    }

    @Test
    void ac_ins_10_a_failed_boot_is_not_cached_and_the_next_request_boots_again() {
        OfflineUnit unit = unit(Map.of("model-query.test.boot", "failed-then-booted"));
        Target target = Target.of(DatabaseVendor.POSTGRESQL, "15.4");
        // Both supports are one class, so one cache key: the failed boot must not be kept under it.
        assertThatThrownBy(() -> OfflineFactory.boot(unit, target,
                List.of(new Configurable("hibernate.boot.allow_jdbc_metadata_access"))))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4013));
        EntityManagerFactory booted = OfflineFactory.boot(unit, target, List.of(new Configurable()));
        assertThat(booted.isOpen()).isTrue();
        assertThat(OfflineFactory.boot(unit, target, List.of(new Configurable()))).isSameAs(booted);
    }

    @Test
    void ac_ins_10_concurrent_requests_boot_a_unit_once_and_share_the_factory() throws Exception {
        OfflineUnit unit = unit(Map.of("model-query.test.boot", "concurrent"));
        Target target = Target.of(DatabaseVendor.MYSQL, "8.0.36");
        Counting counting = new Counting();
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<EntityManagerFactory>> boots = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                boots.add(pool.submit(() -> {
                    start.await();
                    return OfflineFactory.boot(unit, target, List.of(counting));
                }));
            }
            start.countDown();
            EntityManagerFactory first = boots.get(0).get(2, TimeUnit.MINUTES);
            for (Future<EntityManagerFactory> boot : boots) {
                assertThat(boot.get(2, TimeUnit.MINUTES)).isSameAs(first);
            }
        } finally {
            pool.shutdownNow();
        }
        // The support checks each factory it boots once, so one check is one boot.
        assertThat(counting.checks.get()).isEqualTo(1);
    }

    @Test
    void ac_ins_10_a_class_list_naming_no_provider_on_the_class_path_is_mq4013() {
        OfflineUnit unit = unit(Map.of("jakarta.persistence.provider", "com.example.NoSuchProvider"));
        assertThatThrownBy(() -> OfflineFactory.boot(unit, Target.of(DatabaseVendor.H2, "2.3.232")))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4013))
                .hasMessageContaining("needs one persistence provider")
                .hasMessageContaining(HibernatePersistenceProvider.class.getName())
                .hasMessageContaining("jakarta.persistence.provider=com.example.NoSuchProvider");
    }

    /** Reads every customer in a call recording: the one statement it reaches, after Hibernate wraps the failure. */
    private static List<String> readCustomers(EntityManagerFactory emf) {
        try (JdbcRecording call = JdbcRecording.call()) {
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                TypedQuery<Object> read = em.createQuery("select c from CustomerEntity c", Object.class);
                assertThatThrownBy(read::getResultList)
                        .hasRootCauseInstanceOf(SQLException.class)
                        .rootCause().hasMessageStartingWith("Dry run: Connection.prepareStatement reached");
                return call.statements();
            } finally {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
                em.close();
            }
        }
    }

    private static OfflineUnit unit(Map<String, Object> properties) {
        return OfflineUnit.ofClasses(TCK_UNIT.stream().<Class<?>>map(OfflineBootTest::load).toList(), List.of(),
                properties);
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Dialect dialect(EntityManagerFactory emf) {
        return emf.unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect();
    }

    /** A third-party provider support written before {@code offlineProperties}: it keeps the empty default. */
    private static final class NoOfflineSupport implements ProviderSupport {
        @Override
        public boolean supports(EntityManagerFactory emf) {
            return true;
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }
    }

    /** Hibernate's support with one offline property dropped, so the boot reaches the stub. */
    private abstract static class Dropping implements ProviderSupport {
        private final ProviderSupport hibernate = new HibernateProviderSupport();
        private final List<String> dropped;

        Dropping(String... dropped) {
            this.dropped = List.of(dropped);
        }

        @Override
        public boolean supports(EntityManagerFactory emf) {
            return hibernate.supports(emf);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return hibernate.resultStream(query, fetchSize);
        }

        @Override
        public Optional<Map<String, Object>> offlineProperties(DatabaseVendor vendor) {
            return hibernate.offlineProperties(vendor).map(properties -> {
                Map<String, Object> kept = new HashMap<>(properties);
                dropped.forEach(kept::remove);
                return kept;
            });
        }
    }

    /** Hibernate's support with the given offline properties dropped: one class, whatever it drops. */
    private static final class Configurable extends Dropping {
        Configurable(String... dropped) {
            super(dropped);
        }
    }

    /** Hibernate's support, counting the factories it is asked to serve. */
    private static final class Counting extends Dropping {
        private final AtomicInteger checks = new AtomicInteger();

        @Override
        public boolean supports(EntityManagerFactory emf) {
            checks.incrementAndGet();
            return super.supports(emf);
        }
    }

    private static final class WithoutMetadataFlag extends Dropping {
        WithoutMetadataFlag() {
            super("hibernate.boot.allow_jdbc_metadata_access");
        }
    }

    private static final class WithTemporaryTables extends Dropping {
        WithTemporaryTables() {
            super("hibernate.query.mutation_strategy.global_temporary.create_tables",
                    "hibernate.query.mutation_strategy.global_temporary.drop_tables");
        }
    }

    /** Serves one {@code persistence.xml} to the provider's lookup, delegating everything else. */
    private static final class PersistenceXmlLoader extends ClassLoader {
        private static final String PERSISTENCE_XML = "META-INF/persistence.xml";
        private final URL xml;

        PersistenceXmlLoader(ClassLoader parent, URL xml) {
            super(parent);
            this.xml = xml;
        }

        @Override
        public URL getResource(String name) {
            return PERSISTENCE_XML.equals(name) ? xml : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return PERSISTENCE_XML.equals(name) ? Collections.enumeration(List.of(xml)) : super.getResources(name);
        }
    }
}
