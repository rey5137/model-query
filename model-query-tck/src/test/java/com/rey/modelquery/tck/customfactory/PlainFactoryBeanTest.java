package com.rey.modelquery.tck.customfactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spring.boot.ModelQueryAutoConfiguration;
import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.Ordered;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * An application keeping its own {@code JpaRepositoryFactoryBean} subclass that does not extend
 * {@link com.rey.modelquery.spring.data.ModelQueryRepositoryFactoryBean}: the starter keeps the class, keeps a custom
 * repository base class, and adds the model-query fragment through {@code customImplementation} only where the
 * repository extends {@link ModelQueryRepository} (R-SPR-02, D-83, D-113, AC-SPR-16, AC-SPR-17).
 *
 * <p>This lives in its own package so that a scan of {@code tck.spr} does not pick up these repositories.
 */
class PlainFactoryBeanTest {

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Long, CustomerEntity, Long> ID =
            ColumnField.of(Long.class, CUSTOMERS, "id", Long.class);
    private static final ModelQuery<CustomerEntity, ?, Long> CUSTOMER_IDS = ModelQuery
            .builder(CUSTOMERS, row -> row.get(ID)).select(SelectSet.of(ID)).primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc()).build();

    // ---- AC-SPR-16

    @TckTest
    void ac_spr_16_a_plain_factory_bean_subclass_keeps_its_class_and_gets_the_fragment_through_custom_implementation(
            TckDatabase db) {
        PlainJpaRepositoryFactoryBean.built().clear();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> JoinTestSupport.dataSource(db));
            // The early type check caches a merged definition and an early factory bean before the starter runs.
            context.register(PlainJpa.class, EarlyTypeCheck.class, ModelQueryAutoConfiguration.class);
            context.refresh();

            // Both repositories were built by the application's own class, whose override ran.
            assertThat(definitionClass(context, CustomerProfileRepository.class))
                    .isEqualTo(PlainJpaRepositoryFactoryBean.class.getName());
            assertThat(definitionClass(context, PlainCustomerRepository.class))
                    .isEqualTo(PlainJpaRepositoryFactoryBean.class.getName());
            assertThat(PlainJpaRepositoryFactoryBean.built()).containsExactlyInAnyOrder(
                    CustomerProfileRepository.class.getName(), PlainCustomerRepository.class.getName());

            // The custom base class built both: its own method works on each.
            assertThat(context.getBean(CustomerProfileRepository.class).refreshAndGet(1L)).isNotNull();
            assertThat(context.getBean(PlainCustomerRepository.class).refreshAndGet(1L)).isNotNull();

            // Only the repository extending ModelQueryRepository gets the fragment, though it was type-checked first.
            assertThat(context.getBean(CustomerProfileRepository.class)).isInstanceOf(ModelQueryRepository.class);
            assertThat(CustomFactoryBeanTest.modelQueryFragments(
                    context.getBean(CustomerProfileRepository.class), "AC-SPR-16")).isEqualTo(1);
            assertThat(context.getBean(CustomerProfileRepository.class)
                    .findPage(CUSTOMER_IDS, PageRequest.of(0, 2), CountMode.COUNT).getContent()).hasSize(2);
            assertThat(context.getBean(CustomerProfileRepository.class).findAll(CUSTOMER_IDS, Limit.unlimited()))
                    .isNotEmpty();

            // The other stays a plain repository without the fragment.
            assertThat(context.getBean(PlainCustomerRepository.class)).isNotInstanceOf(ModelQueryRepository.class);
            assertThat(context.getBean(PlainCustomerRepository.class).findAll()).isNotEmpty();
        }
    }

    @TckTest
    void ac_spr_16_a_non_generic_factory_bean_subclass_still_gets_the_fragment(TckDatabase db) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> JoinTestSupport.dataSource(db));
            context.register(NonGenericJpa.class, ModelQueryAutoConfiguration.class);
            context.refresh();

            // The class fixes its type arguments in its declaration, so the repository interface comes from the
            // definition's target type resolved as the factory bean's supertype, or from constructor argument 0.
            assertThat(definitionClass(context, CustomerProfileRepository.class))
                    .isEqualTo(CustomerProfileFactoryBean.class.getName());
            assertThat(context.getBean(CustomerProfileRepository.class)).isInstanceOf(ModelQueryRepository.class);
            assertThat(CustomFactoryBeanTest.modelQueryFragments(
                    context.getBean(CustomerProfileRepository.class), "AC-SPR-16")).isEqualTo(1);
            assertThat(context.getBean(CustomerProfileRepository.class)
                    .findPage(CUSTOMER_IDS, PageRequest.of(0, 2), CountMode.COUNT).getContent()).hasSize(2);
        }
    }

    @TckTest
    void ac_spr_16_a_fragment_on_a_plain_factory_bean_reads_the_secondary_entity_manager_factory(TckDatabase db) {
        var primaryOpens = new AtomicInteger();
        var secondaryOpens = new AtomicInteger();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class,
                    () -> JoinTestSupport.dataSource(db, primaryOpens::incrementAndGet));
            context.registerBean("secondaryDataSource", DataSource.class,
                    () -> JoinTestSupport.dataSource(db, secondaryOpens::incrementAndGet));
            context.register(TwoFactoriesJpa.class, ModelQueryAutoConfiguration.class);
            context.refresh();

            // The fragment takes the definition's entityManager, so from here it opens connections on the secondary
            // factory; the primary is only there to make the fragment's unqualified @PersistenceContext ambiguous.
            primaryOpens.set(0);
            secondaryOpens.set(0);
            assertThat(context.getBean(CustomerProfileRepository.class)
                    .findPage(CUSTOMER_IDS, PageRequest.of(0, 2), CountMode.COUNT).getContent()).hasSize(2);
            assertThat(secondaryOpens).hasPositiveValue();
            assertThat(primaryOpens).hasValue(0);
        }
    }

    @TckTest
    void ac_spr_16_the_fragment_is_not_an_autowire_candidate_so_model_query_repository_is_the_repository(
            TckDatabase db) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> JoinTestSupport.dataSource(db));
            context.register(PlainJpa.class, ModelQueryAutoConfiguration.class);
            context.register(ModelQueryInjections.class);
            context.refresh();

            ModelQueryInjections injected = context.getBean(ModelQueryInjections.class);
            assertThat(injected.repository).isSameAs(context.getBean(CustomerProfileRepository.class));
            assertThat(injected.all).containsExactly(context.getBean(CustomerProfileRepository.class));
        }
    }

    private static String definitionClass(AnnotationConfigApplicationContext context, Class<?> type) {
        String name = context.getBeanNamesForType(type)[0];
        return context.getBeanFactory().getBeanDefinition(name).getBeanClassName();
    }

    // ---- AC-SPR-17

    @TckTest
    void ac_spr_17_a_subclass_definition_already_setting_custom_implementation_fails_with_mq4008(TckDatabase db) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> JoinTestSupport.dataSource(db));
            context.register(PlainJpa.class, PresetCustomImplementation.class, ModelQueryAutoConfiguration.class);
            assertThatThrownBy(context::refresh)
                    .satisfies(failure -> assertThat(rootCode(failure)).isEqualTo(MqCode.MQ4008));
        }
    }

    @TckTest
    void ac_spr_17_mq4007_holds_on_the_fragment_route(TckDatabase db) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> JoinTestSupport.dataSource(db));
            context.register(MismatchedJpa.class, ModelQueryAutoConfiguration.class);
            assertThatThrownBy(context::refresh)
                    .satisfies(failure -> assertThat(rootCode(failure)).isEqualTo(MqCode.MQ4007));
        }
    }

    private static MqCode rootCode(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof ModelQueryConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("a ModelQueryConfigurationException in the causes of %s", failure).isNotNull();
        return ((ModelQueryConfigurationException) cause).code();
    }

    /**
     * The application's own factory bean, not extending {@code ModelQueryRepositoryFactoryBean}: the starter keeps it
     * and adds the fragment through {@code customImplementation}. Its {@code setRepositoryFragments} override records
     * that it, and not the starter's class, built each repository.
     */
    public static class PlainJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
            extends JpaRepositoryFactoryBean<T, S, I> {

        private static final Set<String> BUILT = new CopyOnWriteArraySet<>();

        private final Class<? extends T> repositoryInterface;

        public PlainJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
            super(repositoryInterface);
            this.repositoryInterface = repositoryInterface;
        }

        @Override
        public void setRepositoryFragments(RepositoryFragments repositoryFragments) {
            BUILT.add(repositoryInterface.getName());
            super.setRepositoryFragments(repositoryFragments);
        }

        static Set<String> built() {
            return BUILT;
        }
    }

    /** One repository keeping the model-query fragment next to the custom base method, one without the fragment. */
    interface CustomerProfileRepository extends JpaRepository<CustomerEntity, Long>,
            ModelQueryRepository<CustomerEntity>, CustomFactoryBeanTest.RefreshingRepository<CustomerEntity, Long> {}

    interface PlainCustomerRepository extends JpaRepository<CustomerEntity, Long>,
            CustomFactoryBeanTest.RefreshingRepository<CustomerEntity, Long> {}

    /** The application's {@code @EnableJpaRepositories}: a plain factory bean subclass and a custom base class. */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = PlainFactoryBeanTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = { CustomerProfileRepository.class, PlainCustomerRepository.class }),
            repositoryFactoryBeanClass = PlainJpaRepositoryFactoryBean.class,
            repositoryBaseClass = CustomFactoryBeanTest.RefreshingJpaRepository.class)
    static class PlainJpa {

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            return entityManagerFactoryBean(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }

    private static LocalContainerEntityManagerFactoryBean entityManagerFactoryBean(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan(CustomerEntity.class.getPackageName());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        return factory;
    }

    /** Type-checks every repository after the registrar ran and before the starter's fragment pass (D-83). */
    @Configuration(proxyBeanMethods = false)
    static class EarlyTypeCheck {

        @Bean
        static BeanDefinitionRegistryPostProcessor earlyTypeCheck() {
            return new EarlyTypeCheckProcessor();
        }
    }

    static final class EarlyTypeCheckProcessor implements BeanDefinitionRegistryPostProcessor, Ordered {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            var beanFactory = (DefaultListableBeanFactory) registry;
            for (String name : beanFactory.getBeanNamesForType(Repository.class)) {
                beanFactory.getMergedBeanDefinition(name);
                beanFactory.getType(name);
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}

        @Override
        public int getOrder() {
            return 0;
        }
    }

    /** Sets {@code customImplementation} on the ModelQueryRepository definition before the starter's fragment pass. */
    @Configuration(proxyBeanMethods = false)
    static class PresetCustomImplementation {

        @Bean
        static BeanDefinitionRegistryPostProcessor presetCustomImplementation() {
            return new PresetCustomImplementationProcessor();
        }
    }

    static final class PresetCustomImplementationProcessor implements BeanDefinitionRegistryPostProcessor, Ordered {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            for (String name : registry.getBeanDefinitionNames()) {
                BeanDefinition definition = registry.getBeanDefinition(name);
                if (definition instanceof RootBeanDefinition root && root.getTargetType() != null
                        && root.getResolvableType().resolveGeneric(0) == CustomerProfileRepository.class) {
                    definition.getPropertyValues().add("customImplementation", new RuntimeBeanReference("ownerImpl"));
                }
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}

        @Override
        public int getOrder() {
            return -100;
        }
    }

    /** A repository whose {@code ModelQueryRepository<E>} names another entity than its own domain type. */
    interface MismatchedRepository extends JpaRepository<OrderEntity, Long>, ModelQueryRepository<CustomerEntity> {}

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = PlainFactoryBeanTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MismatchedRepository.class),
            repositoryFactoryBeanClass = PlainJpaRepositoryFactoryBean.class)
    static class MismatchedJpa {

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            return entityManagerFactoryBean(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }

    /**
     * The application's own factory bean whose type arguments are fixed in its declaration, so its bean class does not
     * carry them: the starter has to resolve the repository interface from the definition's target type or its
     * constructor argument.
     */
    public static class CustomerProfileFactoryBean
            extends JpaRepositoryFactoryBean<CustomerProfileRepository, CustomerEntity, Long> {

        public CustomerProfileFactoryBean(Class<? extends CustomerProfileRepository> repositoryInterface) {
            super(repositoryInterface);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = PlainFactoryBeanTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CustomerProfileRepository.class),
            repositoryFactoryBeanClass = CustomerProfileFactoryBean.class,
            repositoryBaseClass = CustomFactoryBeanTest.RefreshingJpaRepository.class)
    static class NonGenericJpa {

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            return entityManagerFactoryBean(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }

    /**
     * Two factories over two datasources, the repository's own named {@code secondary}: the fragment takes the
     * definition's {@code entityManager}, so it reads through the secondary one even though the context holds two.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = PlainFactoryBeanTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CustomerProfileRepository.class),
            entityManagerFactoryRef = "secondary", transactionManagerRef = "secondaryTransactions",
            repositoryFactoryBeanClass = PlainJpaRepositoryFactoryBean.class,
            repositoryBaseClass = CustomFactoryBeanTest.RefreshingJpaRepository.class)
    static class TwoFactoriesJpa {

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(@Qualifier("dataSource") DataSource dataSource) {
            return entityManagerFactoryBean(dataSource);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean secondary(
                @Qualifier("secondaryDataSource") DataSource secondaryDataSource) {
            return entityManagerFactoryBean(secondaryDataSource);
        }

        @Bean
        PlatformTransactionManager secondaryTransactions(@Qualifier("secondary") EntityManagerFactory secondary) {
            return new JpaTransactionManager(secondary);
        }
    }

    /** The injections the fragment's missing autowire candidacy would make ambiguous. */
    static class ModelQueryInjections {

        @Autowired
        ModelQueryRepository<CustomerEntity> repository;

        @Autowired
        List<ModelQueryRepository<?>> all;
    }
}
