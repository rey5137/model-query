package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rey.modelquery.audit.test.AuditCustomerView;
import com.rey.modelquery.audit.test.AuditLineView;
import com.rey.modelquery.audit.test.AuditOrderEntity;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.audit.test.ProjectionAuditCustomerView;
import com.rey.modelquery.audit.test.QAuditLineView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import org.example.audit.AuditCaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-INS-22: The report lists unread selected columns per call site and select set, excludes engine-added keys and
 * portability replays, and two forks' files merge to the union (R-INS-21).
 */
class AcIns22Test extends AuditTestBase {

    @Test
    void ac_ins_22_unread_columns_reported_per_call_site_and_select_set_and_child() {
        ReadEvents.clear();
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);

            FetchPlan<AuditOrderView> plan = FetchPlan.of(QAuditOrderView.ALL)
                    .child(QAuditOrderView.LINES, FetchPlan.of(QAuditLineView.ALL));
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .fetch(plan)
                    .build();

            AuditOrderView order = AuditCaller.executorCall(() -> executor.one(query)).orElseThrow();
            assertThat(order.id()).isEqualTo(1L);
            for (AuditLineView line : order.lines()) {
                assertThat(line.id()).isNotNull();
            }

            String report = SelectAuditReport.render();
            assertThat(report).contains("UNREAD COLUMNS (warning)");
            assertThat(report).contains(
                    "one, select ALL: AuditOrderView.total, AuditOrderView.notes\n"
                            + "    never read in 1 call across 1 test");
            assertThat(report).contains("child lines select ALL: AuditLineView.orderId, AuditLineView.sku");
        }
    }

    @Test
    void ac_ins_22_different_select_sets_at_same_call_site_are_tracked_separately() {
        ReadEvents.clear();
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);

            ModelQuery<AuditOrderEntity, Long, AuditOrderView> narrow = QAuditOrderView.query()
                    .select(SelectSet.of(QAuditOrderView.ID, QAuditOrderView.TOTAL))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> wide = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            for (ModelQuery<AuditOrderEntity, Long, AuditOrderView> q : List.of(narrow, wide)) {
                AuditOrderView order = AuditCaller.executorCall(() -> executor.one(q)).orElseThrow();
                assertThat(order.id()).isEqualTo(1L);
                if (q == narrow) {
                    assertThat(order.total()).isEqualTo(100L);
                }
            }

            String report = SelectAuditReport.render();
            assertThat(report).contains("one, select ALL: AuditOrderView.total, AuditOrderView.notes");
            assertThat(report).doesNotContain("select [AuditOrderView.id, AuditOrderView.total]:");
        }
    }

    @Test
    void ac_ins_22_engine_added_keys_and_portability_replays_are_excluded_from_unread() throws Exception {
        ReadEvents.clear();
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);

            // Select only TOTAL, but fetch plan loads LINES so the engine adds the primary key ID to the parent row,
            // and the primary key ID to the child row for ordering. Reading TOTAL and child's SKU and ORDER_ID leaves
            // no requested column unread; the engine-added IDs must not be listed as unread.
            FetchPlan<AuditOrderView> plan = FetchPlan.of(SelectSet.of(QAuditOrderView.TOTAL))
                    .child(QAuditOrderView.LINES, FetchPlan.of(SelectSet.of(QAuditLineView.SKU)));
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .fetch(plan)
                    .build();

            AuditOrderView order = AuditCaller.executorCall(() -> executor.one(query)).orElseThrow();
            assertThat(order.total()).isEqualTo(100L);
            for (AuditLineView line : order.lines()) {
                assertThat(line.sku()).isNotBlank();
                assertThat(line.orderId()).isEqualTo(1L);
            }

            // Simulate a PortabilityCheck replay on the stack: must not be tagged or reported.
            runInPortabilityFrame(
                    () -> executor.list(QAuditOrderView.query().select(QAuditOrderView.ALL).build(), Limit.of(1)));

            String report = SelectAuditReport.render();
            assertThat(report).doesNotContain("UNREAD COLUMNS");
        }
    }

    public static final class ReplayDelegate {
        public static Object run(Supplier<?> supplier) {
            return supplier.get();
        }
    }

    private static void runInPortabilityFrame(Supplier<?> supplier) throws Exception {
        int modifiers = java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC;
        Class<?> replayClass = new ByteBuddy()
                .subclass(Object.class)
                .name("com.rey.modelquery.portability.SimulatedPortabilityReplay")
                .defineMethod("run", Object.class, modifiers)
                .withParameters(Supplier.class)
                .intercept(MethodDelegation.to(ReplayDelegate.class))
                .make()
                .load(AcIns22Test.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
        Method run = replayClass.getMethod("run", Supplier.class);
        run.invoke(null, supplier);
    }

    @Test
    void ac_ins_22_nested_join_unread_columns_reported_and_read_columns_cleared() {
        ReadEvents.clear();
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(SelectSet.of(QAuditOrderView.ID).with(QAuditOrderView.CUSTOMER))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            AuditOrderView order = AuditCaller.executorCall(() -> executor.one(query)).orElseThrow();
            assertThat(order.id()).isEqualTo(1L);
            AuditCustomerView customer = order.customer().orElseThrow();
            assertThat(customer.id()).isEqualTo(1L);

            String report = SelectAuditReport.render();
            assertThat(report).contains(": AuditCustomerView.name");
            assertThat(report).doesNotContain("AuditCustomerView.id");
            assertThat(report).doesNotContain(": AuditOrderView.id");
        }
    }

    @Test
    void ac_ins_22_after_map_row_read_prevents_unread_warning() {
        ReadEvents.clear();
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(SelectSet.of(QAuditOrderView.ID, QAuditOrderView.TOTAL))
                    .afterMap((model, row) -> row.get(QAuditOrderView.TOTAL))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            AuditOrderView order = AuditCaller.executorCall(() -> executor.one(query)).orElseThrow();
            assertThat(order.id()).isEqualTo(1L);

            assertThat(SelectAuditReport.render()).doesNotContain("UNREAD COLUMNS");
        }
    }

    @Test
    void ac_ins_22_report_includes_unselected_serializer_inherited_and_missing_hook_sections() throws Exception {
        ReadEvents.clear();
        Field hookedField = ReadTransformer.class.getDeclaredField("HOOKED");
        hookedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ClassValue<AtomicBoolean> status = (ClassValue<AtomicBoolean>) hookedField.get(null);

        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(SelectSet.of(QAuditOrderView.ID))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            AuditOrderView order = AuditCaller.executorCall(() -> executor.one(query)).orElseThrow();
            assertThat(order.id()).isEqualTo(1L);

            // 1. Direct unselected read on same thread (twice -> x2)
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> readNotes(order)).isInstanceOf(UnselectedColumnRead.class);
            }
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> allWithoutChild = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();
            AuditOrderView allOrder = AuditCaller.executorCall(() -> executor.one(allWithoutChild)).orElseThrow();
            assertThatThrownBy(allOrder::lines).isInstanceOf(UnselectedColumnRead.class)
                    .hasMessageContaining(", select ALL");

            // 2. Unselected read on another thread -> labelled (may not have failed a test)
            CountDownLatch latch = new CountDownLatch(1);
            var pool = Executors.newSingleThreadExecutor();
            try {
                pool.execute(() -> {
                    try {
                        order.total();
                    } catch (UnselectedColumnRead ignored) {
                        // expected
                    } finally {
                        latch.countDown();
                    }
                });
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            // 3. Serializer read of unselected column -> warning
            ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
            AcIns21Test.Bean bean = new AcIns21Test.Bean();
            Tag beanTag = new Tag("OrderController.list:40", "page", null, "DEFAULT",
                    AcIns21Test.Bean.class.getName(), java.util.BitSet.valueOf(new long[] {1L}),
                    java.util.Map.of("id", 0, "missing", 1), java.util.Set.of("id", "missing"), java.util.Map.of(),
                    List.of("id"));
            Field tagsField = AuditObserver.class.getDeclaredField("TAGS");
            tagsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            WeakIdentityMap<Object, Tag> tags = (WeakIdentityMap<Object, Tag>) tagsField.get(null);
            tags.put(bean, beanTag);
            mapper.writeValueAsString(bean);

            // 4. Unmapped inherited field read
            AcIns19Test.Projection proj = new AcIns19Test.Projection();
            Tag projTag = new Tag("OrderService.search:142", "list", null, "ALL",
                    AcIns19Test.Projection.class.getName(), java.util.BitSet.valueOf(new long[] {1L}),
                    java.util.Map.of("name", 0), java.util.Set.of("name"), java.util.Map.of(), List.of("name"));
            tags.put(proj, projTag);
            proj.getInherited();

            // 5. Missing hook
            status.get(AuditOrderView.class).set(false);
            try {
                AuditCaller.executorCall(() -> executor.one(query));
            } finally {
                status.get(AuditOrderView.class).set(true);
                AuditObserver.clearMissingHooks();
            }

            String report = SelectAuditReport.render();
            List<String> lines = report.lines().toList();
            assertThat(lines).contains("UNSELECTED READS (3)");
            assertThat(lines).anyMatch(line -> line.startsWith("  AuditOrderView.notes  read at AcIns22Test.")
                    && line.endsWith(" one x2") && !line.contains("(may not have failed a test)"));
            assertThat(lines).anyMatch(line -> line.startsWith("  AuditOrderView.total  read at AcIns22Test.")
                    && line.contains("(may not have failed a test)  model of ") && line.endsWith(" one"));
            assertThat(lines).contains("SERIALIZER READS OF UNSELECTED COLUMNS (warning)",
                    "  Bean.missing  read by Jackson  model of OrderController.list:40 page");
            assertThat(lines).contains("READS OF UNMAPPED INHERITED FIELDS (warning)");
            assertThat(lines).anyMatch(line -> line.startsWith(
                    "  Projection.inherited (AcIns19Test$Base.getInherited)  read at AcIns22Test.")
                    && line.endsWith("  model of OrderService.search:142 list"));
            assertThat(lines).contains("MODELS NOT OBSERVED (hook missing)");
            assertThat(lines).anyMatch(line -> line.startsWith("  AuditOrderView  model of AuditCaller.executorCall:")
                    && line.endsWith(" one: loaded before the agent; reads not recorded"));
        }
    }

    @Test
    void ac_ins_22_two_forks_files_merge_to_union_and_session_close_writes_fork_file(@TempDir Path dir)
            throws Exception {
        ReadEvents.clear();
        long pid = ProcessHandle.current().pid();
        String previousDir = System.getProperty(SelectAuditReport.DIR_PROPERTY);
        String previousFork = System.getProperty("surefire.forkNumber");
        try (EntityManager em = sessions.createEntityManager()) {
            System.setProperty(SelectAuditReport.DIR_PROPERTY, dir.toString());
            ModelQueryExecutor<AuditOrderEntity> executor = orderExecutor(em);
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query = QAuditOrderView.query()
                    .select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L))
                    .build();

            // Fork 1 reads ID only; TOTAL and NOTES are unread in fork 1.
            System.setProperty("surefire.forkNumber", "1");
            AuditOrderView fork1Order = queryAtSameCallSite(executor, query);
            assertThat(fork1Order.id()).isEqualTo(1L);
            new AuditLauncherSessionListener().launcherSessionClosed(null);

            Path fork1File = dir.resolve("select-audit-fork-1-" + pid + ".txt");
            assertThat(fork1File).exists();
            assertThat(Files.readString(fork1File))
                    .contains("AuditOrderView.total, AuditOrderView.notes");

            // Fork 2 reads TOTAL only; ID and NOTES are unread in fork 2.
            ReadEvents.clear();
            System.setProperty("surefire.forkNumber", "2");
            AuditOrderView fork2Order = queryAtSameCallSiteFromSecondTest(executor, query);
            assertThat(fork2Order.total()).isEqualTo(100L);
            assertThatThrownBy(() -> {
                ModelQuery<AuditOrderEntity, Long, AuditOrderView> partial = QAuditOrderView.query()
                        .select(SelectSet.of(QAuditOrderView.ID))
                        .where(f -> f.eq(QAuditOrderView.ID, 1L))
                        .build();
                AuditOrderView partialOrder = AuditCaller.executorCall(() -> executor.one(partial)).orElseThrow();
                assertThat(partialOrder.id()).isEqualTo(1L);
                readNotes(partialOrder);
            }).isInstanceOf(UnselectedColumnRead.class);
            new AuditLauncherSessionListener().launcherSessionClosed(null);

            Path fork2File = dir.resolve("select-audit-fork-2-" + pid + ".txt");
            assertThat(fork2File).exists();
            assertThat(Files.readString(fork2File))
                    .contains("AuditOrderView.id, AuditOrderView.notes");

            // Merge both fork files: ID and TOTAL were read across the two forks, so only NOTES remains unread.
            SelectAuditReport.main(dir.toString());
            Path mergedFile = dir.resolve("select-audit.txt");
            assertThat(mergedFile).exists();
            String merged = Files.readString(mergedFile);
            assertThat(merged).contains("UNREAD COLUMNS (warning)");
            assertThat(merged).contains(
                    "one, select ALL: AuditOrderView.notes\n"
                            + "    never read in 2 calls across 2 tests");
            assertThat(merged).doesNotContain("AuditOrderView.id");
            assertThat(merged).doesNotContain("AuditOrderView.total");
            assertThat(merged).contains("UNSELECTED READS (1)");
            assertThat(merged).doesNotContain("# CALL_SITE");
        } finally {
            if (previousDir == null) {
                System.clearProperty(SelectAuditReport.DIR_PROPERTY);
            } else {
                System.setProperty(SelectAuditReport.DIR_PROPERTY, previousDir);
            }
            if (previousFork == null) {
                System.clearProperty("surefire.forkNumber");
            } else {
                System.setProperty("surefire.forkNumber", previousFork);
            }
        }
    }

    @Test
    void ac_ins_22_same_simple_model_names_in_different_packages_are_tracked_separately() {
        ReadEvents.clear();
        Tag tagA = new Tag("OrderService.load:10", "one", null, "ALL", "com.example.a.OrderView",
                java.util.BitSet.valueOf(new long[] {3L}), java.util.Map.of("id", 0, "notes", 1),
                java.util.Set.of("id", "notes"), java.util.Map.of(), List.of("id", "notes"));
        Tag tagB = new Tag("OrderService.load:10", "one", null, "ALL", "com.example.b.OrderView",
                java.util.BitSet.valueOf(new long[] {3L}), java.util.Map.of("id", 0, "notes", 1),
                java.util.Set.of("id", "notes"), java.util.Map.of(), List.of("id", "notes"));
        ReadEvents.recordCall(tagA, "com.example.OrderTest#testA");
        ReadEvents.recordCall(tagB, "com.example.OrderTest#testB");
        tagA.read("id");
        tagA.read("notes");
        tagB.read("id");

        String report = SelectAuditReport.render();
        assertThat(report).contains("OrderService.load:10 one, select ALL: OrderView.notes\n"
                + "    never read in 1 call across 1 test");
    }

    @Test
    void ac_ins_22_report_write_failure_logs_warning_without_failing_session(@TempDir Path dir) throws Exception {
        Path fileAsDir = Files.writeString(dir.resolve("not-a-directory"), "x");
        String previousDir = System.getProperty(SelectAuditReport.DIR_PROPERTY);
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = java.util.logging.Logger.getLogger(AuditLauncherSessionListener.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            System.setProperty(SelectAuditReport.DIR_PROPERTY, fileAsDir.toString());
            new AuditLauncherSessionListener().launcherSessionClosed(null);
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING);
                assertThat(record.getMessage()).contains("Failed to write select-audit report");
                assertThat(record.getThrown()).isNotNull();
            });
        } finally {
            logger.removeHandler(handler);
            if (previousDir == null) {
                System.clearProperty(SelectAuditReport.DIR_PROPERTY);
            } else {
                System.setProperty(SelectAuditReport.DIR_PROPERTY, previousDir);
            }
        }
    }

    private static AuditOrderView queryAtSameCallSiteFromSecondTest(
            ModelQueryExecutor<AuditOrderEntity> executor,
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query) throws Exception {
        var ref = new java.util.concurrent.atomic.AtomicReference<AuditOrderView>();
        Thread thread = new Thread(() -> ref.set(queryAtSameCallSite(executor, query)));
        thread.start();
        thread.join(5000);
        return ref.get();
    }

    private static AuditOrderView queryAtSameCallSite(
            ModelQueryExecutor<AuditOrderEntity> executor,
            ModelQuery<AuditOrderEntity, Long, AuditOrderView> query) {
        return AuditCaller.executorCall(() -> executor.one(query)).orElseThrow();
    }

    private static String readNotes(AuditOrderView order) {
        return order.notes();
    }
}
