package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.rey.modelquery.audit.test.QAuditOrderView;
import com.rey.modelquery.core.Row;
import org.junit.jupiter.api.Test;

class AcInsSuperclassTest {
    static class Parent { public String getName() { return "name"; } }
    static class Child extends Parent {}
    static class ExceptionChild extends Parent {}
    static class TimeoutChild extends Parent {}

    @Test
    void ac_ins_19_unloaded_base_is_hooked_when_loaded_through_model() throws Exception {
        String prefix = "org.example.audit.Unloaded";
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(prefix)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = input.readAllBytes();
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
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = java.util.logging.Logger.getLogger(AuditObserver.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            Class<?> type = loader.loadClass(prefix + "Model");
            Object model = type.getConstructor().newInstance();
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL).build();
            Row row = mock(Row.class);
            when(row.isSelected(any())).thenReturn(true);
            new AuditObserver(() -> new CallSiteCapture.Site("Caller.load:1", "one"))
                    .handedOff(query, Optional.empty(), List.of(model), List.of(row));
            assertThat(ReadTransformer.hooked(type)).isTrue();
            assertThat(records).noneMatch(record -> record.getMessage().contains("MODELS NOT OBSERVED"));
            assertThat(type.getMethod("getId").invoke(model)).isEqualTo(1L);
            assertThat(AuditObserver.tagOf(model).wasRead("id")).isTrue();
        } finally {
            logger.removeHandler(handler);
        }
    }

    @SuppressWarnings("unchecked")
    private static ClassValue<AtomicBoolean> status() throws Exception {
        Field field = ReadTransformer.class.getDeclaredField("HOOKED");
        field.setAccessible(true);
        return (ClassValue<AtomicBoolean>) field.get(null);
    }

    private static void hook(Instrumentation instrumentation, ClassLoader loader) throws Exception {
        hook(instrumentation, Child.class, loader, 10_000);
    }

    private static void hook(Instrumentation instrumentation, Class<?> model, ClassLoader loader, long timeout)
            throws Exception {
        Class<?> description = Class.forName(
                "com.rey.modelquery.audit.shaded.bytebuddy.description.type.TypeDescription");
        Class<?> loaded = Class.forName(description.getName() + "$ForLoadedType");
        Method method = ReadTransformer.class.getDeclaredMethod("hookSuperclasses",
                Instrumentation.class, description, ClassLoader.class, long.class);
        method.setAccessible(true);
        method.invoke(null, instrumentation, loaded.getConstructor(Class.class).newInstance(model), loader, timeout);
        ReadTransformer.transformed(model.getName(), loader, true);
    }

    @Test
    void ac_ins_19_parent_loaded_superclass_is_retransformed() throws Exception {
        Instrumentation instrumentation = mock(Instrumentation.class);
        when(instrumentation.getInitiatedClasses(any())).thenReturn(new Class<?>[0]);
        when(instrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[] {Parent.class});
        when(instrumentation.isModifiableClass(Parent.class)).thenReturn(true);
        status().get(Parent.class).set(false);
        hook(instrumentation, new ClassLoader(Parent.class.getClassLoader()) {});
        verify(instrumentation).retransformClasses(Parent.class);
    }

    @Test
    void ac_ins_19_unmodifiable_superclass_marks_model_unhooked() throws Exception {
        Instrumentation instrumentation = mock(Instrumentation.class);
        when(instrumentation.getInitiatedClasses(any())).thenReturn(new Class<?>[0]);
        when(instrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[] {Parent.class});
        status().get(Parent.class).set(false);
        status().get(Child.class).set(true);
        hook(instrumentation, Child.class.getClassLoader());
        assertThat(ReadTransformer.hooked(Child.class)).isFalse();
    }

    @Test
    void ac_ins_19_failed_superclass_retransform_is_reported_at_handoff() throws Exception {
        Instrumentation instrumentation = instrumentation();
        doThrow(new java.lang.instrument.UnmodifiableClassException("test"))
                .when(instrumentation).retransformClasses(Parent.class);
        status().get(ExceptionChild.class).set(true);
        hook(instrumentation, ExceptionChild.class, ExceptionChild.class.getClassLoader(), 1_000);
        assertMissingHookReported(new ExceptionChild());
    }

    @Test
    void ac_ins_19_timed_out_superclass_retransform_is_reported_at_handoff() throws Exception {
        Instrumentation instrumentation = instrumentation();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release retransform");
                }
            } finally {
                finished.countDown();
            }
            return null;
        }).when(instrumentation).retransformClasses(Parent.class);
        status().get(TimeoutChild.class).set(true);
        try {
            hook(instrumentation, TimeoutChild.class, TimeoutChild.class.getClassLoader(), 100);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertMissingHookReported(new TimeoutChild());
        } finally {
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Instrumentation instrumentation() throws Exception {
        Instrumentation instrumentation = mock(Instrumentation.class);
        when(instrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[] {Parent.class});
        when(instrumentation.isModifiableClass(Parent.class)).thenReturn(true);
        status().get(Parent.class).set(false);
        return instrumentation;
    }

    private static void assertMissingHookReported(Object model) {
        assertThat(ReadTransformer.hooked(model.getClass())).isFalse();
        var records = new java.util.ArrayList<java.util.logging.LogRecord>();
        var logger = java.util.logging.Logger.getLogger(AuditObserver.class.getName());
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            var query = QAuditOrderView.query().select(QAuditOrderView.ALL).build();
            new AuditObserver(() -> new CallSiteCapture.Site("Caller.load:1", "one"))
                    .handedOff(query, Optional.empty(), List.of(model), List.of(mock(Row.class)));
            assertThat(records).filteredOn(record -> record.getMessage().contains("MODELS NOT OBSERVED"))
                    .singleElement().satisfies(record -> assertThat(record.getMessage())
                            .contains("hook missing", model.getClass().getName()));
        } finally {
            logger.removeHandler(handler);
        }
    }
}
