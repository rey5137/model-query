package com.rey.modelquery.tck.spr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.spring.boot.ModelQueryAutoConfiguration;
import com.rey.modelquery.spring.data.ModelQueryConfigurer;
import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.spring.data.ModelQueryRepositoryFactoryBean;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

/** The Boot starter reads {@code modelquery.*} into the config and wires the factory bean (integration/50 §3). */
class StarterTest {

    private static final String WARNING_LOGGER = "com.rey.modelquery.spring.boot.ModelQueryAutoConfiguration";

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Long, OrderEntity, Long> ID = ColumnField.of(Long.class, ORDERS, "id", Long.class);
    private static final ModelQuery<OrderEntity, ?, Long> ORDER_IDS = ModelQuery.builder(ORDERS, row -> row.get(ID))
            .columns(ColumnSet.of(ID)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc()).build();

    private final ApplicationContextRunner configOnly =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ModelQueryAutoConfiguration.class));

    private final ApplicationContextRunner withRepositories = configOnly.withUserConfiguration(DefaultJpa.class);

    // ---- properties into the config (R-SPR-08)

    @Test
    void r_spr_08_unset_properties_leave_the_config_defaults() {
        configOnly.run(context -> {
            ModelQueryConfig config = context.getBean(ModelQueryConfig.class);
            assertThat(config.vendor()).isEmpty();
            assertThat(config.exportPageSize()).isEqualTo(ModelQueryConfig.defaults().exportPageSize());
            assertThat(config.primaryKeyFirstBatchSize()).isEmpty();
            assertThat(config.queryTimeout()).isEmpty();
            assertThat(config.keysetNullKeys()).isEqualTo(KeysetNullKeys.FAIL);
            assertThat(config.mysqlStreamingMode()).isEqualTo(MysqlStreamingMode.ROW_BY_ROW);
        });
    }

    @Test
    void r_spr_08_every_property_is_read_into_the_config() {
        configOnly.withPropertyValues("modelquery.vendor=sql-server", "modelquery.export.page-size=200",
                "modelquery.primary-key-first.batch-size=50", "modelquery.stream.fetch-size=70",
                "modelquery.mysql.streaming-mode=cursor-fetch", "modelquery.query-timeout=30s",
                "modelquery.keyset.null-keys=honour-null-precedence").run(context -> {
                    ModelQueryConfig config = context.getBean(ModelQueryConfig.class);
                    assertThat(config.vendor()).contains(DatabaseVendor.SQLSERVER);
                    assertThat(config.exportPageSize()).isEqualTo(200);
                    assertThat(config.primaryKeyFirstBatchSize()).hasValue(50);
                    assertThat(config.streamFetchSize()).isEqualTo(70);
                    assertThat(config.mysqlStreamingMode()).isEqualTo(MysqlStreamingMode.CURSOR_FETCH);
                    assertThat(config.queryTimeout()).contains(Duration.ofSeconds(30));
                    assertThat(config.keysetNullKeys()).isEqualTo(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
                });
    }

    @Test
    void r_spr_08_a_config_bean_of_the_application_wins() {
        ModelQueryConfig own = ModelQueryConfig.defaults().exportPageSize(7);
        configOnly.withBean(ModelQueryConfig.class, () -> own)
                .run(context -> assertThat(context.getBean(ModelQueryConfig.class)).isSameAs(own));
    }

    // ---- AC-SPR-11

    @Test
    void ac_spr_11_a_config_bean_of_the_application_with_a_modelquery_property_fails_startup_with_mq4006() {
        ModelQueryConfig own = ModelQueryConfig.defaults().exportPageSize(7);
        configOnly.withBean(ModelQueryConfig.class, () -> own).withPropertyValues("modelquery.export.page-size=200")
                .run(context -> {
                    assertThat(rootCode(context.getStartupFailure())).isEqualTo(MqCode.MQ4006);
                    assertThat(context.getStartupFailure()).hasMessageContaining("property modelquery.export.page-size");
                });
    }

    @Test
    void ac_spr_11_a_config_bean_of_the_application_without_a_profile_bean_fails_startup_with_mq4006() {
        configOnly.withBean(ModelQueryConfig.class, ModelQueryConfig::defaults)
                .withBean(VendorProfile.class, () -> new BeanProfile(new ArrayList<>()))
                .run(context -> {
                    assertThat(rootCode(context.getStartupFailure())).isEqualTo(MqCode.MQ4006);
                    assertThat(context.getStartupFailure()).hasMessageContaining("VendorProfile bean " + BeanProfile.class.getName());
                });
    }

    @Test
    void ac_spr_11_a_config_bean_of_the_application_holding_the_profile_beans_starts() {
        BeanProfile profile = new BeanProfile(new ArrayList<>());
        configOnly.withBean(VendorProfile.class, () -> profile)
                .withBean(ModelQueryConfig.class, () -> ModelQueryConfig.defaults().vendorProfiles(List.of(profile)))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void r_spr_08_an_unknown_vendor_fails_startup_with_mq4001() {
        configOnly.withPropertyValues("modelquery.vendor=db2").run(context ->
                assertThat(rootCode(context.getStartupFailure())).isEqualTo(MqCode.MQ4001));
    }

    @Test
    void r_spr_08_an_out_of_range_size_fails_startup_with_mq4003() {
        configOnly.withPropertyValues("modelquery.export.page-size=0").run(context -> assertThat(
                rootCode(context.getStartupFailure())).isEqualTo(MqCode.MQ4003));
        configOnly.withPropertyValues("modelquery.query-timeout=0s").run(context -> assertThat(
                rootCode(context.getStartupFailure())).isEqualTo(MqCode.MQ4003));
    }

    // ---- AC-SPR-08

    @Test
    void ac_spr_08_honour_null_precedence_logs_one_startup_warning() {
        assertThat(warnings(() -> withRepositories
                .withPropertyValues("modelquery.keyset.null-keys=honour-null-precedence")
                .run(context -> assertThat(context).hasNotFailed())))
                .singleElement().asString().contains("null-keys=honour-null-precedence", "MQ2202");
    }

    @Test
    void ac_spr_08_the_default_fail_and_an_unset_property_log_no_warning() {
        assertThat(warnings(() -> {
            withRepositories.withPropertyValues("modelquery.keyset.null-keys=fail")
                    .run(context -> assertThat(context).hasNotFailed());
            withRepositories.run(context -> assertThat(context).hasNotFailed());
        })).isEmpty();
    }

    // ---- R-SPR-02

    @Test
    void r_spr_02_the_default_repositories_get_the_model_query_factory_bean_and_work() {
        withRepositories.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanFactory().getBeanDefinition("orderRepository").getBeanClassName())
                    .isEqualTo(ModelQueryRepositoryFactoryBean.class.getName());
            assertThat(context.getBean(OrderRepository.class).count(ORDER_IDS)).isPositive();
        });
    }

    @Test
    void r_spr_02_a_repository_with_a_factory_bean_class_of_its_own_is_left_alone() {
        configOnly.withUserConfiguration(CustomFactoryBeanJpa.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(PlainRepository.class)).singleElement()
                    .satisfies(name -> assertThat(context.getBeanFactory().getBeanDefinition(name).getBeanClassName())
                            .isEqualTo(OwnFactoryBean.class.getName()));
        });
    }

    // ---- AC-SPR-10

    @Test
    void ac_spr_10_vendor_with_two_factories_and_no_configurer_fails_startup_with_mq4005() {
        withRepositories.withUserConfiguration(SecondFactory.class).withPropertyValues("modelquery.vendor=h2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCode(context.getStartupFailure())).isEqualTo(MqCode.MQ4005);
                });
    }

    @Test
    void ac_spr_10_a_configurer_lets_two_factories_start_and_chooses_each_repositorys_config() {
        List<EntityManagerFactory> seen = new ArrayList<>();
        ModelQueryConfigurer configurer = (shared, factory) -> {
            seen.add(factory);
            return shared.queryTimeout(Duration.ofSeconds(9));
        };
        withRepositories.withUserConfiguration(SecondFactory.class)
                .withBean(ModelQueryConfigurer.class, () -> configurer)
                .withPropertyValues("modelquery.vendor=h2").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(seen)
                            .containsExactly(context.getBean("entityManagerFactory", EntityManagerFactory.class));
                });
    }

    @Test
    void ac_spr_10_a_vendor_with_one_factory_needs_no_configurer() {
        withRepositories.withPropertyValues("modelquery.vendor=h2").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void r_spr_13_a_configurer_returning_null_is_refused() {
        withRepositories.withBean(ModelQueryConfigurer.class, () -> (shared, factory) -> null)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(NullPointerException.class).rootCause()
                        .hasMessageContaining("ModelQueryConfigurer").hasMessageContaining("returned null"));
    }

    // ---- AC-SPR-12

    @Test
    void ac_spr_12_a_repository_declaring_another_entity_fails_startup_with_mq4007() {
        configOnly.withUserConfiguration(MismatchedJpa.class).run(context -> {
            assertThat(rootCode(context.getStartupFailure())).isEqualTo(MqCode.MQ4007);
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(
                    "ModelQueryRepository<" + CustomerEntity.class.getName() + "> on a repository of "
                            + OrderEntity.class.getName());
        });
    }

    // ---- AC-VND-06

    @Test
    void ac_vnd_06_a_profile_bean_serves_its_vendor_ahead_of_the_built_in_one() {
        List<Duration> timeouts = new ArrayList<>();
        withRepositories.withBean(VendorProfile.class, () -> new BeanProfile(timeouts))
                .withPropertyValues("modelquery.query-timeout=5s").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OrderRepository.class).count(ORDER_IDS)).isPositive();
                    assertThat(timeouts).isNotEmpty().containsOnly(Duration.ofSeconds(5));
                });
    }

    @Test
    void ac_vnd_06_two_profile_beans_for_one_vendor_fail_startup_with_mq4002() {
        withRepositories.withBean("one", VendorProfile.class, () -> new BeanProfile(new ArrayList<>()))
                .withBean("two", VendorProfile.class, () -> new BeanProfile(new ArrayList<>()))
                .run(context -> assertThat(rootCode(context.getStartupFailure())).isEqualTo(MqCode.MQ4002));
    }

    // ---- support

    private static MqCode rootCode(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof ModelQueryConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("a ModelQueryConfigurationException in the causes of %s", failure).isNotNull();
        return ((ModelQueryConfigurationException) cause).code();
    }

    /** The WARNING messages the starter logs while {@code action} runs. */
    private static List<String> warnings(Runnable action) {
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger(WARNING_LOGGER);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    /** A profile for H2 that records the timeouts it is asked to apply. */
    private record BeanProfile(List<Duration> timeouts) implements VendorProfile {

        @Override
        public DatabaseVendor vendor() {
            return DatabaseVendor.H2;
        }

        @Override
        public int maxInListSize() {
            return 1000;
        }

        @Override
        public int maxBindParameters() {
            return 30000;
        }

        @Override
        public void applyStreaming(Query query, int fetchSize) {
            query.setHint("org.hibernate.fetchSize", fetchSize);
        }

        @Override
        public void applyTimeout(Query query, Duration timeout) {
            timeouts.add(timeout);
        }

        @Override
        public NullOrdering defaultAscendingNullOrdering() {
            return NullOrdering.NULLS_FIRST;
        }
    }

    /** A repository with no model-query fragment, for a factory bean class of the application's own. */
    interface PlainRepository extends JpaRepository<OrderEntity, Long> {}

    /** A repository of one entity declaring the model-query fragment of another (R-SPR-12). */
    interface MismatchedRepository extends JpaRepository<OrderEntity, Long>, ModelQueryRepository<CustomerEntity> {}

    /** The application's own factory bean class. */
    public static class OwnFactoryBean<T extends org.springframework.data.repository.Repository<S, I>, S, I>
            extends JpaRepositoryFactoryBean<T, S, I> {
        public OwnFactoryBean(Class<? extends T> repositoryInterface) {
            super(repositoryInterface);
        }
    }

    /** The TCK's H2 database and entities, as Boot's JPA auto-configuration names them. */
    abstract static class Jpa {

        @Bean
        DataSource dataSource() {
            return JoinTestSupport.dataSource(TckDatabases.get(TckTarget.h2()));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            return factory(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }

    private static LocalContainerEntityManagerFactoryBean factory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan(CustomerEntity.class.getPackageName());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        return factory;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = StarterTest.class, considerNestedRepositories = true,
            excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = { PlainRepository.class, MismatchedRepository.class }))
    static class DefaultJpa extends Jpa {}

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = StarterTest.class, considerNestedRepositories = true,
            repositoryFactoryBeanClass = OwnFactoryBean.class,
            excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = { OrderRepository.class, MismatchedRepository.class }))
    static class CustomFactoryBeanJpa extends Jpa {}

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = StarterTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MismatchedRepository.class))
    static class MismatchedJpa extends Jpa {}

    /** A second factory over the same database, which makes the context a multi-factory one. */
    @Configuration(proxyBeanMethods = false)
    static class SecondFactory {

        @Bean
        LocalContainerEntityManagerFactoryBean secondEntityManagerFactory(DataSource dataSource) {
            return factory(dataSource);
        }
    }
}
