package com.rey.modelquery.tck.spr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.spring.data.ExecutorContext;
import com.rey.modelquery.spring.data.ModelQueryConfigurer;
import com.rey.modelquery.spring.data.ModelQueryExecutorDecorator;
import com.rey.modelquery.spring.data.ModelQueryRepositoryFactoryBean;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The repositories' executor decorators (R-SPR-18, AC-SPR-22) on the TCK's H2 database: the context's
 * {@code OrderRepository} and {@code CustomerRepository}, two repositories of different roots.
 */
class ExecutorDecoratorTest {

    record OrderRow(Long id, String status) {}

    private static final String TRANSACTIONS = "orderTransactions";

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ModelQuery<OrderEntity, Long, OrderRow> ORDER_ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS)))
            .select(SelectSet.of(ID, STATUS)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc()).build();
    /** A status longer than the column's 20 characters: the database refuses the statement. */
    private static final ModelUpdate<OrderEntity, ?> TOO_LONG_STATUS = ModelUpdate.builder(ORDERS)
            .primaryKey(PrimaryKey.of(ID)).set(STATUS, "X".repeat(30)).where(f -> f.eq(ID, 1L)).build();

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Long, CustomerEntity, Long> CUSTOMER_ID =
            ColumnField.of(Long.class, CUSTOMERS, "id", Long.class);
    private static final ModelQuery<CustomerEntity, Long, Long> CUSTOMER_IDS = ModelQuery
            .builder(CUSTOMERS, row -> row.get(CUSTOMER_ID)).select(SelectSet.of(CUSTOMER_ID))
            .primaryKey(PrimaryKey.of(CUSTOMER_ID)).orderBy(CUSTOMER_ID.asc()).build();

    @Test
    void ac_spr_22_decorators_wrap_every_repository_s_executor_in_order_over_what_the_fragment_built() {
        Trace trace = new Trace();
        try (var context = context(trace, Repositories.class, OrderedDecorators.class, Configured.class)) {
            assertThat(context.getBean(OrderRepository.class).findAll(ORDER_ROWS, Limit.of(2))).hasSize(2);
            assertThat(context.getBean(CustomerRepository.class).count(CUSTOMER_IDS)).isPositive();

            // The first in @Order wraps the executor itself; the last is what the repository calls.
            assertThat(trace.decorations).containsExactly("first decorates OrderEntity over the executor",
                    "second decorates OrderEntity over first", "first decorates CustomerEntity over the executor",
                    "second decorates CustomerEntity over first");
            assertThat(trace.calls).containsExactly("second list", "first list", "second count", "first count");

            EntityManagerFactory factory = context.getBean("orderEntities", EntityManagerFactory.class);
            ModelQueryConfig configured = context.getBean(Configured.class).configured;
            assertThat(trace.contexts).<Object>extracting(ExecutorContext::rootEntity).containsExactly(
                    OrderEntity.class, OrderEntity.class, CustomerEntity.class, CustomerEntity.class);
            for (ExecutorContext<?> seen : trace.contexts) {
                // The config after the configurer, which was handed the context's config bean.
                assertThat(seen.config()).isSameAs(configured);
                assertThat(seen.entityManagerFactory()).isSameAs(factory);
                // The shared EntityManager, bound to the repository's transaction.
                assertThat(seen.entityManager()).isInstanceOf(EntityManagerProxy.class);
                new TransactionTemplate(context.getBean(TRANSACTIONS, PlatformTransactionManager.class))
                        .executeWithoutResult(status -> assertThat(
                                ((EntityManagerProxy) seen.entityManager()).getTargetEntityManager())
                                .isSameAs(EntityManagerFactoryUtils.getTransactionalEntityManager(factory)));
            }
            assertThat(context.getBean(Configured.class).shared)
                    .isSameAs(context.getBean("modelQueryConfig", ModelQueryConfig.class));
            // What the first decorator wraps is the executor ModelQueryExecutor.create builds.
            try (EntityManager em = factory.createEntityManager()) {
                assertThat(trace.wrapped).hasSize(2).allSatisfy(executor -> assertThat(executor)
                        .isExactlyInstanceOf(ModelQueryExecutor.create(em, OrderEntity.class, configured).getClass()));
            }
        }
    }

    @Test
    void ac_spr_22_a_decorator_that_rethrows_leaves_the_application_the_exception_it_sees_with_no_decorator() {
        Throwable plain;
        try (var context = context(new Trace(), Repositories.class)) {
            plain = catchThrowable(() -> context.getBean(OrderRepository.class).update(TOO_LONG_STATUS));
        }
        // Spring translated the provider's exception.
        assertThat(plain).isInstanceOf(DataAccessException.class);

        Trace trace = new Trace();
        Throwable decorated;
        try (var context = context(trace, Repositories.class, OrderedDecorators.class)) {
            decorated = catchThrowable(() -> context.getBean(OrderRepository.class).update(TOO_LONG_STATUS));
        }
        assertThat(decorated).isExactlyInstanceOf(plain.getClass()).hasMessage(plain.getMessage());
        assertThat(decorated.getCause()).isExactlyInstanceOf(plain.getCause().getClass());
        // Each decorator saw the executor's own exception, before Spring's translation, and rethrew it.
        assertThat(trace.thrown).hasSize(2).allSatisfy(thrown -> assertThat(thrown).isSameAs(decorated.getCause()));
    }

    @Test
    void ac_spr_22_an_eager_repository_resolves_its_vendor_at_creation_and_its_decorators_on_the_first_call() {
        Trace trace = new Trace();
        try (var context = context(trace, Repositories.class, CountedDecorator.class)) {
            assertThat(trace.constructions).hasValue(0);
            assertThat(trace.decorations).isEmpty();

            context.getBean(OrderRepository.class).findAll(ORDER_ROWS, Limit.of(1));
            assertThat(trace.constructions).hasValue(1);
            assertThat(trace.decorations).containsExactly("counted decorates OrderEntity over the executor");

            // Each repository decorates its executor on its own first call, once.
            context.getBean(CustomerRepository.class).count(CUSTOMER_IDS);
            context.getBean(OrderRepository.class).findAll(ORDER_ROWS, Limit.of(1));
            assertThat(trace.constructions).hasValue(1);
            assertThat(trace.decorations).containsExactly("counted decorates OrderEntity over the executor",
                    "counted decorates CustomerEntity over the executor");
        }

        // Off the target vendors, the repository's executor fails the context's start: the vendor is still resolved
        // when the repository is created, and no decorator was asked for.
        Trace offTarget = new Trace();
        Throwable failure = catchThrowable(() -> context(offTarget, Repositories.class, CountedDecorator.class,
                OffTarget.class).close());
        assertThat(configurationFailure(failure).code()).isEqualTo(MqCode.MQ4012);
        assertThat(offTarget.constructions).hasValue(0);
    }

    @Test
    void ac_spr_22_a_decorator_bean_that_needs_a_repository_makes_no_creation_cycle() {
        Trace trace = new Trace();
        try (var context = context(trace, Repositories.class, RepositoryDecorator.class)) {
            assertThat(context.getBean(OrderRepository.class).findAll(ORDER_ROWS, Limit.of(1))).hasSize(1);
            assertThat(trace.decorations).containsExactly("needs customers decorates OrderEntity over the executor");
        }
    }

    @ParameterizedTest
    @ValueSource(classes = { LazyRepositories.class, DeferredRepositories.class })
    void ac_spr_22_a_lazy_or_deferred_bootstrap_touches_neither_executor_nor_decorators_before_the_first_call(
            Class<?> repositories) {
        Trace trace = new Trace();
        try (var context = context(trace, repositories, CountedDecorator.class)) {
            OrderRepository orders = context.getBean(OrderRepository.class);
            assertThat(trace.constructions).hasValue(0);

            assertThat(orders.findAll(ORDER_ROWS, Limit.of(1))).hasSize(1);
            assertThat(trace.constructions).hasValue(1);
            assertThat(trace.decorations).containsExactly("counted decorates OrderEntity over the executor");
        }

        // Off the target vendors, the context starts: the executor is created, and fails, on the first call only.
        Trace offTarget = new Trace();
        try (var context = context(offTarget, repositories, CountedDecorator.class, OffTarget.class)) {
            OrderRepository orders = context.getBean(OrderRepository.class);
            Throwable failure = catchThrowable(() -> orders.findAll(ORDER_ROWS, Limit.of(1)));
            assertThat(configurationFailure(failure).code()).isEqualTo(MqCode.MQ4012);
            assertThat(offTarget.constructions).hasValue(0);
        }
    }

    @Test
    void ac_spr_22_a_decorator_returning_null_fails_the_first_call_naming_it_and_every_later_call_alike() {
        Trace trace = new Trace();
        try (var context = context(trace, Repositories.class, NullDecorator.class)) {
            OrderRepository orders = context.getBean(OrderRepository.class);
            Throwable first = catchThrowable(() -> orders.findAll(ORDER_ROWS, Limit.of(1)));
            assertThat(first)
                    .isExactlyInstanceOf(NullPointerException.class)
                    .hasMessage("ModelQueryExecutorDecorator " + Returning.class.getName() + " returned null for "
                            + OrderEntity.class.getName() + "; return the executor it is given to leave it as it is");

            // The failure is kept: the later call gets it again, and neither decorator runs a second time.
            assertThat(catchThrowable(() -> orders.findAll(ORDER_ROWS, Limit.of(1)))).isSameAs(first);
            assertThat(trace.decorations).containsExactly("first decorates OrderEntity over the executor",
                    "returning null for OrderEntity");
        }
    }

    @ParameterizedTest
    @ValueSource(classes = { NullDecorator.class, ThrowingDecorator.class, FailingDecoratorBean.class })
    void ac_spr_22_a_failed_decoration_runs_once_under_concurrent_first_calls_and_is_rethrown_on_every_later_call(
            Class<?> failing) throws InterruptedException {
        Trace trace = new Trace();
        try (var context = context(trace, Repositories.class, failing)) {
            OrderRepository orders = context.getBean(OrderRepository.class);
            CountDownLatch start = new CountDownLatch(1);
            List<Throwable> thrown = new CopyOnWriteArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    thrown.add(catchThrowable(() -> orders.findAll(ORDER_ROWS, Limit.of(1))));
                });
                thread.start();
                threads.add(thread);
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(30_000);
            }
            thrown.add(catchThrowable(() -> orders.findAll(ORDER_ROWS, Limit.of(1))));

            assertThat(thrown).hasSize(9).doesNotContainNull();
            assertThat(thrown).allSatisfy(failure -> assertThat(failure).isSameAs(thrown.get(0)));
            // Every decorator, and the failing one's bean, ran once for all nine calls.
            assertThat(trace.decorations).doesNotHaveDuplicates().isNotEmpty();
            assertThat(trace.decorations.get(trace.decorations.size() - 1)).endsWith("for OrderEntity");
        }
    }

    /** A started context of {@code configurations} over the TCK's H2 database, with {@code trace} as a bean. */
    private static AnnotationConfigApplicationContext context(Trace trace, Class<?>... configurations) {
        var context = new AnnotationConfigApplicationContext();
        try {
            context.registerBean("dataSource", DataSource.class,
                    () -> JoinTestSupport.dataSource(TckDatabases.get(TckTarget.h2())));
            context.registerBean(Trace.class, () -> trace);
            context.register(configurations);
            context.refresh();
            return context;
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    private static ModelQueryConfigurationException configurationFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof ModelQueryConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("a ModelQueryConfigurationException in the causes of %s", failure).isNotNull();
        return (ModelQueryConfigurationException) cause;
    }

    /** What the decorators of one context did, in order. */
    static final class Trace {
        final AtomicInteger constructions = new AtomicInteger();
        final List<String> decorations = new CopyOnWriteArrayList<>();
        final List<ExecutorContext<?>> contexts = new CopyOnWriteArrayList<>();
        final List<Object> wrapped = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Throwable> thrown = new CopyOnWriteArrayList<>();
    }

    /** Wraps each executor in a proxy that notes each call and rethrows what the executor throws, unchanged. */
    static class Tagging implements ModelQueryExecutorDecorator {
        private final String name;
        private final Trace trace;

        Tagging(String name, Trace trace) {
            this.name = name;
            this.trace = trace;
            trace.constructions.incrementAndGet();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context) {
            String over = Proxy.isProxyClass(executor.getClass())
                    && Proxy.getInvocationHandler(executor) instanceof Tag tag ? tag.name : "the executor";
            if (over.equals("the executor")) {
                trace.wrapped.add(executor);
            }
            trace.decorations.add(name + " decorates " + context.rootEntity().getSimpleName() + " over " + over);
            trace.contexts.add(context);
            return (ModelQueryExecutor<E>) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { ModelQueryExecutor.class }, new Tag(name, executor, trace));
        }
    }

    private record Tag(String name, Object delegate, Trace trace) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> name;
                };
            }
            trace.calls.add(name + " " + method.getName());
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                trace.thrown.add(e.getCause());
                throw e.getCause();
            }
        }
    }

    @Order(1)
    static final class First extends Tagging {
        First(Trace trace) {
            super("first", trace);
        }
    }

    @Order(2)
    static final class Second extends Tagging {
        Second(Trace trace) {
            super("second", trace);
        }
    }

    /** A decorator that returns what the application gave it nothing to return: {@code null}. */
    static final class Returning implements ModelQueryExecutorDecorator {
        private final Trace trace;

        Returning(Trace trace) {
            this.trace = trace;
        }

        @Override
        public <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context) {
            trace.decorations.add("returning null for " + context.rootEntity().getSimpleName());
            return null;
        }
    }

    /** A decorator that fails instead of wrapping. */
    static final class Throwing implements ModelQueryExecutorDecorator {
        private final Trace trace;

        Throwing(Trace trace) {
            this.trace = trace;
        }

        @Override
        public <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context) {
            trace.decorations.add("throwing for " + context.rootEntity().getSimpleName());
            throw new DecorationFailure("no metrics registry for " + context.rootEntity().getSimpleName());
        }
    }

    /** A decorator's own failure, which Spring's exception translation leaves as it is. */
    static final class DecorationFailure extends RuntimeException {
        DecorationFailure(String message) {
            super(message);
        }
    }

    /** The TCK's entities, {@code OrderRepository} and {@code CustomerRepository}, created with the context. */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class, entityManagerFactoryRef = "orderEntities",
            transactionManagerRef = TRANSACTIONS, repositoryFactoryBeanClass = ModelQueryRepositoryFactoryBean.class)
    static class Repositories extends ModelQueryRepositoryTest.JpaBeans {}

    /** The repositories of {@link Repositories}, created when first asked for. */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class, entityManagerFactoryRef = "orderEntities",
            transactionManagerRef = TRANSACTIONS, repositoryFactoryBeanClass = ModelQueryRepositoryFactoryBean.class,
            bootstrapMode = BootstrapMode.LAZY)
    static class LazyRepositories extends ModelQueryRepositoryTest.JpaBeans {}

    /** The repositories of {@link Repositories}, created once the context has started. */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class, entityManagerFactoryRef = "orderEntities",
            transactionManagerRef = TRANSACTIONS, repositoryFactoryBeanClass = ModelQueryRepositoryFactoryBean.class,
            bootstrapMode = BootstrapMode.DEFERRED)
    static class DeferredRepositories extends ModelQueryRepositoryTest.JpaBeans {}

    /** Two decorators, declared in the reverse of their {@code @Order}. */
    @Configuration(proxyBeanMethods = false)
    static class OrderedDecorators {
        @Bean
        Second second(Trace trace) {
            return new Second(trace);
        }

        @Bean
        First first(Trace trace) {
            return new First(trace);
        }
    }

    /** A decorator bean created only when first asked for, so its construction shows when that is. */
    @Configuration(proxyBeanMethods = false)
    static class CountedDecorator {
        @Bean
        @Lazy
        ModelQueryExecutorDecorator counted(Trace trace) {
            return new Tagging("counted", trace);
        }
    }

    /** A decorator bean created with the context that needs a repository, as a metrics decorator might. */
    @Configuration(proxyBeanMethods = false)
    static class RepositoryDecorator {
        @Bean
        ModelQueryExecutorDecorator needsCustomers(CustomerRepository customers, Trace trace) {
            assertThat(customers).isNotNull();
            return new Tagging("needs customers", trace);
        }
    }

    /** {@code First}, then a decorator returning {@code null}. */
    @Configuration(proxyBeanMethods = false)
    static class NullDecorator {
        @Bean
        First first(Trace trace) {
            return new First(trace);
        }

        @Bean
        @Order(2)
        Returning returning(Trace trace) {
            return new Returning(trace);
        }
    }

    /** {@code First}, then a decorator that throws. */
    @Configuration(proxyBeanMethods = false)
    static class ThrowingDecorator {
        @Bean
        First first(Trace trace) {
            return new First(trace);
        }

        @Bean
        @Order(2)
        Throwing throwing(Trace trace) {
            return new Throwing(trace);
        }
    }

    /** A decorator bean whose creation fails when the first call asks for it. */
    @Configuration(proxyBeanMethods = false)
    static class FailingDecoratorBean {
        @Bean
        @Lazy
        ModelQueryExecutorDecorator failing(Trace trace) {
            trace.decorations.add("creating the failing decorator for OrderEntity");
            throw new DecorationFailure("the metrics registry is down");
        }
    }

    /** The context's config and a configurer returning another for every repository, noting both. */
    @Configuration(proxyBeanMethods = false)
    static class Configured implements ModelQueryConfigurer {
        ModelQueryConfig shared;
        ModelQueryConfig configured;

        @Bean
        ModelQueryConfig modelQueryConfig() {
            return ModelQueryConfig.defaults();
        }

        @Override
        public synchronized ModelQueryConfig configure(ModelQueryConfig shared, EntityManagerFactory factory) {
            if (configured == null) {
                this.shared = shared;
                this.configured = shared.exportPageSize(7);
            }
            return configured;
        }
    }

    /** A config whose only target vendor is not the H2 the repositories are on. */
    @Configuration(proxyBeanMethods = false)
    static class OffTarget {
        @Bean
        ModelQueryConfig modelQueryConfig() {
            return ModelQueryConfig.defaults().targetVendors(DatabaseVendor.POSTGRESQL);
        }
    }
}
