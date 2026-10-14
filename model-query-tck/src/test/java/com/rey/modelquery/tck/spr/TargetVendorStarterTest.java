package com.rey.modelquery.tck.spr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.spring.boot.ModelQueryAutoConfiguration;
import com.rey.modelquery.spring.data.ModelQueryConfigurer;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The starter's startup check of each {@code EntityManagerFactory} bean against the declared target vendors, on the
 * TCK's H2 database, which the Hibernate dialect detects (R-SPR-17, AC-SPR-21).
 */
class TargetVendorStarterTest {

    /** R-VND-19's detail for one factory, whose name the provider line decides, so it is matched as any word. */
    private static final String DETAIL = "EntityManagerFactory \\E\\S+\\Q is on H2, detected by the persistence "
            + "provider, which is not one of the target vendors [POSTGRESQL]; connect it to one of them, or add H2 to "
            + "ModelQueryConfig.targetVendors(...) or modelquery.target-vendors";

    private final ApplicationContextRunner configOnly =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ModelQueryAutoConfiguration.class));

    /** Two eager factories on H2, the first with the repositories, which would check it on their own creation. */
    private final ApplicationContextRunner twoFactories =
            configOnly.withUserConfiguration(StarterTest.DefaultJpa.class, StarterTest.SecondFactory.class);

    @Test
    void ac_spr_21_two_factories_off_the_targets_fail_startup_in_one_mq4012() {
        twoFactories.withPropertyValues("modelquery.target-vendors=postgresql").run(context -> {
            assertThat(context).hasFailed();
            ModelQueryConfigurationException failure = configurationFailure(context.getStartupFailure());
            assertThat(failure.code()).isEqualTo(MqCode.MQ4012);
            assertThat(failure.getMessage()).matches(Pattern.compile("\\Q" + MqCode.MQ4012.code()
                    + ": EntityManagerFactory beans not on a target vendor:\n- bean 'entityManagerFactory': " + DETAIL
                    + "\n- bean 'secondEntityManagerFactory': " + DETAIL + "\\E"));
        });
    }

    @Test
    void ac_spr_21_a_factory_failing_its_own_creation_is_left_out_and_suppressed_on_the_mq4012() {
        configOnly.withUserConfiguration(StarterTest.DefaultJpa.class, BrokenSecondFactory.class)
                .withPropertyValues("modelquery.target-vendors=postgresql").run(context -> {
                    ModelQueryConfigurationException failure = configurationFailure(context.getStartupFailure());
                    assertThat(failure.code()).isEqualTo(MqCode.MQ4012);
                    assertThat(failure.getMessage()).matches(Pattern.compile("\\Q" + MqCode.MQ4012.code()
                            + ": EntityManagerFactory beans not on a target vendor:\n- bean 'entityManagerFactory': "
                            + DETAIL + "\\E"));
                    assertThat(failure.getSuppressed()).singleElement()
                            .satisfies(e -> assertThat(e).isInstanceOf(BeansException.class)
                                    .hasRootCauseMessage(BrokenSecondFactory.BROKEN));
                });
    }

    @Test
    void ac_spr_21_a_configurer_is_not_called_by_the_startup_check() {
        var calls = new AtomicInteger();
        ModelQueryConfigurer configurer = (shared, factory) -> {
            calls.incrementAndGet();
            return shared;
        };
        twoFactories.withBean(ModelQueryConfigurer.class, () -> configurer)
                .withPropertyValues("modelquery.target-vendors=postgresql,h2").run(context -> {
                    assertThat(context).hasNotFailed();
                    // Once, for the one repository.
                    assertThat(calls).hasValue(1);
                });
    }

    @Test
    void ac_spr_21_target_vendors_declared_by_a_configurer_are_enforced_when_a_repository_is_created() {
        ModelQueryConfigurer configurer = (shared, factory) -> shared.targetVendors("postgresql");
        twoFactories.withBean(ModelQueryConfigurer.class, () -> configurer).run(context -> {
            ModelQueryConfigurationException failure = configurationFailure(context.getStartupFailure());
            assertThat(failure.code()).isEqualTo(MqCode.MQ4012);
            assertThat(failure.getMessage()).contains("is on H2, detected by the persistence provider");
        });
    }

    @Test
    void ac_spr_21_target_vendors_declared_by_a_configurer_are_not_checked_for_a_factory_with_no_repository() {
        ModelQueryConfigurer configurer = (shared, factory) -> shared.targetVendors("postgresql");
        configOnly.withUserConfiguration(PlainJpa.class).withBean(ModelQueryConfigurer.class, () -> configurer)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void ac_spr_21_factories_on_a_target_vendor_start_and_their_repositories_work() {
        twoFactories.withPropertyValues("modelquery.target-vendors=postgresql,h2").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModelQueryConfig.class).targetVendors()).isPresent();
            assertThat(context.getBean(OrderRepository.class).count()).isPositive();
        });
    }

    @Test
    void ac_spr_21_an_explicit_modelquery_vendor_is_what_the_startup_check_compares() {
        configOnly.withUserConfiguration(PlainJpa.class)
                .withPropertyValues("modelquery.target-vendors=postgresql", "modelquery.vendor=postgresql")
                .run(context -> assertThat(context).hasNotFailed());
        configOnly.withUserConfiguration(PlainJpa.class)
                .withPropertyValues("modelquery.target-vendors=postgresql", "modelquery.vendor=h2").run(context -> {
                    ModelQueryConfigurationException failure = configurationFailure(context.getStartupFailure());
                    assertThat(failure.code()).isEqualTo(MqCode.MQ4012);
                    assertThat(failure.getMessage()).contains("is on H2, set by ModelQueryConfig.vendor");
                });
    }

    @Test
    void ac_spr_21_a_lazy_factory_is_skipped_and_its_executor_checks_it() {
        configOnly.withUserConfiguration(LazyFactory.class).withPropertyValues("modelquery.target-vendors=postgresql")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EntityManagerFactory lazy = context.getBean("entityManagerFactory", EntityManagerFactory.class);
                    ModelQueryConfig config = context.getBean(ModelQueryConfig.class);
                    try (EntityManager em = lazy.createEntityManager()) {
                        assertThatThrownBy(() -> ModelQueryExecutor.create(em, OrderEntity.class, config))
                                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4012))
                                .hasMessageContaining("is on H2, detected by the persistence provider");
                    }
                });
    }

    @Test
    void ac_spr_21_an_unknown_or_empty_target_vendors_property_fails_startup() {
        configOnly.withPropertyValues("modelquery.target-vendors=h2,db2").run(context ->
                assertThat(configurationFailure(context.getStartupFailure()).code()).isEqualTo(MqCode.MQ4001));
        configOnly.withPropertyValues("modelquery.target-vendors=").run(context ->
                assertThat(configurationFailure(context.getStartupFailure()).code()).isEqualTo(MqCode.MQ4003));
    }

    @Test
    void ac_spr_21_a_config_bean_of_the_application_with_target_vendors_set_fails_startup_with_mq4006() {
        ModelQueryConfig own = ModelQueryConfig.defaults();
        configOnly.withBean(ModelQueryConfig.class, () -> own).withPropertyValues("modelquery.target-vendors=h2")
                .run(context -> {
                    ModelQueryConfigurationException failure = configurationFailure(context.getStartupFailure());
                    assertThat(failure.code()).isEqualTo(MqCode.MQ4006);
                    assertThat(failure).hasMessageContaining("property modelquery.target-vendors");
                });
    }

    @Test
    void ac_spr_21_a_chunk_transactions_bean_on_the_factory_s_transaction_manager_starts_with_the_check() {
        configOnly.withUserConfiguration(StarterTest.DefaultJpa.class, ManagedChunks.class)
                .withPropertyValues("modelquery.target-vendors=h2").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ModelQueryConfig.class).chunkTransactions())
                            .containsSame(context.getBean(ChunkTransactions.class));
                    assertThat(context.getBean(OrderRepository.class).count()).isPositive();
                });
    }

    @Test
    void ac_spr_21_a_factory_off_the_targets_under_a_creation_cycle_fails_startup() {
        configOnly.withUserConfiguration(PlainJpa.class, ManagedChunks.class)
                .withPropertyValues("modelquery.target-vendors=postgresql").run(context -> {
                    ModelQueryConfigurationException failure = configurationFailure(context.getStartupFailure());
                    assertThat(failure.code()).isEqualTo(MqCode.MQ4012);
                    assertThat(failure.getMessage()).matches(Pattern.compile("\\Q" + MqCode.MQ4012.code()
                            + ": EntityManagerFactory beans not on a target vendor:\n- bean 'entityManagerFactory': "
                            + DETAIL + "\\E"));
                });
    }

    @Test
    void ac_spr_21_a_factory_created_early_and_one_swept_fail_in_one_mq4012() {
        configOnly.withUserConfiguration(PlainJpa.class, ManagedChunks.class, StarterTest.SecondFactory.class,
                SecondFactoryUser.class).withPropertyValues("modelquery.target-vendors=postgresql").run(context -> {
                    ModelQueryConfigurationException failure = configurationFailure(context.getStartupFailure());
                    assertThat(failure.code()).isEqualTo(MqCode.MQ4012);
                    assertThat(failure.getMessage()).contains("- bean 'entityManagerFactory': ",
                            "- bean 'secondEntityManagerFactory': ");
                });
    }

    private static ModelQueryConfigurationException configurationFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof ModelQueryConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("a ModelQueryConfigurationException in the causes of %s", failure).isNotNull();
        return (ModelQueryConfigurationException) cause;
    }

    /**
     * A second factory whose product fails for a reason of its own when first asked for, which only the check's sweep
     * does, so the first factory's failure is found first.
     */
    @Configuration(proxyBeanMethods = false)
    static class BrokenSecondFactory {

        static final String BROKEN = "second factory unreachable";

        @Bean
        LocalContainerEntityManagerFactoryBean secondEntityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean() {
                @Override
                public EntityManagerFactory getObject() {
                    throw new IllegalStateException(BROKEN);
                }
            };
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan(OrderEntity.class.getPackageName());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            return factory;
        }
    }

    /** The TCK's H2 database and one factory, with no repositories to check the factory on their own creation. */
    @Configuration(proxyBeanMethods = false)
    static class PlainJpa extends StarterTest.Jpa {}

    /** A {@code ChunkTransactions} of the application's on the transaction manager, which needs the factory. */
    @Configuration(proxyBeanMethods = false)
    static class ManagedChunks {

        @Bean
        ChunkTransactions chunkTransactions(PlatformTransactionManager transactionManager) {
            return new ChunkTransactions() {
                @Override
                public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
                    throw new UnsupportedOperationException("not used: " + transactionManager);
                }
            };
        }
    }

    /** A bean on the second factory, which creates it, and so checks it, before all singletons exist. */
    @Configuration(proxyBeanMethods = false)
    static class SecondFactoryUser {

        @Bean
        String secondFactoryUser(@Qualifier("secondEntityManagerFactory") EntityManagerFactory second) {
            return second.toString();
        }
    }

    /** One factory on H2, created only when first asked for. */
    @Configuration(proxyBeanMethods = false)
    static class LazyFactory {

        @Bean
        DataSource dataSource() {
            return JoinTestSupport.dataSource(TckDatabases.get(TckTarget.h2()));
        }

        @Bean
        @Lazy
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            return StarterTest.factory(dataSource);
        }
    }
}
