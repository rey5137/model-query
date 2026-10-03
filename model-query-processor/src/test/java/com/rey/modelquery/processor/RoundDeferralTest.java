package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static com.rey.modelquery.processor.ProcessorHarness.errors;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.testing.compile.Compilation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** A model whose {@code root} another processor generates is deferred to a later round (D-107, R-DIAG-03). */
class RoundDeferralTest {

    /** The entity the test processor generates in the first round, which the models are rooted at. */
    private static final String SHIPMENT = """
            package gen;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;

            @Entity
            public class ShipmentEntity {
                @Id
                private Long id;
                private String carrier;
            }
            """;

    private static final JavaFileObject SHIPMENT_VIEW = source("models.ShipmentView", """
            package models;

            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import gen.ShipmentEntity;

            @QueryModel(root = ShipmentEntity.class)
            public record ShipmentView(@PrimaryKey Long id, String carrier) {}
            """);

    /** An entity of the sources whose association targets the generated entity. */
    private static final JavaFileObject PARCEL = source("models.ParcelEntity", """
            package models;

            import gen.ShipmentEntity;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class ParcelEntity {
                @Id
                private Long id;
                @ManyToOne
                private ShipmentEntity shipment;
            }
            """);

    /** A model with a resolvable root, which nests a model rooted at the generated entity. */
    private static final JavaFileObject PARCEL_VIEW = source("models.ParcelView", """
            package models;

            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.QueryModel;
            import java.util.Optional;

            @QueryModel(root = ParcelEntity.class)
            public record ParcelView(@PrimaryKey Long id, @Join Optional<ShipmentView> shipment) {}
            """);

    /** Generates {@code gen.ShipmentEntity} in the first round, as an entity-generating processor would. */
    private static final class ShipmentGenerator extends AbstractProcessor {

        private boolean generated;

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
            if (!generated) {
                generated = true;
                try (Writer out = processingEnv.getFiler().createSourceFile("gen.ShipmentEntity").openWriter()) {
                    out.write(SHIPMENT);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        }
    }

    @Test
    void ac_diag_06_a_model_rooted_at_a_type_another_processor_generates_is_generated_in_a_later_round() {
        // The model-query processor runs first, so its first round sees the root before it exists.
        Compilation compilation = javac()
                .withProcessors(new ModelQueryProcessor(), new ShipmentGenerator())
                .compile(SHIPMENT_VIEW, PARCEL, PARCEL_VIEW);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation.generatedSourceFile("models.QShipmentView")).isPresent();
        // A model whose own root resolves waits for the model it nests, rather than failing its @Join (MQ3005).
        assertThat(compilation.generatedSourceFile("models.QParcelView")).isPresent();
    }

    @Test
    void ac_diag_07_a_model_whose_root_is_never_generated_reports_mq3017_and_produces_no_qmodel() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(SHIPMENT_VIEW, PARCEL, PARCEL_VIEW);

        assertThat(compilation).failed();
        // Beside javac's own errors for the missing class, which name it.
        assertThat(errors(compilation)).contains(
                "MQ3017: ShipmentView: root does not name a class, and no annotation processor generated one",
                "MQ3017: ParcelView: nests ShipmentView, whose root does not name a class, and no annotation "
                        + "processor generated one");
        assertThat(processor.messages())
                .extracting(
                        ProcessorHarness.Recording.Message::kind,
                        message -> message.text().substring(0, 6),
                        message -> message.element().getKind(),
                        message -> message.element().toString())
                .containsExactlyInAnyOrder(
                        tuple(Diagnostic.Kind.ERROR, DiagnosticCode.MQ3017.code(), ElementKind.RECORD,
                                "models.ShipmentView"),
                        tuple(Diagnostic.Kind.ERROR, DiagnosticCode.MQ3017.code(), ElementKind.RECORD,
                                "models.ParcelView"));
        assertThat(processor.originating()).isEmpty();
    }
}
