package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.squareup.javapoet.TypeName;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A model compiled in another module reads through the {@code Element} API as it does from source: its fields in
 * declaration order, their types, and every {@code CLASS}-retained model-query annotation with its values, on a record
 * component and its field alike (D-140, the M24 spike for R-PROC-30). A type-use annotation such as JSpecify's
 * {@code @Nullable} is not read from a class file before JDK 22 (JDK-8225377); the processor reads none from a model,
 * and types are compared as JavaPoet names them, without one.
 */
class ClasspathModelTest {

    private static final JavaFileObject CUSTOMER_REF = source("api.CustomerRef", """
            package api;

            import com.rey.modelquery.annotations.FilterColumn;
            import com.rey.modelquery.annotations.QueryModel;

            @QueryModel(root = Object.class, prefix = "X")
            @FilterColumn(name = "city", path = "address.city")
            public record CustomerRef(Long id, String name) {}
            """);

    private static final JavaFileObject ORDER_RECORD = source("api.OrderRecord", """
            package api;

            import com.rey.modelquery.annotations.Aggregate;
            import com.rey.modelquery.annotations.AggregateFunction;
            import com.rey.modelquery.annotations.Child;
            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Computed;
            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.JoinKind;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.Selected;
            import com.rey.modelquery.annotations.Transient;
            import java.math.BigDecimal;
            import java.util.List;
            import java.util.Optional;
            import org.jspecify.annotations.Nullable;

            public record OrderRecord(
                    @PrimaryKey Long id,
                    @Column(attribute = "total", converter = String.class) BigDecimal amount,
                    @Nullable String note,
                    @Selected int lineCount,
                    @Computed(String.class) String label,
                    @Aggregate(fn = AggregateFunction.SUM, attribute = "lines.qty", distinct = true) long quantity,
                    @Join(attribute = "customer", type = JoinKind.INNER, prefix = "c_") Optional<CustomerRef> buyer,
                    @Child(key = {"id"}, foreignKey = {"orderId", "tenant"}) List<CustomerRef> contacts,
                    @Transient boolean flagged) {

                public static final String CONSTANT = "not a field of the model";
            }
            """);

    private static final JavaFileObject ORDER_CLASS = source("api.OrderClass", """
            package api;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.Computed;
            import com.rey.modelquery.annotations.ExcludeFromInserts;
            import com.rey.modelquery.annotations.GroupBy;
            import com.rey.modelquery.annotations.PrimaryKey;
            import java.math.BigDecimal;
            import org.jspecify.annotations.Nullable;

            public class OrderClass {
                @PrimaryKey private Long id;
                @Column(attribute = "total") @GroupBy private BigDecimal amount;
                private @Nullable String note;
                @Computed(String.class) @ExcludeFromInserts private String label;

                public OrderClass() {}

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public BigDecimal getAmount() { return amount; }
                public void setAmount(BigDecimal amount) { this.amount = amount; }
                public @Nullable String getNote() { return note; }
                public void setNote(@Nullable String note) { this.note = note; }
                public String getLabel() { return label; }
                public void setLabel(String label) { this.label = label; }
            }
            """);

    /** The persistence module's source: refers to the models, which are on its classpath only. */
    private static final JavaFileObject BINDING = source("persistence.OrderBinding", """
            package persistence;

            final class OrderBinding {
                static final Class<?>[] MODELS = {api.OrderRecord.class, api.OrderClass.class, api.CustomerRef.class};
            }
            """);

    private static final List<String> MODELS = List.of("api.OrderRecord", "api.OrderClass", "api.CustomerRef");

    @TempDir
    Path classes;

    @Test
    void d140_classpath_models_read_as_their_sources() {
        Map<String, List<String>> fromSource = probe(List.of(), CUSTOMER_REF, ORDER_RECORD, ORDER_CLASS, BINDING);

        Compilation api = javac().withOptions("-proc:none").compile(CUSTOMER_REF, ORDER_RECORD, ORDER_CLASS);
        assertThat(api).succeeded();
        write(api);
        Map<String, List<String>> fromClassFile = probe(List.of(classes.toFile()), BINDING);

        assertThat(fromClassFile).isEqualTo(fromSource);
        // The facts the comparison relies on are there, not merely equal: a value left at its default is not
        // written, on either side, and a record component's annotation is on its field.
        assertThat(fromClassFile.get("api.OrderRecord")).contains(
                "field id java.lang.Long [@com.rey.modelquery.annotations.PrimaryKey]",
                "field amount java.math.BigDecimal [@com.rey.modelquery.annotations.Column(attribute=\"total\","
                        + " converter=java.lang.String.class)]",
                "field quantity long [@com.rey.modelquery.annotations.Aggregate(attribute=\"lines.qty\","
                        + " distinct=true, fn=SUM)]",
                "field buyer java.util.Optional<api.CustomerRef> [@com.rey.modelquery.annotations.Join("
                        + "attribute=\"customer\", prefix=\"c_\", type=INNER)]",
                "component lineCount [@com.rey.modelquery.annotations.Selected] accessor lineCount []");
        assertThat(fromClassFile.get("api.CustomerRef")).contains(
                "type [@com.rey.modelquery.annotations.FilterColumn(name=\"city\", path=\"address.city\"),"
                        + " @com.rey.modelquery.annotations.QueryModel(prefix=\"X\", root=java.lang.Object.class)]");
    }

    /** What the processor reads of each of {@link #MODELS}, compiled with {@code sources} over {@code extra}. */
    private static Map<String, List<String>> probe(List<File> extra, JavaFileObject... sources) {
        var probe = new Probe();
        var classpath = new ArrayList<>(extra);
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(File::new)
                .forEach(classpath::add);
        Compilation compilation = javac().withProcessors(probe).withClasspath(classpath).compile(sources);
        assertThat(compilation).succeeded();
        return probe.facts;
    }

    private void write(Compilation compilation) {
        for (JavaFileObject file : compilation.generatedFiles()) {
            String path = file.toUri().getPath();
            Path target = classes.resolve(path.substring(path.indexOf('/', 1) + 1));
            try (InputStream in = file.openInputStream()) {
                Files.createDirectories(target.getParent());
                Files.write(target, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Records, in its first round, what the {@code Element} API gives for each model, rendered as text. */
    private static final class Probe extends AbstractProcessor {

        final Map<String, List<String>> facts = new LinkedHashMap<>();

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (!facts.isEmpty()) {
                return false;
            }
            for (String name : MODELS) {
                TypeElement type = processingEnv.getElementUtils().getTypeElement(name);
                var read = new ArrayList<String>();
                read.add(type.getKind() + " " + type.getModifiers());
                read.add("type " + annotations(type));
                for (var field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
                    if (!field.getModifiers().contains(Modifier.STATIC)) {
                        // As QModelWriter names the type: JavaPoet keeps no type-use annotation.
                        read.add("field " + field.getSimpleName() + " " + TypeName.get(field.asType()) + " "
                                + annotations(field));
                    }
                }
                for (var component : ElementFilter.recordComponentsIn(type.getEnclosedElements())) {
                    read.add("component " + component.getSimpleName() + " " + annotations(component)
                            + " accessor " + component.getAccessor().getSimpleName() + " "
                            + annotations(component.getAccessor()));
                }
                for (var method : ElementFilter.methodsIn(type.getEnclosedElements())) {
                    read.add("method " + method.getModifiers() + " " + method.getSimpleName() + " "
                            + method.getParameters().size());
                }
                for (var constructor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
                    read.add("constructor " + constructor.getModifiers() + " " + constructor.getParameters().stream()
                            .map(parameter -> TypeName.get(parameter.asType()).toString())
                            .collect(Collectors.joining(", ")));
                }
                facts.put(name, read);
            }
            return false;
        }

        /** The annotations on {@code element}, by name, each with the values written on it, sorted by member. */
        private static String annotations(Element element) {
            return element.getAnnotationMirrors().stream()
                    .map(ClasspathModelTest.Probe::render)
                    .sorted()
                    .collect(Collectors.joining(", ", "[", "]"));
        }

        private static String render(AnnotationMirror mirror) {
            String values = mirror.getElementValues().entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().getSimpleName().toString()))
                    .map(entry -> entry.getKey().getSimpleName() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            String name = ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
            return "@" + name + (values.isEmpty() ? "" : "(" + values + ")");
        }
    }
}
