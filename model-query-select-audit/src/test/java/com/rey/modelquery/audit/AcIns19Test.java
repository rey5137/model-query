package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.Selected;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.audit.test.ProjectionAuditCustomerView;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectSet;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/** AC-INS-19: Reads by the mapper and engine are distinct from reads made by application code. */
class AcIns19Test extends AuditTestBase {
    private static final String IDENTITY_MODEL = "org.example.audit.HookIdentityModel";

    private static final class ModelLoader extends ClassLoader {
        private final boolean strip;

        ModelLoader(boolean strip) {
            super(AcIns19Test.class.getClassLoader());
            this.strip = strip;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(IDENTITY_MODEL)) {
                return super.loadClass(name, resolve);
            }
            Class<?> existing = findLoadedClass(name);
            if (existing != null) {
                return existing;
            }
            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                byte[] bytes = input.readAllBytes();
                if (strip) {
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public FieldVisitor visitField(int access, String field, String descriptor, String signature,
                                Object value) {
                            return new FieldVisitor(Opcodes.ASM9,
                                    super.visitField(access, field, descriptor, signature, value)) {
                                @Override
                                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                                    return null;
                                }
                            };
                        }
                    }, 0);
                    bytes = writer.toByteArray();
                }
                Class<?> defined = defineClass(name, bytes, 0, bytes.length);
                if (resolve) {
                    resolveClass(defined);
                }
                return defined;
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    @Test
    void ac_ins_19_hook_status_is_per_class_not_per_name() throws Exception {
        Class<?> hooked = new ModelLoader(false).loadClass(IDENTITY_MODEL);
        Class<?> unhooked = new ModelLoader(true).loadClass(IDENTITY_MODEL);
        assertThat(hooked.getName()).isEqualTo(unhooked.getName());
        assertThat(ReadTransformer.hooked(hooked)).isTrue();
        assertThat(ReadTransformer.hooked(unhooked)).isFalse();
    }

    static class Base {
        public String getInherited() {
            return "base";
        }
    }

    static final class Projection extends Base {
        @Column
        private final String name;
        @Selected
        private final SelectSet<Projection> selected = SelectSet.of();

        Projection() {
            name = "name";
        }

        public String getName() {
            return name;
        }

        public String computed() {
            return name;
        }

        public SelectSet<Projection> getSelected() {
            return selected;
        }

        public int selectedSize() {
            return selected.fields().size();
        }

        public Projection withName() {
            return this;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Projection projection && getName().equals(projection.getName());
        }

        @Override
        public int hashCode() {
            return getName().hashCode();
        }
    }

    @SuppressWarnings("unchecked")
    private static void tag(Object model, Tag tag) throws ReflectiveOperationException {
        Field field = AuditObserver.class.getDeclaredField("TAGS");
        field.setAccessible(true);
        ((WeakIdentityMap<Object, Tag>) field.get(null)).put(model, tag);
    }

    @Test
    void ac_ins_19_inherited_getters_and_object_methods_survive_retransformation() throws Exception {
        new Base();
        Projection model = new Projection();
        BitSet selected = new BitSet();
        selected.set(0);
        Tag tag = new Tag("Site.read:1", "list", null, selected,
                Map.of("name", 0, "inherited", 1, "selected", 2), Set.of("name", "selected"));
        tag(model, tag);

        assertThat(ReadTransformer.hooked(Projection.class)).isTrue();
        assertThat(ReadTransformer.hooked(Base.class)).isTrue();
        model.equals(new Projection());
        model.hashCode();
        model.withName();
        model.getSelected();
        model.selectedSize();
        assertThat(tag.wasRead("name")).isFalse();
        assertThat(tag.wasRead("selected")).isFalse();
        assertThat(model.getInherited()).isEqualTo("base");
        assertThat(tag.inheritedReads()).containsExactly(Base.class.getName() + ".inherited");

        AuditAgent.instrumentation().retransformClasses(Projection.class);
        assertThat(ReadTransformer.hooked(Projection.class)).isTrue();
        assertThat(model.computed()).isEqualTo("name");
        assertThat(tag.wasRead("name")).isTrue();
    }

    @Test
    void ac_ins_19_accessors_and_model_methods_record_reads_but_withers_do_not() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            Tag tag = AuditObserver.tagOf(model);
            assertThat(ReadTransformer.hooked(model.getClass())).isTrue();
            assertThat(tag.wasRead("id")).isFalse();
            assertThat(tag.wasRead("notes")).isFalse();
            model.withNotes("other");
            assertThat(tag.wasRead("notes")).isFalse();
            assertThat(model.computedNotes()).isEqualTo("First order");
            assertThat(tag.wasRead("notes")).isTrue();
            model.id();
            assertThat(tag.wasRead("id")).isTrue();
        }
    }

    @Test
    void ac_ins_19_after_map_row_reads_count_but_generated_mapper_reads_do_not() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query()
                    .select(SelectSet.of(QAuditOrderView.ID, QAuditOrderView.TOTAL))
                    .afterMap((model, row) -> row.get(QAuditOrderView.TOTAL))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            Tag tag = AuditObserver.tagOf(model);
            assertThat(tag.wasRead("total")).isTrue();
            assertThat(tag.wasRead("id")).isFalse();
        }
    }

    @Test
    void ac_ins_19_after_map_raw_and_finisher_row_reads_count() {
        try (EntityManager em = sessions.createEntityManager()) {
            AtomicReference<Row> row = new AtomicReference<>();
            var query = QAuditOrderView.query()
                    .select(SelectSet.of(QAuditOrderView.ID, QAuditOrderView.TOTAL, QAuditOrderView.NOTES))
                    .afterMap((model, mappedRow) -> {
                        row.set(mappedRow);
                        mappedRow.raw(QAuditOrderView.TOTAL);
                    })
                    .finisher(model -> {
                        row.get().get(QAuditOrderView.NOTES);
                        return model;
                    })
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            Tag tag = AuditObserver.tagOf(model);
            assertThat(tag.wasRead("total")).isTrue();
            assertThat(tag.wasRead("notes")).isTrue();
            assertThat(tag.wasRead("id")).isFalse();
        }
    }

    @Test
    void ac_ins_19_mapping_and_enrichers_do_not_count_reads_on_already_tagged_models() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL)
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var previous = orderExecutor(em).one(query).orElseThrow();
            Tag tag = AuditObserver.tagOf(previous);
            var plan = FetchPlan.of(QAuditOrderView.ALL).enrich(Enricher.of(page -> {
                previous.id();
                return page;
            }));
            var next = QAuditOrderView.query().select(QAuditOrderView.ALL)
                    .afterMap((model, row) -> previous.notes())
                    .fetch(plan).where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            orderExecutor(em).one(next).orElseThrow();
            assertThat(tag.wasRead("id")).isFalse();
            assertThat(tag.wasRead("notes")).isFalse();
            previous.id();
            assertThat(tag.wasRead("id")).isTrue();
        }
    }

    @Test
    void ac_ins_19_untagged_instances_are_not_recorded() {
        Projection model = new Projection();
        assertThat(model.computed()).isEqualTo("name");
        assertThat(AuditObserver.tagOf(model)).isNull();
    }

    @Test
    void ac_ins_19_a_lost_hook_is_reported_at_handoff() throws Exception {
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = java.util.logging.Logger.getLogger(AuditObserver.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Field field = ReadTransformer.class.getDeclaredField("HOOKED");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ClassValue<AtomicBoolean> status = (ClassValue<AtomicBoolean>) field.get(null);
        logger.addHandler(handler);
        try (EntityManager em = sessions.createEntityManager()) {
            AtomicReference<Row> row = new AtomicReference<>();
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL)
                    .afterMap((model, mapped) -> row.set(mapped))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var model = orderExecutor(em).one(query).orElseThrow();
            status.get(model.getClass()).set(false);
            try {
                new AuditObserver(() -> new CallSiteCapture.Site("Caller.load:1", "one"))
                        .handedOff(query, Optional.empty(), List.of(model), List.of(row.get()));
                new AuditObserver(() -> new CallSiteCapture.Site("Caller.load:2", "one"))
                        .handedOff(query, Optional.empty(), List.of(model), List.of(row.get()));
                assertThat(records).filteredOn(record -> record.getMessage().contains("MODELS NOT OBSERVED"))
                        .singleElement().satisfies(record -> assertThat(record.getMessage())
                                .contains("hook missing", model.getClass().getName()));
            } finally {
                status.get(model.getClass()).set(true);
            }
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void ac_ins_19_scoped_join_row_read_counts_on_nested_model() {
        try (EntityManager em = sessions.createEntityManager()) {
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL.with(QAuditOrderView.CUSTOMER))
                    .afterMap((model, row) -> row.scoped(QAuditOrderView.CUSTOMER_TABLE)
                            .get(ProjectionAuditCustomerView.NAME))
                    .where(f -> f.eq(QAuditOrderView.ID, 1L)).build();
            var order = orderExecutor(em).one(query).orElseThrow();
            var customer = order.customer().orElseThrow();
            assertThat(AuditObserver.tagOf(customer).wasRead("name")).isTrue();
            assertThat(AuditObserver.tagOf(customer).wasRead("id")).isFalse();
        }
    }
}
