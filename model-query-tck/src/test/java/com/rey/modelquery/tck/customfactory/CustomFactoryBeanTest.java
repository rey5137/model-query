package com.rey.modelquery.tck.customfactory;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spring.boot.ModelQueryAutoConfiguration;
import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.spring.data.ModelQueryRepositoryFactoryBean;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.data.repository.Repository;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * An application that keeps its own factory bean class and repository base class: the starter leaves a repository
 * with a factory bean class of its own alone (R-SPR-02, D-50), and a factory bean extending
 * {@link ModelQueryRepositoryFactoryBean} adds the model-query fragment only to the repositories that declare
 * {@code ModelQueryRepository}, keeping the custom base class for all of them (R-SPR-12, D-83, AC-SPR-15).
 *
 * <p>This lives in its own package so that a scan of {@code tck.spr} does not pick up these repositories.
 */
class CustomFactoryBeanTest {

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Long, CustomerEntity, Long> ID =
            ColumnField.of(Long.class, CUSTOMERS, "id", Long.class);
    private static final ModelQuery<CustomerEntity, ?, Long> CUSTOMER_IDS = ModelQuery
            .builder(CUSTOMERS, row -> row.get(ID)).select(SelectSet.of(ID)).primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc()).build();

    // ---- AC-SPR-15

    @TckTest
    void ac_spr_15_a_custom_factory_bean_and_base_class_start_and_add_the_fragment_only_where_declared(
            TckDatabase db) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> JoinTestSupport.dataSource(db));
            // The starter is loaded, as in the adopter's application: its post-processor leaves these alone.
            context.register(CustomJpa.class, ModelQueryAutoConfiguration.class);
            context.refresh();

            // Both repositories were built with the application's own factory bean, not the starter's.
            assertThat(definitionClass(context, CustomerProfileRepository.class))
                    .isEqualTo(CustomJpaRepositoryFactoryBean.class.getName());
            assertThat(definitionClass(context, PlainCustomerRepository.class))
                    .isEqualTo(CustomJpaRepositoryFactoryBean.class.getName());

            // The custom base class built both: its own method works on each.
            assertThat(context.getBean(CustomerProfileRepository.class).refreshAndGet(1L)).isNotNull();
            assertThat(context.getBean(PlainCustomerRepository.class).refreshAndGet(1L)).isNotNull();

            // Only the repository extending ModelQueryRepository carries the fragment.
            assertThat(context.getBean(CustomerProfileRepository.class)).isInstanceOf(ModelQueryRepository.class);
            assertThat(context.getBean(PlainCustomerRepository.class)).isNotInstanceOf(ModelQueryRepository.class);

            // The fragment works on the first, and the second is still a plain JpaRepository.
            assertThat(context.getBean(CustomerProfileRepository.class)
                    .findPage(CUSTOMER_IDS, PageRequest.of(0, 2), CountMode.COUNT).getContent()).hasSize(2);
            assertThat(context.getBean(CustomerProfileRepository.class).findAll(CUSTOMER_IDS, Limit.unlimited()))
                    .hasSize((int) context.getBean(PlainCustomerRepository.class).count()).hasSizeGreaterThan(2);
            assertThat(context.getBean(PlainCustomerRepository.class).findAll()).isNotEmpty();
        }
    }

    private static String definitionClass(AnnotationConfigApplicationContext context, Class<?> type) {
        String name = context.getBeanNamesForType(type)[0];
        return context.getBeanFactory().getBeanDefinition(name).getBeanClassName();
    }

    /** A repository base interface of the application's own, implemented by {@link RefreshingJpaRepository}. */
    interface RefreshingRepository<T, ID> {
        T refreshAndGet(ID id);
    }

    /** The application's own repository base class: a {@code SimpleJpaRepository} that adds {@code refreshAndGet}. */
    public static class RefreshingJpaRepository<T, ID> extends SimpleJpaRepository<T, ID>
            implements RefreshingRepository<T, ID> {

        private final EntityManager entityManager;

        public RefreshingJpaRepository(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager) {
            super(entityInformation, entityManager);
            this.entityManager = entityManager;
        }

        @Override
        public T refreshAndGet(ID id) {
            T entity = findById(id).orElse(null);
            if (entity != null) {
                entityManager.refresh(entity);
            }
            return entity;
        }
    }

    /**
     * The application's own factory bean: extending {@link ModelQueryRepositoryFactoryBean} rather than
     * {@code JpaRepositoryFactoryBean} is what adds the fragment, and it keeps the custom base class.
     */
    public static class CustomJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
            extends ModelQueryRepositoryFactoryBean<T, S, I> {
        public CustomJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
            super(repositoryInterface);
        }
    }

    /** One repository keeping the model-query fragment next to the custom base method, one without the fragment. */
    interface CustomerProfileRepository extends JpaRepository<CustomerEntity, Long>,
            ModelQueryRepository<CustomerEntity>, RefreshingRepository<CustomerEntity, Long> {}

    interface PlainCustomerRepository extends JpaRepository<CustomerEntity, Long>,
            RefreshingRepository<CustomerEntity, Long> {}

    /** The application's {@code @EnableJpaRepositories}, naming its own factory bean class and base class. */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = CustomFactoryBeanTest.class, considerNestedRepositories = true,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = { CustomerProfileRepository.class, PlainCustomerRepository.class }),
            repositoryFactoryBeanClass = CustomJpaRepositoryFactoryBean.class,
            repositoryBaseClass = RefreshingJpaRepository.class)
    static class CustomJpa {

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan(CustomerEntity.class.getPackageName());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }
}
