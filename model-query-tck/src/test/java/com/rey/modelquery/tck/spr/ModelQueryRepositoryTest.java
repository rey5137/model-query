package com.rey.modelquery.tck.spr;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.spring.data.ModelQueryRepositoryFactoryBean;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code ModelQueryRepository} over the plain-JPA executor (integration/50 §1). */
class ModelQueryRepositoryTest {

    record OrderRow(Long id, String status, BigDecimal total) {}

    /** What the body of a {@code stream} saw of the transaction it ran in. */
    record StreamedIn(long rows, boolean active, boolean readOnly, String name) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);

    private static final ModelQuery<OrderEntity, ?, OrderRow> NEW_ORDERS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
            .select(SelectSet.of(ID, STATUS, TOTAL))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc())
            .where(f -> f.eq(STATUS, Optional.of("NEW")))
            .build();

    private static final String TRANSACTIONS = "orderTransactions";

    /** The ids above which a test inserts orders of its own, and removes them again. */
    private static final long TEMPORARY = 100_000;
    private static final UnaryOperator<Filters<OrderRow>> TEMPORARY_ORDERS = f -> f.gt(ID, TEMPORARY);
    private static final ModelUpdate<OrderEntity, OrderRow> PAY_TEMPORARY_ORDERS = ModelUpdate.builder(ORDERS)
            .primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID").where(TEMPORARY_ORDERS).build();
    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Long, CustomerEntity, Long> CUSTOMER_ID =
            ColumnField.of(Long.class, CUSTOMERS, "id", Long.class);
    private static final ModelDelete<CustomerEntity, Long> DELETE_TEMPORARY_CUSTOMERS = ModelDelete.builder(CUSTOMERS)
            .primaryKey(PrimaryKey.of(CUSTOMER_ID)).where(f -> f.gt(CUSTOMER_ID, TEMPORARY)).build();

    // ---- AC-SPR-01

    @TckTest
    void ac_spr_01_find_all_runs_the_executors_sql_and_returns_its_rows(TckDatabase db) {
        assertSameAsExecutor(db,
                executor -> executor.list(NEW_ORDERS, Limit.of(25)),
                repository -> repository.findAll(NEW_ORDERS, Limit.of(25)));
    }

    @TckTest
    void ac_spr_01_count_runs_the_executors_sql_and_returns_its_count(TckDatabase db) {
        assertSameAsExecutor(db, executor -> executor.count(NEW_ORDERS), repository -> repository.count(NEW_ORDERS));
    }

    @TckTest
    void ac_spr_01_stream_runs_the_executors_sql_and_passes_its_rows(TckDatabase db) {
        assertSameAsExecutor(db,
                executor -> executor.stream(NEW_ORDERS, Limit.of(40), rows -> rows.toList()),
                repository -> repository.stream(NEW_ORDERS, Limit.of(40), rows -> rows.toList()));
    }

    @TckTest
    void ac_spr_01_export_runs_the_executors_sql_and_sinks_its_items(TckDatabase db) {
        assertSameAsExecutor(db,
                executor -> exported(sink -> executor.export(NEW_ORDERS, ExportOptions.of(300),
                        page -> page.stream().map(OrderRow::id).toList(), sink::add)),
                repository -> exported(sink -> repository.export(NEW_ORDERS, ExportOptions.of(300),
                        page -> page.stream().map(OrderRow::id).toList(), sink::add)));
    }

    // ---- AC-SPR-03

    @TckTest
    void ac_spr_03_stream_without_a_transaction_opens_a_read_only_one_of_the_repositorys_manager(TckDatabase db) {
        StreamedIn streamed = withRepository(JoinTestSupport.dataSource(db), DefaultTransactions.class,
                (repository, context) -> streamNewOrders(repository));

        assertThat(streamed).isEqualTo(new StreamedIn(TckFixture.ORDERS / 4, true, true, null));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @TckTest
    void ac_spr_03_stream_opens_its_transaction_with_default_transactions_disabled(TckDatabase db) {
        StreamedIn streamed = withRepository(JoinTestSupport.dataSource(db), NoDefaultTransactions.class,
                (repository, context) -> streamNewOrders(repository));

        assertThat(streamed).isEqualTo(new StreamedIn(TckFixture.ORDERS / 4, true, true, null));
    }

    @TckTest
    void ac_spr_03_stream_joins_an_active_transaction(TckDatabase db) {
        StreamedIn streamed = withRepository(JoinTestSupport.dataSource(db), DefaultTransactions.class,
                (repository, context) -> {
                    TransactionTemplate outer =
                            new TransactionTemplate(context.getBean(TRANSACTIONS, PlatformTransactionManager.class));
                    outer.setName("outer");
                    return outer.execute(status -> {
                        status.setRollbackOnly();
                        // Uncommitted in the outer transaction: only a stream that joined it reads these rows as NEW.
                        EntityManagerFactoryUtils
                                .getTransactionalEntityManager(context.getBean(EntityManagerFactory.class))
                                .createQuery("update OrderEntity o set o.status = 'NEW' where o.status = 'PAID'")
                                .executeUpdate();
                        return streamNewOrders(repository);
                    });
                });

        assertThat(streamed).isEqualTo(new StreamedIn(TckFixture.ORDERS / 2, true, false, "outer"));
    }

    // ---- AC-SPR-09

    @TckTest
    void ac_spr_09_update_and_delete_without_a_transaction_open_one_on_the_repositorys_manager(TckDatabase db) {
        DataSource dataSource = JoinTestSupport.dataSource(db);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            insertTemporaryOrders(jdbc, 3);
            StarterTest.insertTemporaryCustomers(jdbc, 2);
            // The default-named manager throws when used, so these succeed only on the repository's own.
            withRepository(dataSource, DefaultTransactions.class, (repository, context) -> {
                assertThat(repository.update(PAY_TEMPORARY_ORDERS)).isEqualTo(3);
                assertThat(statuses(jdbc)).containsExactly("PAID", "PAID", "PAID");
                assertThat(context.getBean(CustomerRepository.class).delete(DELETE_TEMPORARY_CUSTOMERS))
                        .isEqualTo(2);
                return null;
            });

            assertThat(StarterTest.temporaryCustomers(jdbc)).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        } finally {
            jdbc.update("delete from orders where id > " + TEMPORARY);
            jdbc.update("delete from customers where id > " + TEMPORARY);
        }
    }

    @TckTest
    void ac_spr_09_update_joins_an_active_transaction(TckDatabase db) {
        DataSource dataSource = JoinTestSupport.dataSource(db);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            insertTemporaryOrders(jdbc, 3);
            long written = withRepository(dataSource, DefaultTransactions.class, (repository, context) -> {
                TransactionTemplate outer =
                        new TransactionTemplate(context.getBean(TRANSACTIONS, PlatformTransactionManager.class));
                return outer.execute(status -> {
                    status.setRollbackOnly();
                    return repository.update(PAY_TEMPORARY_ORDERS);
                });
            });

            // The outer transaction rolled back, and the update with it.
            assertThat(written).isEqualTo(3);
            assertThat(statuses(jdbc)).containsExactly("NEW", "NEW", "NEW");
        } finally {
            jdbc.update("delete from orders where id > " + TEMPORARY);
        }
    }

    @TckTest
    void ac_spr_09_a_commit_each_chunk_update_opens_no_transaction_and_commits_through_the_callback(
            TckDatabase db) {
        DataSource dataSource = JoinTestSupport.dataSource(db);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var update = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID")
                .where(TEMPORARY_ORDERS).chunked(ChunkOptions.size(2).commitEachChunk()).build();
        try {
            insertTemporaryOrders(jdbc, 5);
            RecordingChunks.ACTIVE.clear();
            long written = withRepository(dataSource, ChunkedTransactions.class,
                    (repository, context) -> repository.update(update));

            assertThat(written).isEqualTo(5);
            assertThat(statuses(jdbc)).containsOnly("PAID").hasSize(5);
            // Three chunks of at most two keys, and the last one finding none left, none inside a Spring transaction.
            assertThat(RecordingChunks.ACTIVE).containsExactly(false, false, false);
        } finally {
            jdbc.update("delete from orders where id > " + TEMPORARY);
        }
    }

    // ---- support

    /** Commits {@code count} orders of customer 1 above {@link #TEMPORARY}, with status {@code NEW}. */
    private static void insertTemporaryOrders(JdbcTemplate jdbc, int count) {
        for (long id = TEMPORARY + 1; id <= TEMPORARY + count; id++) {
            jdbc.update("insert into orders (id, customer_id, status, total, placed_at) values (?, 1, 'NEW', 0, ?)",
                    id, Timestamp.valueOf("2020-01-01 00:00:00"));
        }
    }

    private static List<String> statuses(JdbcTemplate jdbc) {
        return jdbc.queryForList("select status from orders where id > " + TEMPORARY + " order by id", String.class);
    }

    /**
     * A plain-JPA callback, a resource-local {@code EntityManager} per chunk, that records whether a Spring
     * transaction was active when each chunk began.
     */
    static final class RecordingChunks implements ChunkTransactions {

        static final List<Boolean> ACTIVE = new ArrayList<>();

        @Override
        public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
            ACTIVE.add(TransactionSynchronizationManager.isActualTransactionActive());
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                T result = chunk.apply(em);
                em.getTransaction().commit();
                return result;
            } finally {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
                em.close();
            }
        }
    }

    private static StreamedIn streamNewOrders(OrderRepository repository) {
        return repository.stream(NEW_ORDERS, Limit.unlimited(), rows -> new StreamedIn(
                rows.count(),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                TransactionSynchronizationManager.getCurrentTransactionName()));
    }

    /**
     * Runs {@code viaExecutor} on the plain-JPA executor and {@code viaRepository} on the repository, each over its
     * own captured DataSource, asserts both ran the same, non-empty SQL and gave the same result (R-SPR-01), and
     * returns that result.
     */
    static <X> X assertSameAsExecutor(TckDatabase db,
            Function<ModelQueryExecutor<OrderEntity>, X> viaExecutor, Function<OrderRepository, X> viaRepository) {
        AtomicReference<X> expected = new AtomicReference<>();
        List<String> executorSql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                // In a transaction, which PostgreSQL streaming needs without Spring.
                sf.inTransaction(em -> expected.set(viaExecutor.apply(
                        ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()))));
            }
        });
        AtomicReference<X> actual = new AtomicReference<>();
        List<String> repositorySql = SqlSnapshots.capture(db, ds -> actual.set(withRepository(ds,
                DefaultTransactions.class, (repository, context) -> viaRepository.apply(repository))));

        assertThat(repositorySql).isNotEmpty().isEqualTo(executorSql);
        assertThat(actual.get()).isEqualTo(expected.get());
        return actual.get();
    }

    private static List<Long> exported(Function<List<Long>, Long> export) {
        List<Long> sunk = new ArrayList<>();
        long passed = export.apply(sunk);
        assertThat(passed).isEqualTo(sunk.size());
        return sunk;
    }

    @FunctionalInterface
    interface RepositoryWork<X> {
        X run(OrderRepository repository, AnnotationConfigApplicationContext context);
    }

    /** Starts a Spring context of {@code configuration} over {@code dataSource}, runs {@code work}, and closes it. */
    static <X> X withRepository(DataSource dataSource, Class<?> configuration, RepositoryWork<X> work) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> dataSource);
            context.register(configuration);
            context.refresh();
            return work.run(context.getBean(OrderRepository.class), context);
        }
    }

    /**
     * The TCK's entities over the context's DataSource, with the repository's transaction manager not named the
     * default, and a default-named one that fails if used, so a stream only succeeds on the repository's own manager.
     */
    abstract static class JpaBeans {

        @Bean
        LocalContainerEntityManagerFactoryBean orderEntities(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan(CustomerEntity.class.getPackageName());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            return factory;
        }

        @Bean(TRANSACTIONS)
        JpaTransactionManager orderTransactions(EntityManagerFactory orderEntities) {
            return new JpaTransactionManager(orderEntities);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    throw new IllegalStateException("not the repository's transaction manager");
                }

                @Override
                public void commit(TransactionStatus status) {
                    throw new IllegalStateException("not the repository's transaction manager");
                }

                @Override
                public void rollback(TransactionStatus status) {
                    throw new IllegalStateException("not the repository's transaction manager");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class, entityManagerFactoryRef = "orderEntities",
            transactionManagerRef = TRANSACTIONS, repositoryFactoryBeanClass = ModelQueryRepositoryFactoryBean.class)
    static class DefaultTransactions extends JpaBeans {}

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class, entityManagerFactoryRef = "orderEntities",
            transactionManagerRef = TRANSACTIONS, repositoryFactoryBeanClass = ModelQueryRepositoryFactoryBean.class,
            enableDefaultTransactions = false)
    static class NoDefaultTransactions extends JpaBeans {}

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class, entityManagerFactoryRef = "orderEntities",
            transactionManagerRef = TRANSACTIONS, repositoryFactoryBeanClass = ModelQueryRepositoryFactoryBean.class)
    static class ChunkedTransactions extends JpaBeans {

        @Bean
        ModelQueryConfig modelQueryConfig() {
            return ModelQueryConfig.defaults().chunkTransactions(new RecordingChunks());
        }
    }
}
