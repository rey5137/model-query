package com.rey.modelquery.tck.portability;

import static com.rey.modelquery.jpa.spi.DatabaseVendor.H2;
import static com.rey.modelquery.jpa.spi.DatabaseVendor.MYSQL;
import static com.rey.modelquery.jpa.spi.DatabaseVendor.POSTGRESQL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.portability.CallRecorder;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.RecordedCall;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.portability.TransactionState;
import com.rey.modelquery.spring.boot.ModelQueryAutoConfiguration;
import com.rey.modelquery.spring.data.ExecutorContext;
import com.rey.modelquery.spring.data.ModelQueryExecutorDecorator;
import com.rey.modelquery.spring.data.ModelQueryRepository;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.SampleValueEntity;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.example.metrics.Timed;
import org.example.orders.OrderController;
import org.example.orders.OrderService;
import org.example.orders.TimingExecutor;
import org.example.orders.TimingHandler;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AC-INS-14: calls recorded on H2 through {@code CallRecorder.wrap}, on an executor and through a Spring repository's
 * decorator (R-SPR-18), replayed on each Tier-1 target without a container.
 */
class RecordedCallPortabilityTest {

    private static final List<Target> TARGETS = List.of(Target.of(H2, "2.5.252"), Target.of(POSTGRESQL, "17.0"),
            Target.of(MYSQL, "8.4.0"));

    record OrderRow(Long id, String status) {}
    record SampleRow(Long id) {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ROOT, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ROOT, "status", String.class);
    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ORDERS_BUILDER =
            ModelQuery.builder(ROOT, row -> new OrderRow(row.get(ID), row.get(STATUS)))
                    .select(SelectSet.of(ID, STATUS)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc());
    private static final ModelQuery<OrderEntity, Long, OrderRow> ORDERS = ORDERS_BUILDER.build();
    private static final ModelUpdate<OrderEntity, ?> HOLD = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID))
            .set(STATUS, "HELD").where(f -> f.eq(ID, 1L)).build();
    private static final ModelUpdate<OrderEntity, ?> HOLD_IN_CHUNKS = ModelUpdate.builder(ROOT)
            .primaryKey(PrimaryKey.of(ID)).set(STATUS, "HELD").where(f -> f.eq(ID, 1L))
            .chunked(ChunkOptions.size(10).commitEachChunk()).build();

    private static final TableField<SampleValueEntity, SampleValueEntity> SAMPLE_ROOT =
            TableField.root(SampleValueEntity.class);
    private static final ColumnField<SampleRow, SampleValueEntity, Long> SAMPLE_ID =
            ColumnField.of(SampleRow.class, SAMPLE_ROOT, "id", Long.class);
    private static final ModelQuery<SampleValueEntity, Long, SampleRow> SAMPLES =
            ModelQuery.builder(SAMPLE_ROOT, row -> new SampleRow(row.get(SAMPLE_ID)))
                    .select(SelectSet.of(SAMPLE_ID)).primaryKey(PrimaryKey.of(SAMPLE_ID)).orderBy(SAMPLE_ID.asc())
                    .build();

    private static SessionFactory factory;

    @BeforeAll
    static void boot() {
        factory = JoinTestSupport.sessionFactory(TckDatabases.get(TckTarget.h2()));
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void ac_ins_14_a_wrapped_executor_records_each_call_and_replays_it_on_the_targets() {
        CallRecorder recorder = CallRecorder.create();
        AtomicInteger applicationRuns = new AtomicInteger();
        inTransaction(em -> {
            OrderService service = new OrderService(recorder.wrap(OrderEntity.class, executor(em), em));
            service.search(ORDERS);
            service.find(ORDERS_BUILDER.where(f -> f.eq(ID, 1L)).build());
            service.find(ORDERS, 1L);
            service.streamed(ORDERS, rows -> applicationRuns.addAndGet((int) rows.count()));
            service.export(ORDERS_BUILDER.where(f -> f.lte(ID, 4L)).build(), row -> applicationRuns.incrementAndGet());
        });
        assertThat(applicationRuns).hasValue(7);

        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).extracting(call -> call.method().getName())
                .containsExactly("page", "one", "one", "stream", "export");
        // The overloads of one are two methods.
        assertThat(calls.get(1).method()).isNotEqualTo(calls.get(2).method());
        assertThat(calls).extracting(call -> call.method().getParameterCount()).containsExactly(3, 1, 2, 3, 4);
        assertThat(calls).extracting(RecordedCall::inTransaction).containsOnly(TransactionState.IN);
        assertThat(calls).extracting(RecordedCall::label).allMatch(Pattern.compile(
                "OrderService\\.(search|find|streamed|export):\\d+ (page|one|stream|export)").asMatchPredicate());
        // Each function argument is a marker, not the application's closure.
        assertThat(calls.get(3).arguments().get(2)).isNotInstanceOf(Function.class)
                .hasToString("<Function argument 2>");
        assertThat(calls.get(4).arguments().subList(2, 4)).noneMatch(argument -> argument instanceof Function<?, ?>
                || argument instanceof Consumer<?>);

        List<String> lines = report(check(ModelQueryConfig.defaults()).replay(calls));
        assertThat(lines.get(0)).isEqualTo("Not portable: 3 of 21 results failed");
        for (Target target : TARGETS) {
            String on = Pattern.quote(" on " + target + ": REACHED ");
            assertThat(lines).anyMatch(line -> line.matches("OrderService\\.search:\\d+ page \\[count]" + on + ".*"))
                    .anyMatch(line -> line.matches("OrderService\\.search:\\d+ page \\[content]" + on + ".*"))
                    .anyMatch(line -> line.matches("OrderService\\.streamed:\\d+ stream" + on + ".*"))
                    .anyMatch(line -> line.matches("OrderService\\.export:\\d+ export" + on + ".*"));
            assertThat(lines).filteredOn(line -> line.matches("OrderService\\.find:\\d+ one" + on + ".*")).hasSize(2);
        }
        // Replay passed the harness's own functions: the application's closures did not run again.
        assertThat(applicationRuns).hasValue(7);
    }

    @Test
    void ac_ins_14_equal_calls_are_one_record_and_identical_result_lines_collapse() {
        CallRecorder recorder = CallRecorder.create();
        inTransaction(em -> {
            OrderService service = new OrderService(recorder.wrap(OrderEntity.class, executor(em), em));
            for (int i = 0; i < 3; i++) {
                service.find(ORDERS, 1L);
            }
            // A query built per request has no value equality: a record per call, one report line per target.
            service.find(ORDERS_BUILDER.where(f -> f.eq(ID, 2L)).build());
            service.find(ORDERS_BUILDER.where(f -> f.eq(ID, 2L)).build());
        });

        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).extracting(RecordedCall::times).containsExactly(3L, 1L, 1L);
        assertThat(calls.get(0).label()).matches("OrderService\\.find:\\d+ one x3");

        List<String> lines = report(check(ModelQueryConfig.defaults()).replay(calls));
        assertThat(lines.get(0)).isEqualTo("Not portable: 3 of 9 results failed");
        for (Target target : TARGETS) {
            String on = Pattern.quote(" on " + target + ": REACHED ");
            assertThat(lines).filteredOn(line -> line.matches("OrderService\\.find:\\d+ one x3" + on + ".*"))
                    .hasSize(1);
            assertThat(lines).filteredOn(line -> line.matches("OrderService\\.find:\\d+ one x2" + on + ".*"))
                    .hasSize(1);
        }
    }

    @Test
    void ac_ins_14_calls_past_the_bound_and_a_failed_recording_fail_the_check() {
        CallRecorder recorder = CallRecorder.create(1);
        Collection<Long> uncopyable = new AbstractCollection<>() {
            @Override
            public Iterator<Long> iterator() {
                return List.of(1L, 2L).iterator();
            }

            @Override
            public int size() {
                return 2;
            }

            @Override
            public Object[] toArray() {
                throw new IllegalStateException("cannot copy");
            }
        };
        inTransaction(em -> {
            OrderService service = new OrderService(recorder.wrap(OrderEntity.class, executor(em), em));
            service.find(ORDERS, 1L);
            service.find(ORDERS, 2L);
            // The recording fails; the application's call still runs.
            assertThat(service.byKeys(ORDERS, uncopyable)).containsOnlyKeys(1L, 2L);
        });

        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).singleElement().extracting(call -> call.arguments().get(1)).isEqualTo(1L);
        List<String> lines = report(PortabilityCheck.of(classes(), ModelQueryConfig.defaults())
                .targets(TARGETS.toArray(Target[]::new)).replay(calls));
        assertThat(lines.get(0)).isEqualTo("Not portable: 2 of 5 results failed");
        assertThat(lines.subList(1, 4))
                .allMatch(line -> line.matches("OrderService\\.find:\\d+ one on .*: REACHED .*"));
        assertThat(lines.subList(4, 6)).containsExactly(
                "recording: 1 call past the bound of 1 not kept; record with CallRecorder.create(maxCalls) and a "
                        + "larger bound",
                "recording: 1 call failed to record, the first with java.lang.IllegalStateException: cannot copy");
    }

    @Test
    void ac_ins_14_the_transaction_state_is_recorded_and_replayed() {
        CallRecorder recorder = CallRecorder.create();
        try (EntityManager em = factory.createEntityManager()) {
            OrderService service = new OrderService(recorder.wrap(OrderEntity.class, executor(em), em));
            // Outside a transaction the application's own call is refused, rethrown unchanged after recording.
            ModelQueryException refused = catchThrowableOfType(() -> service.rename(HOLD), ModelQueryException.class);
            assertThat(refused.code()).isEqualTo(MqCode.MQ2501);
            em.getTransaction().begin();
            try {
                assertThat(service.rename(HOLD)).isEqualTo(1L);
            } finally {
                em.getTransaction().rollback();
            }
        }
        // Without an EntityManager, a commitEachChunk() write replays outside a transaction and any other in one.
        CallRecorder plain = CallRecorder.create();
        try (EntityManager em = factory.createEntityManager()) {
            OrderService service = new OrderService(plain.wrap(OrderEntity.class, executor(em)));
            catchThrowableOfType(() -> service.rename(HOLD_IN_CHUNKS), ModelQueryException.class);
            catchThrowableOfType(() -> service.rename(HOLD), ModelQueryException.class);
        }

        List<RecordedCall> calls = recorder.calls();
        assertThat(calls).extracting(RecordedCall::inTransaction).containsExactly(TransactionState.OUTSIDE,
                TransactionState.IN);
        assertThat(plain.calls()).extracting(RecordedCall::inTransaction).containsOnly(TransactionState.UNKNOWN);
        List<RecordedCall> all = new ArrayList<>(calls);
        all.addAll(plain.calls());
        // The harness's own ChunkTransactions serves a commitEachChunk() write run outside a transaction.
        List<String> lines = report(check(ModelQueryConfig.defaults().chunkTransactions(new ApplicationChunks()))
                .replay(all));
        // The in-transaction update of each recorder renders one statement: one line, x2.
        assertThat(lines.get(0)).isEqualTo("Not portable: 6 of 12 results failed");
        for (Target target : TARGETS) {
            String on = Pattern.quote(" on " + target + ": ");
            List<String> rename = lines.stream().filter(line -> line.matches("OrderService\\.rename:.*" + on + ".*"))
                    .toList();
            assertThat(rename).hasSize(3);
            assertThat(rename.get(0)).matches("OrderService\\.rename:\\d+ update" + on + "REFUSED MQ2501: .*");
            assertThat(rename.get(1)).matches("OrderService\\.rename:\\d+ update x2" + on + "REACHED .*");
            assertThat(rename.get(2)).matches("OrderService\\.rename:\\d+ update" + on + "REACHED .*");
        }
    }

    @Test
    void ac_ins_14_a_root_outside_the_unit_fails_unless_left_out() {
        CallRecorder recorder = CallRecorder.create();
        inTransaction(em -> {
            OrderService service = new OrderService(recorder.wrap(OrderEntity.class, executor(em), em));
            service.find(ORDERS, 1L);
            ModelQueryExecutor<SampleValueEntity> samples = recorder.wrap(SampleValueEntity.class,
                    ModelQueryExecutor.create(em, SampleValueEntity.class, ModelQueryConfig.defaults()), em);
            OrderService.elsewhere(() -> samples.list(SAMPLES, Limit.of(1)));
        });
        List<RecordedCall> calls = recorder.calls();

        AssertionError outside = catchThrowableOfType(check(ModelQueryConfig.defaults()).replay(calls)::assertPortable,
                AssertionError.class);
        assertThat(outside.getMessage().lines()).containsExactly("Portability check set up wrongly, nothing ran:",
                "replayed root " + SampleValueEntity.class.getName() + " is not an entity of the unit: check its "
                        + "calls with its own unit, or leaveOut(SampleValueEntity.class, reason)");

        List<String> lines = report(check(ModelQueryConfig.defaults()).replay(calls)
                .leaveOut(SampleValueEntity.class, "checked with the samples unit"));
        assertThat(lines.get(0)).isEqualTo("Not portable: 3 of 6 results failed");
        assertThat(lines).noneMatch(line -> line.contains(" list on "))
                .endsWith("SampleValueEntity left out: checked with the samples unit");

        AssertionError managed = catchThrowableOfType(check(ModelQueryConfig.defaults()).replay(calls)
                .leaveOut(SampleValueEntity.class, " ").leaveOut(OrderEntity.class, "kept elsewhere")::assertPortable,
                AssertionError.class);
        // Each setup problem is listed: a blank reason does not hide the managed root.
        assertThat(managed.getMessage().lines()).containsExactly("Portability check set up wrongly, nothing ran:",
                "leaveOut of SampleValueEntity gives no reason",
                "leaveOut of OrderEntity: the unit manages it, so its calls are checked here too");
        managed = catchThrowableOfType(check(ModelQueryConfig.defaults()).replay(calls)
                .leaveOut(SampleValueEntity.class, "samples").leaveOut(OrderEntity.class, "kept elsewhere")
                ::assertPortable, AssertionError.class);
        assertThat(managed.getMessage().lines()).containsExactly("Portability check set up wrongly, nothing ran:",
                "leaveOut of OrderEntity: the unit manages it, so its calls are checked here too");

        AssertionError empty = catchThrowableOfType(check(ModelQueryConfig.defaults())
                .replay(CallRecorder.create().calls())::assertPortable, AssertionError.class);
        assertThat(empty.getMessage()).contains(
                "replay of no recorded calls: record through CallRecorder.wrap(...) before calls()");
    }

    @Test
    void ac_ins_14_an_empty_replay_shows_the_calls_its_recording_dropped_or_failed() {
        CallRecorder bounded = CallRecorder.create(0);
        inTransaction(em -> new OrderService(bounded.wrap(OrderEntity.class, executor(em), em)).find(ORDERS, 1L));
        List<RecordedCall> calls = bounded.calls();
        assertThat(calls).isEmpty();

        AssertionError empty = catchThrowableOfType(PortabilityCheck.of(classes(), ModelQueryConfig.defaults())
                .targets(TARGETS.toArray(Target[]::new)).replay(calls)::assertPortable, AssertionError.class);
        assertThat(empty.getMessage().lines()).containsExactly("Portability check set up wrongly, nothing ran:",
                "replay of no recorded calls: record through CallRecorder.wrap(...) before calls()",
                "no calls: add call(...) or sweep(...)",
                "recording: 1 call past the bound of 0 not kept; record with CallRecorder.create(maxCalls) and a "
                        + "larger bound");
    }

    @Test
    void ac_ins_14_two_snapshots_of_one_recorder_count_its_recording_once_at_the_latest() {
        CallRecorder recorder = CallRecorder.create(1);
        inTransaction(em -> {
            OrderService service = new OrderService(recorder.wrap(OrderEntity.class, executor(em), em));
            service.find(ORDERS, 1L);
            service.find(ORDERS, 2L);
        });
        List<RecordedCall> first = recorder.calls();
        inTransaction(em -> new OrderService(recorder.wrap(OrderEntity.class, executor(em), em)).find(ORDERS, 3L));
        List<RecordedCall> latest = recorder.calls();

        List<String> lines = report(PortabilityCheck.of(classes(), ModelQueryConfig.defaults())
                .targets(TARGETS.toArray(Target[]::new)).replay(latest).replay(first));
        // One count line, the latest snapshot's 2 dropped calls; the kept call's identical lines collapse.
        assertThat(lines.get(0)).isEqualTo("Not portable: 1 of 4 results failed");
        assertThat(lines).filteredOn(line -> line.startsWith("recording: ")).containsExactly(
                "recording: 2 calls past the bound of 1 not kept; record with CallRecorder.create(maxCalls) and a "
                        + "larger bound");
    }

    @Test
    void ac_ins_14_calls_through_a_spring_repository_record_as_its_executor_s_calls_and_replay_on_the_targets() {
        List<RecordedCall> calls;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class,
                    () -> JoinTestSupport.dataSource(TckDatabases.get(TckTarget.h2())));
            context.register(RepositoryJpa.class, ModelQueryAutoConfiguration.class, RecordCalls.class);
            context.refresh();
            @SuppressWarnings("unchecked")
            ModelQueryRepository<OrderEntity> orders = context.getBean("orderRepository", ModelQueryRepository.class);
            OrderController controller = new OrderController(orders);

            assertThat(controller.list(ORDERS, PageRequest.of(0, 2)).getContent()).hasSize(2);
            assertThat(controller.list(ORDERS, PageRequest.of(1, 2, Sort.by(Sort.Order.desc("status"))))
                    .getContent()).hasSize(2);
            assertThat(controller.show(ORDERS, 1L)).isPresent();
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                    .executeWithoutResult(status -> assertThat(controller.recent(ORDERS)).hasSize(3));
            calls = context.getBean(CallRecorder.class).calls();
        }

        // The executor's calls, after SpringPaging translated each Pageable, from the application's caller.
        assertThat(calls).extracting(RecordedCall::label).containsExactly(
                "OrderController.list:28 page", "OrderController.list:28 page", "OrderController.show:33 one",
                "OrderController.recent:38 list");
        assertThat(calls).<Object>extracting(call -> call.method().getDeclaringClass())
                .containsOnly(ModelQueryExecutor.class);
        assertThat(calls.get(0).arguments().subList(1, 3)).containsExactly(PageSpec.of(0, 2), CountMode.COUNT);
        assertThat(calls.get(1).arguments().subList(1, 3)).containsExactly(PageSpec.of(1, 2), CountMode.COUNT);
        assertThat(calls.get(1).arguments().get(0)).isNotEqualTo(calls.get(0).arguments().get(0));
        // The shared EntityManager the decorator was given reads the repository's transaction.
        assertThat(calls).extracting(RecordedCall::inTransaction).containsExactly(TransactionState.OUTSIDE,
                TransactionState.OUTSIDE, TransactionState.OUTSIDE, TransactionState.IN);

        // Replayed on the executor, never the repository: the two pages' equal count statements are one line.
        List<String> lines = report(check(ModelQueryConfig.defaults()).replay(calls));
        assertThat(lines.get(0)).isEqualTo("Not portable: 3 of 18 results failed");
        for (Target target : TARGETS) {
            String on = Pattern.quote(" on " + target + ": REACHED select ");
            assertThat(lines)
                    .anyMatch(line -> line.matches("OrderController\\.list:\\d+ page x2 \\[count]" + on + ".*"))
                    .anyMatch(line -> line.matches("OrderController\\.list:\\d+ page \\[content]" + on
                            + ".* order by 1 .*"))
                    .anyMatch(line -> line.matches("OrderController\\.list:\\d+ page \\[content]" + on
                            + ".* order by 2 desc,1 .*"))
                    .anyMatch(line -> line.matches("OrderController\\.show:\\d+ one" + on + ".*"))
                    .anyMatch(line -> line.matches("OrderController\\.recent:\\d+ list" + on + ".*"));
        }
    }

    @ParameterizedTest
    @ValueSource(classes = { TimeByDelegation.class, TimeByProxy.class })
    void ac_ins_14_an_application_decorator_ordered_outside_the_recorder_is_never_the_call_site(Class<?> timing) {
        List<RecordedCall> calls;
        AtomicInteger timed;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class,
                    () -> JoinTestSupport.dataSource(TckDatabases.get(TckTarget.h2())));
            context.register(RepositoryJpa.class, ModelQueryAutoConfiguration.class, RecordCalls.class, timing);
            context.refresh();
            @SuppressWarnings("unchecked")
            ModelQueryRepository<OrderEntity> orders = context.getBean("orderRepository", ModelQueryRepository.class);
            OrderController controller = new OrderController(orders);

            controller.list(ORDERS, PageRequest.of(0, 2));
            controller.show(ORDERS, 1L);
            controller.recent(ORDERS);
            calls = context.getBean(CallRecorder.class).calls();
            timed = context.getBean("timed", AtomicInteger.class);
        }

        // Each call passed the application's decorator before the recorder, which read past it to the caller.
        assertThat(timed).hasValue(3);
        assertThat(calls).extracting(RecordedCall::label).containsExactly(
                "OrderController.list:28 page", "OrderController.show:33 one", "OrderController.recent:38 list");
    }

    @Test
    void ac_ins_14_a_decorator_delegating_through_a_helper_ordered_outside_makes_the_helper_the_call_site() {
        @SuppressWarnings("unchecked")
        ModelQueryExecutor<OrderEntity> stub = (ModelQueryExecutor<OrderEntity>) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ModelQueryExecutor.class },
                        (proxy, method, args) -> null);
        CallRecorder recorder = CallRecorder.create();
        ModelQueryExecutor<OrderEntity> recording = recorder.wrap(OrderEntity.class, stub);

        // What a metrics decorator ordered outside the recorder does: its frame is skipped as an executor's or the
        // library's, the helper's is the first outside them (documented in R-INS-14).
        Timed.call(() -> recording.list(ORDERS, Limit.of(1)));

        assertThat(recorder.calls()).extracting(RecordedCall::label).singleElement().asString()
                .matches("Timed\\.call:\\d+ list");
    }

    /** A check of the TCK unit on the targets, with a failing call so the report is thrown. */
    private static PortabilityCheck check(ModelQueryConfig config) {
        return PortabilityCheck.of(classes(), config).targets(TARGETS.toArray(Target[]::new))
                .call("empty", OrderEntity.class, executor -> null);
    }

    private static List<String> report(PortabilityCheck check) {
        AssertionError failure = catchThrowableOfType(check::assertPortable, AssertionError.class);
        assertThat(failure).isNotNull();
        return failure.getMessage().lines().toList();
    }

    private static ModelQueryExecutor<OrderEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
    }

    private static void inTransaction(Consumer<EntityManager> work) {
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            try {
                work.accept(em);
            } finally {
                em.getTransaction().rollback();
            }
        }
    }

    private static List<Class<?>> classes() {
        return OfflineBootTest.TCK_UNIT.stream().<Class<?>>map(name -> {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    /** The TCK's entities on H2 and the {@code tck.spr} repositories, which the starter gives the fragment. */
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackages = "com.rey.modelquery.tck.spr")
    static class RepositoryJpa {

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

    /** A test's recorder of every repository's calls, as R-SPR-18 shows it. */
    @Configuration(proxyBeanMethods = false)
    static class RecordCalls {

        @Bean
        CallRecorder recorder() {
            return CallRecorder.create();
        }

        @Bean
        @Order(1)
        ModelQueryExecutorDecorator recording(CallRecorder recorder) {
            return new ModelQueryExecutorDecorator() {
                @Override
                public <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context) {
                    return recorder.wrap(context.rootEntity(), executor, context.entityManager());
                }
            };
        }
    }

    /** The application's timing decorator, an executor delegating to the recorder's, ordered outside it. */
    @Configuration(proxyBeanMethods = false)
    static class TimeByDelegation {

        @Bean
        AtomicInteger timed() {
            return new AtomicInteger();
        }

        @Bean
        @Order(2)
        ModelQueryExecutorDecorator timing(AtomicInteger timed) {
            return new ModelQueryExecutorDecorator() {
                @Override
                public <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context) {
                    return new TimingExecutor<>(executor, timed);
                }
            };
        }
    }

    /** The application's timing decorator, a JDK proxy over the recorder's executor, ordered outside it. */
    @Configuration(proxyBeanMethods = false)
    static class TimeByProxy {

        @Bean
        AtomicInteger timed() {
            return new AtomicInteger();
        }

        @Bean
        @Order(2)
        ModelQueryExecutorDecorator timing(AtomicInteger timed) {
            return new ModelQueryExecutorDecorator() {
                @Override
                public <E> ModelQueryExecutor<E> decorate(ModelQueryExecutor<E> executor, ExecutorContext<E> context) {
                    return TimingHandler.wrap(executor, timed);
                }
            };
        }
    }

    /** The application's callback, which serves its own factory only; the check replaces it with the harness's. */
    private static final class ApplicationChunks implements ChunkTransactions {
        @Override
        public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
            throw new AssertionError("the application's callback ran");
        }

        @Override
        public void checkServes(EntityManagerFactory emf) {
            throw new IllegalStateException("serves the application's factory only");
        }
    }
}
