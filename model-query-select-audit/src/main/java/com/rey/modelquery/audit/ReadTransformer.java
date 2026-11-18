package com.rey.modelquery.audit;

import static net.bytebuddy.matcher.ElementMatchers.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Installs load-time, retransformation-capable hooks without retransformation of loaded models (D-232). */
final class ReadTransformer {
    private static final String ANNOTATIONS = "com.rey.modelquery.annotations.";
    private static final Map<ClassLoader, Map<String, Boolean>> LOADS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ClassLoader, Set<String>> SUPERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ClassLoader, Set<String>> FAILED_SUPERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ClassValue<AtomicBoolean> HOOKED = new ClassValue<>() {
        @Override
        protected AtomicBoolean computeValue(Class<?> type) {
            synchronized (LOADS) {
                return new AtomicBoolean(LOADS.getOrDefault(type.getClassLoader(), Map.of())
                        .getOrDefault(type.getName(), false));
            }
        }
    };

    private static final String CORE = "com.rey.modelquery.core.";
    private static final String EXECUTOR = "com.rey.modelquery.jpa.DefaultModelQueryExecutor";
    private static final String VALUE_ROW = CORE + "RowSelection$ValueRow";
    /** The engine seams (D-241): seam name to the type that declares it. */
    private static final Map<String, String> SEAMS = Map.of(
            "ModelQuery.toModel(Row)", CORE + "ModelQuery",
            "DefaultModelQueryExecutor.run(FetchPlan,String,List)", EXECUTOR,
            "Row.get(SelectField)", VALUE_ROW,
            "Row.raw(SelectField)", VALUE_ROW,
            "Row.alias(SelectField)", VALUE_ROW);
    private static final Set<String> SEAM_TYPES_SEEN = ConcurrentHashMap.newKeySet();
    private static final Set<String> SEAMS_HOOKED = ConcurrentHashMap.newKeySet();

    private ReadTransformer() {}

    /** The live set of seam types seen, for a test that drives the failure path. */
    static Set<String> seamTypesSeen() {
        return SEAM_TYPES_SEEN;
    }

    /** The live set of seams hooked, for a test that drives the failure path. */
    static Set<String> seamsHooked() {
        return SEAMS_HOOKED;
    }

    /** The seams whose type loaded but whose method matched no hook, or {@code null} when every loaded one is hooked. */
    static String unappliedSeams() {
        List<String> missing = new ArrayList<>();
        SEAMS.forEach((seam, type) -> {
            if (SEAM_TYPES_SEEN.contains(type) && !SEAMS_HOOKED.contains(seam)) {
                missing.add(seam);
            }
        });
        return missing.isEmpty() ? null : String.join(", ", missing.stream().sorted().toList());
    }

    private static ElementMatcher<TypeDescription> seamType(ElementMatcher<TypeDescription> matcher) {
        return type -> {
            boolean matches = matcher.matches(type);
            if (matches && SEAMS.containsValue(type.getName())) {
                SEAM_TYPES_SEEN.add(type.getName());
            }
            return matches;
        };
    }

    private static ElementMatcher.Junction<MethodDescription> seam(String name,
            ElementMatcher<MethodDescription> matcher) {
        return new ElementMatcher.Junction.AbstractBase<>() {
            @Override
            public boolean matches(MethodDescription method) {
                boolean matches = matcher.matches(method);
                if (matches && method.getDeclaringType().asErasure().getName().equals(SEAMS.get(name))) {
                    SEAMS_HOOKED.add(name);
                }
                return matches;
            }
        };
    }

    static boolean hooked(Class<?> type) {
        return HOOKED.get(type).get();
    }

    private static void status(String name, ClassLoader loader, boolean loaded, boolean value) {
        synchronized (LOADS) {
            LOADS.computeIfAbsent(loader, ignored -> new ConcurrentHashMap<>()).put(name, value);
        }
        if (loaded) {
            try {
                HOOKED.get(Class.forName(name, false, loader)).set(value);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // The load may have failed before the class could be resolved.
            }
        }
    }

    static void install(Instrumentation inst) {
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit())
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module,
                            boolean loaded, DynamicType dynamicType) {
                        transformed(type.getName(), loader, loaded);
                    }

                    @Override
                    public void onIgnored(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded) {
                        if (loaded && known(type.getName(), loader)) {
                            status(type.getName(), loader, true, false);
                        }
                    }

                    @Override
                    public void onError(String name, ClassLoader loader, JavaModule module, boolean loaded,
                            Throwable error) {
                        if (loaded && known(name, loader)) {
                            status(name, loader, true, false);
                        }
                        System.getLogger(ReadTransformer.class.getName()).log(System.Logger.Level.WARNING,
                                "Select-audit hook failed for " + name, error);
                    }
                })
                .ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("com.rey.modelquery.audit.shaded."))
                        .or(nameStartsWith("java.")).or(nameStartsWith("jdk.")).or(nameStartsWith("sun."))
                        .or(nameStartsWith("org.jacoco.")).or(nameStartsWith("org.mockito.internal.")))
                .type((type, loader, module, redefining, domain) -> pending(type.getName(), loader)
                        || (model(type) && (redefining == null || known(type.getName(), loader))))
                .transform((builder, type, loader, module, domain) -> {
                    if (model(type)) {
                        hookSuperclasses(inst, type, loader);
                    }
                    ElementMatcher<MethodDescription> accessors = accessors(type);
                    return builder.visit(Advice.withCustomMapping().bind(new PropertyMapping(type.isRecord()))
                                    .to(AccessorAdvice.class).on(accessors))
                            .visit(Advice.to(ObjectAdvice.class).on(objectMethods()))
                            .visit(new AsmVisitorWrapper.ForDeclaredMethods().writerFlags(ClassWriter.COMPUTE_MAXS)
                                    .method(ownMethods(type, accessors), (instrumented, method, visitor, context,
                                            pool, writerFlags, readerFlags) -> new Fields(visitor, type)));
                })
                .type(seamType(named(CORE + "ModelQuery")))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(MappingAdvice.class).on(seam("ModelQuery.toModel(Row)",
                                named("toModel").and(takesArguments(1))))))
                .type(seamType(named(EXECUTOR)))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(ObjectAdvice.class).on(seam("DefaultModelQueryExecutor.run(FetchPlan,String,List)",
                                named("run").and(takesArguments(3))))))
                .type(seamType(hasSuperType(named(CORE + "Row")).and(not(isInterface()))))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(RowAdvice.class).on(
                                seam("Row.get(SelectField)", named("get").and(takesArguments(1)))
                                        .or(seam("Row.raw(SelectField)", named("raw").and(takesArguments(1))))))
                        .visit(Advice.to(AliasAdvice.class).on(seam("Row.alias(SelectField)", named("alias")
                                .and(takesArguments(1)).and(returns(String.class))))))
                .installOn(inst);
    }

    private static boolean known(String name, ClassLoader loader) {
        synchronized (LOADS) {
            return LOADS.getOrDefault(loader, Map.of()).containsKey(name);
        }
    }

    private static boolean pending(String name, ClassLoader loader) {
        synchronized (SUPERS) {
            return SUPERS.getOrDefault(loader, Set.of()).contains(name);
        }
    }

    private static void hookSuperclasses(Instrumentation inst, TypeDescription type, ClassLoader loader) {
        hookSuperclasses(inst, type, loader, 10_000);
    }

    private static void hookSuperclasses(Instrumentation inst, TypeDescription type, ClassLoader loader,
            long timeoutMillis) {
        boolean success = true;
        for (TypeDescription.Generic parent = type.getSuperClass(); parent != null;
                parent = parent.asErasure().getSuperClass()) {
            TypeDescription base = parent.asErasure();
            String name = base.getName();
            if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")) {
                break;
            }
            if (model(base)) {
                continue;
            }
            Class<?> loaded = null;
            for (ClassLoader current = loader; current != null && loaded == null; current = current.getParent()) {
                for (Class<?> candidate : inst.getAllLoadedClasses()) {
                    if (candidate.getName().equals(name) && candidate.getClassLoader() == current) {
                        loaded = candidate;
                        break;
                    }
                }
            }
            synchronized (SUPERS) {
                for (ClassLoader current = loader; current != null; current = current.getParent()) {
                    SUPERS.computeIfAbsent(current, ignored -> ConcurrentHashMap.newKeySet()).add(name);
                }
            }
            if (loaded != null && !hooked(loaded) && !inst.isModifiableClass(loaded)) {
                success = false;
            } else if (loaded != null && !hooked(loaded)) {
                Class<?> target = loaded;
                AtomicBoolean transformed = new AtomicBoolean(true);
                Thread worker = new Thread(() -> {
                    try {
                        inst.retransformClasses(target);
                        transformed.set(hooked(target));
                    } catch (Throwable error) {
                        transformed.set(false);
                        System.getLogger(ReadTransformer.class.getName()).log(System.Logger.Level.WARNING,
                                "Select-audit superclass hook failed for " + target.getName(), error);
                    }
                }, "model-query-select-audit-superclass");
                worker.setDaemon(true);
                worker.start();
                try {
                    worker.join(timeoutMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    transformed.set(false);
                }
                success &= transformed.get() && !worker.isAlive();
            }
        }
        synchronized (FAILED_SUPERS) {
            Set<String> failures = FAILED_SUPERS.computeIfAbsent(loader, ignored -> ConcurrentHashMap.newKeySet());
            if (success) {
                failures.remove(type.getName());
            } else {
                failures.add(type.getName());
                status(type.getName(), loader, false, false);
            }
        }
    }

    static void transformed(String name, ClassLoader loader, boolean loaded) {
        synchronized (FAILED_SUPERS) {
            status(name, loader, loaded, !FAILED_SUPERS.getOrDefault(loader, Set.of()).contains(name));
        }
    }

    private static boolean model(TypeDescription type) {
        try {
            return !type.isInterface() && !type.isAnnotation()
                    && (carries(type.getDeclaredAnnotations())
                    || type.getRecordComponents().stream().anyMatch(c -> carries(c.getDeclaredAnnotations()))
                    || type.getDeclaredFields().stream().anyMatch(f -> carries(f.getDeclaredAnnotations()))
                    || type.getDeclaredMethods().stream().anyMatch(m -> carries(m.getDeclaredAnnotations())));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean carries(AnnotationList annotations) {
        return annotations.stream().anyMatch(annotation -> {
            String name = annotation.getAnnotationType().getName();
            return name.startsWith(ANNOTATIONS) && !name.equals(ANNOTATIONS + "Incubating")
                    && !name.equals(ANNOTATIONS + "EngineFacing");
        });
    }

    private static ElementMatcher<MethodDescription> accessors(TypeDescription type) {
        Set<String> names = new HashSet<>();
        type.getRecordComponents().forEach(component -> names.add(component.getActualName()));
        Set<String> selectedFields = selectedFields(type);
        boolean record = type.isRecord();
        return isMethod().<MethodDescription>and(isPublic()).and(not(isStatic())).and(not(isAbstract()))
                .and(takesArguments(0)).and(not(returns(void.class)))
                .and(method -> !selectedFields.contains(record ? method.getInternalName()
                        : propertyName(method.getInternalName())) && !selectedFields.contains(method.getInternalName()))
                .and(method -> record ? names.contains(method.getInternalName())
                        : method.getInternalName().startsWith("get") || method.getInternalName().startsWith("is"));
    }

    private static Set<String> selectedFields(TypeDescription type) {
        Set<String> selected = new HashSet<>();
        type.getDeclaredFields().forEach(field -> {
            if (field.getDeclaredAnnotations().isAnnotationPresent(
                    new TypeDescription.ForLoadedType(com.rey.modelquery.annotations.Selected.class))) {
                selected.add(field.getName());
            }
        });
        return selected;
    }

    private static String propertyName(String method) {
        int prefix = method.startsWith("get") ? 3 : method.startsWith("is") ? 2 : 0;
        return prefix > 0 && method.length() > prefix && Character.isUpperCase(method.charAt(prefix))
                ? Character.toLowerCase(method.charAt(prefix)) + method.substring(prefix + 1) : method;
    }

    private static ElementMatcher<MethodDescription> objectMethods() {
        return named("equals").and(takesArguments(Object.class)).or(named("hashCode").and(takesArguments(0)))
                .or(named("toString").and(takesArguments(0)));
    }

    private static ElementMatcher<MethodDescription> ownMethods(TypeDescription type,
            ElementMatcher<MethodDescription> accessors) {
        return isMethod().<MethodDescription>and(not(isStatic())).and(not(accessors)).and(not(objectMethods()))
                .and(method -> !method.getReturnType().asErasure().equals(type));
    }

    private static final class Fields extends MethodVisitor {
        private final String owner;
        private final Set<String> fields = new HashSet<>();

        Fields(MethodVisitor visitor, TypeDescription type) {
            super(Opcodes.ASM9, visitor);
            owner = type.getInternalName();
            Set<String> selected = selectedFields(type);
            type.getDeclaredFields().forEach(field -> {
                if (!selected.contains(field.getName())) {
                    fields.add(field.getName());
                }
            });
        }

        @Override
        public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
            if (opcode == Opcodes.GETFIELD && fieldOwner.equals(owner) && fields.contains(name)) {
                super.visitInsn(Opcodes.DUP);
                super.visitLdcInsn(name);
                super.visitLdcInsn(owner.replace('/', '.'));
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/rey/modelquery/audit/ReadAccess", "field",
                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false);
            }
            super.visitFieldInsn(opcode, fieldOwner, name, descriptor);
        }
    }

    static final class AccessorAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.This Object self, @Property String property, @Advice.Origin("#m") String method,
                @Advice.Origin Class<?> owner) {
            ReadAccess.accessor(self, property, method, owner);
        }
    }

    /** The property an accessor reads, computed when the class is transformed and injected as a constant. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Property {}

    private record PropertyMapping(boolean record) implements Advice.OffsetMapping.Factory<Property> {
        @Override
        public Class<Property> getAnnotationType() {
            return Property.class;
        }

        @Override
        public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                AnnotationDescription.Loadable<Property> annotation, AdviceType adviceType) {
            return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
                String name = instrumentedMethod.getInternalName();
                return Advice.OffsetMapping.Target.ForStackManipulation.of(record ? name : propertyName(name));
            };
        }
    }

    static final class ObjectAdvice {
        @Advice.OnMethodEnter
        static void enter() {
            ReadAccess.enterObjectMethod();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit() {
            ReadAccess.exitObjectMethod();
        }
    }

    static final class MappingAdvice {
        @Advice.OnMethodEnter
        static Object enter(@Advice.Argument(0) Object row) {
            ReadAccess.enterObjectMethod();
            return ReadAccess.enterMapping(row);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Enter Object previous) {
            ReadAccess.exitMapping(previous);
            ReadAccess.exitObjectMethod();
        }
    }

    static final class RowAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Object column) {
            ReadAccess.rowRead(column);
        }
    }

    static final class AliasAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.Return String alias) {
            ReadAccess.rowAlias(alias);
        }
    }
}
