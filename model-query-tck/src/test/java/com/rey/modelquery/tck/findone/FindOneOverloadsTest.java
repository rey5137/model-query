package com.rey.modelquery.tck.findone;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.spring.data.ModelQueryRepositoryFactoryBean;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Example;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.query.QueryByExampleExecutor;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A repository that extends Spring Data's {@code findOne(Example)} and {@code findOne(Specification)} next to
 * {@code ModelQueryRepository}'s {@code findOne(ModelQuery)}: each call reaches its own implementation, and
 * {@code findFirst} and {@code findByKey} are fragment methods, not derived queries (R-SPR-15, AC-SPR-18).
 *
 * <p>This lives in its own package so that a scan of {@code tck.spr} does not pick up its repository.
 */
class FindOneOverloadsTest {

    record OrderRow(Long id, String status) {}

    interface OrderLookupRepository extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity>,
            QueryByExampleExecutor<OrderEntity>, ModelQueryRepository<OrderEntity> {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);

    /** Every order, keyed by id. */
    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ORDER_ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS)))
            .select(SelectSet.of(ID, STATUS))
            .primaryKey(PrimaryKey.of(ID));

    // ---- AC-SPR-18

    @TckTest
    void ac_spr_18_each_find_one_reaches_its_own_implementation_and_find_first_and_find_by_key_are_not_derived(
            TckDatabase db) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> JoinTestSupport.dataSource(db));
            context.register(LookupJpa.class);
            // Startup parses every query method: a findFirst or findByKey taken as a derived query would fail here.
            context.refresh();
            OrderLookupRepository repository = context.getBean(OrderLookupRepository.class);

            Optional<OrderEntity> byExample = repository.findOne(Example.of(orderWithId(7L)));
            Specification<OrderEntity> eight = (root, query, cb) -> cb.equal(root.get("id"), 8L);
            Optional<OrderEntity> bySpecification = repository.findOne(eight);
            Optional<OrderRow> byModelQuery = repository.findOne(ORDER_ROWS.where(f -> f.eq(ID, 9L)).build());

            assertThat(byExample).map(OrderEntity::getId).contains(7L);
            assertThat(bySpecification).map(OrderEntity::getId).contains(8L);
            assertThat(byModelQuery).map(OrderRow::id).contains(9L);

            ModelQuery<OrderEntity, Long, OrderRow> keyed = ORDER_ROWS.build();
            assertThat(repository.findFirst(keyed)).map(OrderRow::id).contains(1L);
            assertThat(repository.findByKey(keyed, 42L)).map(OrderRow::id).contains(42L);
        }
    }

    /** A probe with only its id set. */
    private static OrderEntity orderWithId(long id) {
        OrderEntity probe = new OrderEntity();
        probe.setId(id);
        return probe;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = FindOneOverloadsTest.class, considerNestedRepositories = true,
            repositoryFactoryBeanClass = ModelQueryRepositoryFactoryBean.class)
    static class LookupJpa {

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan(OrderEntity.class.getPackageName());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }
}
