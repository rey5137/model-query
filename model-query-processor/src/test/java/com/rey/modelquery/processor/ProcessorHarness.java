package com.rey.modelquery.processor;

import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.TableField;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/** What the processor's tests share: compiling sources with the processor, and looking at what it did. */
final class ProcessorHarness {

    private ProcessorHarness() {}

    /** Lombok's processor, which its jar hides from the classpath: loaded by name, as {@code javac} discovers it. */
    static Processor lombok() {
        try {
            return (Processor) Class.forName("lombok.launch.AnnotationProcessorHider$AnnotationProcessor")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static JavaFileObject source(String qualifiedName, String text) {
        return JavaFileObjects.forSourceString(qualifiedName, text);
    }

    /** Compiles {@code sources} with the processor alone; the test classpath is the compile classpath. */
    static Compilation compile(JavaFileObject... sources) {
        return javac().withProcessors(new ModelQueryProcessor()).compile(sources);
    }

    /**
     * Compiles {@code sources} with the processor over the test classpath less each entry whose path contains
     * {@code excluded}, as a model module without that dependency would.
     */
    static Compilation compileWithout(String excluded, JavaFileObject... sources) {
        List<File> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains(excluded))
                .map(File::new)
                .toList();
        return javac().withProcessors(new ModelQueryProcessor()).withClasspath(classpath).compile(sources);
    }

    /** The error messages of {@code compilation}, in the order reported. */
    static List<String> errors(Compilation compilation) {
        return compilation.errors().stream().map(error -> error.getMessage(Locale.ROOT)).toList();
    }

    /** The generated source of {@code qualifiedName}, exactly as written. */
    static String generated(Compilation compilation, String qualifiedName) {
        JavaFileObject file = compilation.generatedSourceFile(qualifiedName)
                .orElseThrow(() -> new AssertionError("no generated source " + qualifiedName));
        try {
            return file.getCharContent(false).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The generated source with every run of whitespace as one space, so line wrapping does not matter. */
    static String generatedFlat(Compilation compilation, String qualifiedName) {
        return generated(compilation, qualifiedName).replaceAll("\\s+", " ");
    }

    static String resource(String path) {
        try (InputStream in = ProcessorHarness.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("no test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads the classes {@code compilation} produced, models and QModels alike, over the test classpath. */
    static ClassLoader classes(Compilation compilation) {
        var bytes = new HashMap<String, byte[]>();
        for (JavaFileObject file : compilation.generatedFiles()) {
            if (file.getKind() == JavaFileObject.Kind.CLASS) {
                String path = file.toUri().getPath();
                String name = path.substring(path.indexOf("/", 1) + 1, path.length() - ".class".length());
                try (InputStream in = file.openInputStream()) {
                    bytes.put(name.replace('/', '.'), in.readAllBytes());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return new ClassLoader(ProcessorHarness.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] found = bytes.get(name);
                if (found == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, found, 0, found.length);
            }
        };
    }

    /** A hand-built row: the columns it maps are the selected ones, a null value included. */
    static Row row(Map<SelectField<?, ?>, Object> values) {
        return new Row() {
            @Override
            @SuppressWarnings("unchecked")
            public <C> C get(SelectField<?, C> column) {
                return (C) values.get(column);
            }

            @Override
            public Object raw(SelectField<?, ?> column) {
                return values.get(column);
            }

            @Override
            public boolean isSelected(SelectField<?, ?> column) {
                return values.containsKey(column);
            }

            @Override
            public Row scoped(TableField<?, ?> join) {
                throw new UnsupportedOperationException("a flat model has no join to scope");
            }
        };
    }

    /**
     * Runs {@link ModelQueryProcessor} and keeps what it told the compiler that a {@link Compilation} does not show:
     * the element of each message, and the originating elements of each file.
     */
    static final class Recording implements Processor {

        /** One {@code Messager} call. */
        record Message(Diagnostic.Kind kind, String text, Element element) {}

        private final ModelQueryProcessor delegate = new ModelQueryProcessor();
        private final List<Message> messages = new ArrayList<>();
        private final Map<String, List<Element>> originating = new LinkedHashMap<>();

        List<Message> messages() {
            return messages;
        }

        /** Each generated source's qualified name with the originating elements it was created with. */
        Map<String, List<Element>> originating() {
            return originating;
        }

        Compilation compile(JavaFileObject... sources) {
            return javac().withProcessors(this).compile(sources);
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }

        @Override
        public void init(ProcessingEnvironment env) {
            delegate.init(new ProcessingEnvironment() {
                @Override
                public Map<String, String> getOptions() {
                    return env.getOptions();
                }

                @Override
                public Messager getMessager() {
                    return recordingMessager(env.getMessager());
                }

                @Override
                public Filer getFiler() {
                    return recordingFiler(env.getFiler());
                }

                @Override
                public Elements getElementUtils() {
                    return env.getElementUtils();
                }

                @Override
                public Types getTypeUtils() {
                    return env.getTypeUtils();
                }

                @Override
                public SourceVersion getSourceVersion() {
                    return env.getSourceVersion();
                }

                @Override
                public Locale getLocale() {
                    return env.getLocale();
                }
            });
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return delegate.process(annotations, roundEnv);
        }

        @Override
        public Iterable<? extends Completion> getCompletions(
                Element element, AnnotationMirror annotation, ExecutableElement member, String userText) {
            return delegate.getCompletions(element, annotation, member, userText);
        }

        private Messager recordingMessager(Messager messager) {
            return new Messager() {
                @Override
                public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                    messages.add(new Message(kind, msg.toString(), null));
                    messager.printMessage(kind, msg);
                }

                @Override
                public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
                    messages.add(new Message(kind, msg.toString(), e));
                    messager.printMessage(kind, msg, e);
                }

                @Override
                public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
                    messages.add(new Message(kind, msg.toString(), e));
                    messager.printMessage(kind, msg, e, a);
                }

                @Override
                public void printMessage(
                        Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
                    messages.add(new Message(kind, msg.toString(), e));
                    messager.printMessage(kind, msg, e, a, v);
                }
            };
        }

        private Filer recordingFiler(Filer filer) {
            return new Filer() {
                @Override
                public JavaFileObject createSourceFile(CharSequence name, Element... elements) throws IOException {
                    originating.put(name.toString(), List.of(elements));
                    return filer.createSourceFile(name, elements);
                }

                @Override
                public JavaFileObject createClassFile(CharSequence name, Element... elements) throws IOException {
                    return filer.createClassFile(name, elements);
                }

                @Override
                public FileObject createResource(
                        JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName,
                        Element... elements) throws IOException {
                    return filer.createResource(location, pkg, relativeName, elements);
                }

                @Override
                public FileObject getResource(
                        JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName)
                        throws IOException {
                    return filer.getResource(location, pkg, relativeName);
                }
            };
        }
    }
}
