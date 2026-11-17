package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.audit.test.ProjectionAuditCustomerView;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.audit.test.AuditOrderView;
import com.rey.modelquery.core.JoinScope;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.EntityManager;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.example.audit.QuoteService;
import org.junit.jupiter.api.Test;

class AuditRegressionTest extends AuditTestBase {
    public record WarningManualView(Long total) {}

    @Test
    void ac_ins_20_selection_uses_actual_row_keys_in_both_orders() {
        var query = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.TOTAL)).build();
        var props = ModelProperties.of(query, AuditOrderView.class);
        for (boolean first : new boolean[] {true, false}) {
            for (boolean id : new boolean[] {first, !first}) {
                BitSet bits = props.selectionBitSet(query, new SelectionRow(id));
                assertThat(bits.get(props.propertyBits().get("id"))).isEqualTo(id);
            }
        }
    }

    @Test
    void ac_ins_20_join_presence_uses_actual_row_keys_in_both_orders() {
        var query = QAuditOrderView.query().select(QAuditOrderView.ALL.with(QAuditOrderView.CUSTOMER)).build();
        var props = ModelProperties.of(query, AuditOrderView.class);
        for (boolean first : new boolean[] {true, false}) {
            for (boolean present : new boolean[] {first, !first}) {
                BitSet bits = props.selectionBitSet(query, new SelectionRow(present));
                assertThat(bits.get(props.propertyBits().get("customer"))).isEqualTo(present);
            }
        }
    }

    @Test
    void ac_ins_20_selected_snapshot_cannot_mutate_shared_tag() {
        BitSet selected = new BitSet();
        selected.set(0);
        Tag tag = new Tag("Caller.load:1", "list", null, selected, Map.of("id", 0));
        tag.selected().clear();
        assertThat(tag.isSelected("id")).isTrue();
    }

    @Test
    void ac_ins_23_quote_service_is_an_application_frame() {
        try (EntityManager em = sessions.createEntityManager()) {
            var executor = orderExecutor(em);
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL).build();
            var model = QuoteService.findOpen(() -> executor.first(query)).orElseThrow();
            assertThat(AuditObserver.tagOf(model).callSite()).startsWith("QuoteService.findOpen:");
        }
    }

    @Test
    void ac_ins_23_stream_rows_share_one_tag_and_next_call_has_another() {
        try (EntityManager em = sessions.createEntityManager()) {
            var executor = orderExecutor(em);
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL).build();
            List<AuditOrderView> models = QuoteService.findOpen(() ->
                    executor.stream(query, Limit.unlimited(), stream -> stream.toList()));
            assertThat(models).hasSize(3);
            Tag tag = AuditObserver.tagOf(models.get(0));
            assertThat(tag).isNotNull();
            for (var model : models) {
                assertThat(AuditObserver.tagOf(model)).isSameAs(tag);
            }
            var next = QuoteService.findOpen(() -> executor.first(query)).orElseThrow();
            assertThat(AuditObserver.tagOf(next)).isNotSameAs(tag);
        }
    }

    @Test
    void ac_ins_20_two_q_classes_for_one_model_have_independent_metadata() {
        var first = org.example.audit.FirstProjection.query();
        var second = org.example.audit.SecondProjection.query();
        var firstProps = ModelProperties.of(first, org.example.audit.ManualView.class);
        var secondProps = ModelProperties.of(second, org.example.audit.ManualView.class);
        Row row = new Row() {
            @Override public <C> C get(SelectField<?, C> field) { return null; }
            @Override public Object raw(SelectField<?, ?> field) { return null; }
            @Override public boolean isSelected(SelectField<?, ?> field) {
                return field.equals(org.example.audit.FirstProjection.VALUE);
            }
            @Override public Row scoped(TableField<?, ?> table) { return this; }
            @Override public Row scoped(JoinScope scope) { return this; }
        };
        assertThat(firstProps.selectionBitSet(first, row).get(firstProps.propertyBits().get("total"))).isTrue();
        assertThat(secondProps.selectionBitSet(second, row).get(secondProps.propertyBits().get("total"))).isFalse();
    }

    @Test
    void ac_ins_20_hand_written_mapper_warns_once_per_class_and_is_not_tagged() {
        var logs = new java.util.ArrayList<java.util.logging.LogRecord>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(ModelProperties.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { logs.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            var query = ModelQuery.builder(QAuditOrderView.ROOT,
                    row -> new WarningManualView(12L))
                    .select(SelectSet.of(QAuditOrderView.TOTAL.withTable(WarningManualView.class, QAuditOrderView.ROOT)
                            .named("total"))).build();
            var model = new WarningManualView(12L);
            var observer = new AuditObserver();
            for (int i = 0; i < 2; i++) {
                QuoteService.findOpen(() -> {
                    observer.handedOff(query, java.util.Optional.empty(), List.of(model),
                            List.of(new SelectionRow(false)));
                    return null;
                });
            }
            var otherQuery = ModelQuery.builder(QAuditOrderView.ROOT, row -> new OtherManualView(13L))
                    .select(SelectSet.of(QAuditOrderView.TOTAL.withTable(OtherManualView.class, QAuditOrderView.ROOT)
                            .named("total"))).build();
            var other = new OtherManualView(13L);
            for (int i = 0; i < 2; i++) {
                QuoteService.findOpen(() -> {
                    observer.handedOff(otherQuery, java.util.Optional.empty(), List.of(other),
                            List.of(new SelectionRow(false)));
                    return null;
                });
            }
            assertThat(AuditObserver.tagOf(model)).isNull();
            assertThat(AuditObserver.tagOf(other)).isNull();
            assertThat(logs).hasSize(2).allSatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING);
            });
            assertThat(logs.get(1).getMessage()).contains(OtherManualView.class.getName());
            assertThat(logs.get(0)).satisfies(record -> {
                assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING);
                assertThat(record.getMessage()).contains(WarningManualView.class.getName());
            });
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void ac_ins_20_hand_written_mapper_of_annotated_model_is_not_audited() {
        var query = ModelQuery.builder(QAuditOrderView.ROOT,
                row -> QAuditOrderView.MAPPER.map(row)).select(QAuditOrderView.ALL).build();
        var logs = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = java.util.logging.Logger.getLogger(ModelProperties.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { logs.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try (EntityManager em = sessions.createEntityManager()) {
            var executor = orderExecutor(em);
            for (int i = 0; i < 2; i++) {
                var models = executor.list(query, Limit.unlimited());
                assertThat(models).hasSize(3).allSatisfy(model -> assertThat(AuditObserver.tagOf(model)).isNull());
            }
            assertThat(logs).singleElement().satisfies(record -> {
                assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING);
                assertThat(record.getMessage()).contains(AuditOrderView.class.getName());
            });
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void ac_ins_20_hand_written_verdict_does_not_read_model_class_files_again() throws Exception {
        assertHandWrittenClassFileReads(true);
    }

    @Test
    void ac_ins_20_hand_written_model_with_unreadable_class_file_is_not_audited() throws Exception {
        assertHandWrittenClassFileReads(false);
    }

    private void assertHandWrittenClassFileReads(boolean readable) throws Exception {
        byte[] bytes;
        try (var resource = org.example.audit.ManualView.class.getResourceAsStream("ManualView.class")) {
            bytes = resource.readAllBytes();
        }
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> defineModel() { return defineClass(null, bytes, 0, bytes.length); }
            @Override public java.io.InputStream getResourceAsStream(String name) {
                if (name.equals("org/example/audit/ManualView.class")) {
                    reads.incrementAndGet();
                    return readable ? new java.io.ByteArrayInputStream(bytes) : null;
                }
                return super.getResourceAsStream(name);
            }
        };
        Class<?> modelClass = loader.defineModel();
        Object model = modelClass.getConstructor(Long.class).newInstance(12L);
        var query = ModelQuery.builder(QAuditOrderView.ROOT, row -> model)
                .select(SelectSet.of(QAuditOrderView.TOTAL.withTable(Object.class, QAuditOrderView.ROOT)
                        .named("total"))).build();
        var observer = new AuditObserver();
        QuoteService.findOpen(() -> {
            try (var scope = observer.openCall()) {
                for (int i = 0; i < 10; i++) {
                    observer.handedOff(query, java.util.Optional.empty(), List.of(model),
                            List.of(new SelectionRow(false)));
                }
            }
            return null;
        });
        assertThat(AuditObserver.tagOf(model)).isNull();
        assertThat(reads.get()).as("mapper verdict never needs the model class file").isZero();
    }

    @Test
    void ac_ins_20_metadata_caches_allow_application_classes_to_be_collected() throws Exception {
        var references = cacheIsolatedModels();
        for (int attempt = 0; attempt < 100 && references.stream().anyMatch(ref -> ref.get() != null); attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(references).allSatisfy(ref -> assertThat(ref.get()).as("application class or loader").isNull());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<java.lang.ref.WeakReference<?>> cacheIsolatedModels() throws Exception {
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.rey.modelquery.audit.test.")
                        && !name.equals("org.example.audit.ManualView")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (var resource = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = resource.readAllBytes();
                            type = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
        };
        Class<?> host = loader.loadClass(QAuditOrderView.class.getName());
        Class<?> model = loader.loadClass(AuditOrderView.class.getName());
        var builder = (ModelQuery.Builder) host.getMethod("query").invoke(null);
        var query = builder.select((SelectSet) host.getField("ALL").get(null)).build();
        var properties = ModelProperties.of(query, model);
        assertThat(properties).isNotNull();
        properties.nested("customer", loader.loadClass("com.rey.modelquery.audit.test.AuditCustomerView"));
        Class<?> manual = loader.loadClass("org.example.audit.ManualView");
        var manualQuery = ModelQuery.builder(QAuditOrderView.ROOT, row -> (Object) null)
                .select(SelectSet.of(QAuditOrderView.TOTAL.withTable(Object.class, QAuditOrderView.ROOT)
                        .named("total"))).build();
        assertThat(ModelProperties.of(manualQuery, manual)).isNull();
        return List.of(new java.lang.ref.WeakReference<>(loader), new java.lang.ref.WeakReference<>(host),
                new java.lang.ref.WeakReference<>(model), new java.lang.ref.WeakReference<>(manual));
    }

    @Test
    void ac_ins_23_parallel_stream_carries_the_outer_call_tag_and_site() {
        try (EntityManager em = sessions.createEntityManager()) {
            var executor = orderExecutor(em);
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL).build();
            var models = QuoteService.findOpen(() -> executor.stream(query, Limit.unlimited(), stream ->
                    onWorker(() -> stream.parallel().toList())));
            assertThat(models).hasSize(3);
            Tag tag = AuditObserver.tagOf(models.get(0));
            assertThat(tag).isNotNull();
            assertThat(tag.callSite()).contains("QuoteService.findOpen:");
            assertThat(tag.method()).isEqualTo("stream");
            models.forEach(model -> assertThat(AuditObserver.tagOf(model)).isSameAs(tag));
        }
    }

    @Test
    void ac_ins_20_same_scope_different_actual_selection_gets_a_new_tag() {
        try (EntityManager em = sessions.createEntityManager();
                EntityManager nestedEm = sessions.createEntityManager()) {
            var executor = orderExecutor(em);
            var nestedExecutor = orderExecutor(nestedEm);
            var query = QAuditOrderView.query().select(SelectSet.of(QAuditOrderView.TOTAL)).build();
            QuoteService.findOpen(() -> executor.stream(query, Limit.unlimited(), stream -> {
                stream.forEach(model -> {
                    Tag outer = AuditObserver.tagOf(model);
                    assertThat(outer.isSelected("id")).isTrue();
                    // Both executor phases add ID today; supply the observer a row with different engine keys.
                    var withoutId = new AuditOrderView(null, 12L, null, java.util.Optional.empty(), List.of());
                    new AuditObserver().handedOff(query, java.util.Optional.empty(), List.of(withoutId),
                            List.of(new SelectionRow(false)));
                    Tag different = AuditObserver.tagOf(withoutId);
                    assertThat(different.isSelected("id")).isFalse();
                    var nested = nestedExecutor.byKeys(query, List.of(1L));
                    Tag inner = AuditObserver.tagOf(nested.get(1L));
                    assertThat(inner.isSelected("id")).isTrue();
                    assertThat(inner).isNotSameAs(different);
                    assertThat(inner.callSite()).isEqualTo(outer.callSite());
                });
                return null;
            }));
        }
    }

    private static <T> T onWorker(java.util.concurrent.Callable<T> work) {
        var pool = new java.util.concurrent.ForkJoinPool(2);
        try {
            return pool.submit(work).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ac_ins_23_export_pages_share_one_tag_and_callback_failure_releases_scope() {
        try (EntityManager em = sessions.createEntityManager()) {
            var executor = orderExecutor(em);
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL)
                    .orderBy(QAuditOrderView.ID.asc()).build();
            var models = new java.util.ArrayList<AuditOrderView>();
            QuoteService.findOpen(() -> executor.export(query, com.rey.modelquery.core.ExportOptions.of(1),
                    java.util.function.Function.identity(), models::add));
            assertThat(models).hasSize(3);
            Tag tag = AuditObserver.tagOf(models.get(0));
            assertThat(tag).isNotNull();
            models.forEach(model -> assertThat(AuditObserver.tagOf(model)).isSameAs(tag));
            RuntimeException failure = new RuntimeException("body failed");
            var seen = new java.util.ArrayList<Tag>();
            assertThatThrownBy(() -> QuoteService.findOpen(() -> executor.stream(query, Limit.unlimited(), stream -> {
                stream.forEach(model -> {
                    seen.add(AuditObserver.tagOf(model));
                    throw failure;
                });
                return null;
            }))).isSameAs(failure);
            var next = QuoteService.findOpen(() -> executor.first(query)).orElseThrow();
            assertThat(AuditObserver.tagOf(next)).isNotSameAs(seen.get(0));
        }
    }

    @Test
    void ac_ins_23_one_stack_capture_per_outer_scope_including_nested_calls() {
        var captures = new java.util.concurrent.atomic.AtomicInteger();
        var observer = new AuditObserver(() -> {
            captures.incrementAndGet();
            return new CallSiteCapture.Site("Caller.load:1", "stream");
        });
        var query = QAuditOrderView.query().select(QAuditOrderView.ALL).build();
        var first = new AuditOrderView(1L, 12L, null, java.util.Optional.empty(), List.of());
        var second = new AuditOrderView(2L, 12L, null, java.util.Optional.empty(), List.of());
        try (var outer = observer.openCall()) {
            observer.handedOff(query, java.util.Optional.empty(), List.of(first), List.of(new SelectionRow(false)));
            try (var inner = observer.openCall()) {
                observer.handedOff(query, java.util.Optional.empty(), List.of(second),
                        List.of(new SelectionRow(false)));
            }
            assertThat(captures.get()).isEqualTo(1);
            assertThat(AuditObserver.tagOf(second)).isSameAs(AuditObserver.tagOf(first));
        }
        try (var next = observer.openCall()) {
            observer.handedOff(query, java.util.Optional.empty(), List.of(second),
                        List.of(new SelectionRow(false)));
        }
        assertThat(captures.get()).isEqualTo(2);
        assertThat(AuditObserver.tagOf(second)).isNotSameAs(AuditObserver.tagOf(first));
    }

    private record OtherManualView(Long total) {}

    private record SelectionRow(boolean id) implements Row {
        @Override public <C> C get(SelectField<?, C> field) { return null; }
        @Override public Object raw(SelectField<?, ?> field) { return null; }
        @Override public boolean isSelected(SelectField<?, ?> field) {
            return field.equals(QAuditOrderView.TOTAL)
                    || id && (field.equals(QAuditOrderView.ID) || field.equals(ProjectionAuditCustomerView.ID));
        }
        @Override public Row scoped(TableField<?, ?> table) { return this; }
        @Override public Row scoped(JoinScope scope) { return this; }
    }
}
