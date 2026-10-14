package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The declared target vendors and their check per factory (R-VND-19, AC-VND-15). */
class TargetVendorsTest {

    private static final String TARGETS_HINT = "; connect it to one of them, or add %s to "
            + "ModelQueryConfig.targetVendors(...) or modelquery.target-vendors";

    /** A factory whose vendor is detected from {@code DatabaseMetaData} on its non-JTA data source: H2. */
    private static SessionFactory detected;

    /** A factory with no data source property, so nothing detects its vendor and it is {@code OTHER}. */
    private static SessionFactory undetected;

    /** A factory on H2 served by {@link Naming}, whose answer names it in {@code MQ4012}. */
    private static SessionFactory named;

    @BeforeAll
    static void boot() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:target-vendors-detected;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        detected = factory("targets-detected", new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource));
        undetected = factory("targets-undetected", new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:target-vendors-undetected")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, ""));
        named = factory("targets-named", new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource)
                .applySetting(Naming.NAMING, "true"));
    }

    @AfterAll
    static void close() {
        detected.close();
        undetected.close();
        named.close();
    }

    @Test
    void ac_vnd_15_target_vendors_are_undeclared_by_default_and_kept_by_the_other_setters() {
        assertThat(ModelQueryConfig.defaults().targetVendors()).isEmpty();
        var config = ModelQueryConfig.defaults().targetVendors(DatabaseVendor.POSTGRESQL, DatabaseVendor.H2);
        var others = config.vendor(DatabaseVendor.H2).queryTimeout(Duration.ofSeconds(1)).exportPageSize(10)
                .writeAssignments(List.of()).vendorProfiles(List.of());
        assertThat(others.targetVendors()).contains(Set.of(DatabaseVendor.H2, DatabaseVendor.POSTGRESQL));
        assertThat(others.targetVendors(List.of(DatabaseVendor.MYSQL)).vendor()).contains(DatabaseVendor.H2);
    }

    @Test
    void ac_vnd_15_the_three_forms_declare_the_same_vendors_in_enum_order() {
        var expected = List.of(DatabaseVendor.H2, DatabaseVendor.POSTGRESQL, DatabaseVendor.MARIADB);
        var fromVendors = ModelQueryConfig.defaults()
                .targetVendors(DatabaseVendor.MARIADB, DatabaseVendor.H2, DatabaseVendor.POSTGRESQL, DatabaseVendor.H2);
        var fromCollection = ModelQueryConfig.defaults()
                .targetVendors(List.of(DatabaseVendor.POSTGRESQL, DatabaseVendor.MARIADB, DatabaseVendor.H2));
        var fromNames = ModelQueryConfig.defaults().targetVendors("maria-db", "h2", "PostgreSQL");
        for (ModelQueryConfig config : List.of(fromVendors, fromCollection, fromNames)) {
            assertThat(config.targetVendors().orElseThrow()).containsExactlyElementsOf(expected);
            assertThatThrownBy(() -> config.targetVendors().orElseThrow().add(DatabaseVendor.OTHER))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void ac_vnd_15_an_empty_collection_or_name_list_throws_mq4003() {
        String expected = MqCode.MQ4003.code() + ": targetVendors is empty; name at least one vendor, or leave the "
                + "target vendors undeclared to check none";
        assertCode(() -> ModelQueryConfig.defaults().targetVendors(List.of()), MqCode.MQ4003, expected);
        assertCode(() -> ModelQueryConfig.defaults().targetVendors(new String[0]), MqCode.MQ4003, expected);
    }

    @Test
    void ac_vnd_15_an_unknown_vendor_name_throws_mq4001() {
        assertCode(() -> ModelQueryConfig.defaults().targetVendors("h2", "db2"), MqCode.MQ4001,
                MqCode.MQ4001.code() + ": target vendor 'db2' is not one of " + List.of(DatabaseVendor.values()));
    }

    @Test
    void ac_vnd_15_each_factory_is_checked_against_its_own_detected_vendor() {
        var h2Only = ModelQueryConfig.defaults().targetVendors(DatabaseVendor.H2);
        assertThatCode(() -> h2Only.checkTargets(detected)).doesNotThrowAnyException();
        assertCode(() -> h2Only.checkTargets(undetected), MqCode.MQ4012, MqCode.MQ4012.code()
                + ": EntityManagerFactory " + identity(undetected) + " is on OTHER, as no vendor could be detected, "
                + "which is not one of the target vendors [H2]" + TARGETS_HINT.formatted("OTHER"));
        // OTHER matches only when it is listed.
        var withOther = h2Only.targetVendors(DatabaseVendor.OTHER, DatabaseVendor.H2);
        assertThatCode(() -> withOther.checkTargets(undetected)).doesNotThrowAnyException();
        assertCode(() -> ModelQueryConfig.defaults().targetVendors("postgresql", "mysql").checkTargets(detected),
                MqCode.MQ4012, MqCode.MQ4012.code() + ": EntityManagerFactory " + identity(detected) + " is on H2, "
                        + "detected from DatabaseMetaData, which is not one of the target vendors [POSTGRESQL, MYSQL]"
                        + TARGETS_HINT.formatted("H2"));
    }

    @Test
    void ac_vnd_15_undeclared_targets_check_nothing() {
        assertThatCode(() -> ModelQueryConfig.defaults().checkTargets(undetected)).doesNotThrowAnyException();
        try (EntityManager em = undetected.createEntityManager()) {
            assertThat(ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults())).isNotNull();
        }
    }

    @Test
    void ac_vnd_15_an_explicit_vendor_outside_the_targets_throws_mq4012() {
        var explicit = ModelQueryConfig.defaults().vendor("postgresql").targetVendors(DatabaseVendor.H2);
        assertCode(() -> explicit.checkTargets(detected), MqCode.MQ4012, MqCode.MQ4012.code()
                + ": EntityManagerFactory " + identity(detected) + " is on POSTGRESQL, set by "
                + "ModelQueryConfig.vendor(...) or modelquery.vendor, which is not one of the target vendors [H2]"
                + TARGETS_HINT.formatted("POSTGRESQL"));
    }

    @Test
    void ac_vnd_15_mariadb_is_a_target_while_its_profile_is_other() {
        assertThat(VendorResolver.resolve(detected, Optional.of(DatabaseVendor.MARIADB), MysqlStreamingMode.ROW_BY_ROW)
                .profile().vendor()).isEqualTo(DatabaseVendor.OTHER);
        var mariaDb = ModelQueryConfig.defaults().vendor(DatabaseVendor.MARIADB).targetVendors("mariadb");
        assertThatCode(() -> mariaDb.checkTargets(detected)).doesNotThrowAnyException();
        assertCode(() -> mariaDb.targetVendors(DatabaseVendor.OTHER).checkTargets(detected), MqCode.MQ4012,
                MqCode.MQ4012.code() + ": EntityManagerFactory " + identity(detected) + " is on MARIADB, set by "
                        + "ModelQueryConfig.vendor(...) or modelquery.vendor, which is not one of the target vendors "
                        + "[OTHER]" + TARGETS_HINT.formatted("MARIADB"));
    }

    @Test
    void ac_vnd_15_creating_an_executor_checks_the_targets_of_its_factory() {
        var h2Only = ModelQueryConfig.defaults().targetVendors(DatabaseVendor.H2);
        try (EntityManager em = detected.createEntityManager()) {
            assertThat(ModelQueryExecutor.create(em, KeysetRowEntity.class, h2Only)).isNotNull();
        }
        try (EntityManager em = undetected.createEntityManager()) {
            assertThatThrownBy(() -> ModelQueryExecutor.create(em, KeysetRowEntity.class, h2Only))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4012))
                    .hasMessageContaining(identity(undetected) + " is on OTHER");
        }
    }

    @Test
    void ac_vnd_15_mq4012_quotes_the_name_the_provider_support_answers_else_names_class_and_identity() {
        var postgresOnly = ModelQueryConfig.defaults().targetVendors(DatabaseVendor.POSTGRESQL);
        String detail = " is on H2, detected from DatabaseMetaData, which is not one of the target vendors "
                + "[POSTGRESQL]" + TARGETS_HINT.formatted("H2");
        Naming.answer = Optional.of("orders-unit");
        try {
            assertCode(() -> postgresOnly.checkTargets(named), MqCode.MQ4012,
                    MqCode.MQ4012.code() + ": EntityManagerFactory 'orders-unit'" + detail);
            Naming.answer = Optional.empty();
            assertCode(() -> postgresOnly.checkTargets(named), MqCode.MQ4012,
                    MqCode.MQ4012.code() + ": EntityManagerFactory " + identity(named) + detail);
        } finally {
            Naming.answer = Optional.empty();
        }
    }

    @Test
    void ac_vnd_15_a_provider_support_names_no_factory_by_default() {
        ProviderSupport unnamed = new ProviderSupport() {
            @Override
            public boolean supports(EntityManagerFactory emf) {
                return true;
            }

            @Override
            public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
                return query.getResultStream();
            }
        };
        assertThat(unnamed.factoryName(named)).isEmpty();
    }

    /** {@code emf}'s class and identity, as {@code MQ4012} names a factory its provider support does not name. */
    private static String identity(EntityManagerFactory emf) {
        return emf.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(emf));
    }

    private static void assertCode(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                e -> assertThat(e.code()).isEqualTo(code)).hasMessage(message);
    }

    private static SessionFactory factory(String name, StandardServiceRegistryBuilder registry) {
        registry.applySetting(AvailableSettings.SESSION_FACTORY_NAME, name)
                .applySetting(AvailableSettings.SESSION_FACTORY_NAME_IS_JNDI, "false")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        return new Configuration().addAnnotatedClass(KeysetRowEntity.class).buildSessionFactory(registry.build());
    }

    /** Serves a factory with the {@link #NAMING} property and names it {@link #answer}. */
    public static final class Naming implements ProviderSupport {

        static final String NAMING = "model-query.test.target-vendors-naming";

        static volatile Optional<String> answer = Optional.empty();

        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(NAMING);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }

        @Override
        public Optional<String> factoryName(EntityManagerFactory emf) {
            return answer;
        }
    }
}
