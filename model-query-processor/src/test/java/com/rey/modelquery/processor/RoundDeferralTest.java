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

/**
 * A model whose {@code root} another processor generates is deferred to a later round, annotated or bound (D-107,
 * R-DIAG-03, R-PROC-30).
 */
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

    /** {@link #SHIPMENT_VIEW} as a model module writes it, bound in {@link #BINDINGS} (R-PROC-30). */
    private static final JavaFileObject BOUND_SHIPMENT_VIEW = source("models.ShipmentView", """
            package models;

            import com.rey.modelquery.annotations.PrimaryKey;

            public record ShipmentView(@PrimaryKey Long id, String carrier) {}
            """);

    /** {@link #PARCEL_VIEW} as a model module writes it, nesting the bound {@link #BOUND_SHIPMENT_VIEW}. */
    private static final JavaFileObject BOUND_PARCEL_VIEW = source("models.ParcelView", """
            package models;

            import com.rey.modelquery.annotations.Join;
            import com.rey.modelquery.annotations.PrimaryKey;
            import java.util.Optional;

            public record ParcelView(@PrimaryKey Long id, @Join Optional<ShipmentView> shipment) {}
            """);

    /** Binds both models, one rooted at the generated entity, the other nesting it (R-PROC-31). */
    private static final JavaFileObject BINDINGS = source("persistence.Models", """
            package persistence;

            import com.rey.modelquery.annotations.BindQueryModel;
            import gen.ShipmentEntity;
            import models.ParcelEntity;
            import models.ParcelView;
            import models.ShipmentView;

            @BindQueryModel(model = ShipmentView.class, root = ShipmentEntity.class)
            @BindQueryModel(model = ParcelView.class, root = ParcelEntity.class)
            final class Models {}
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

    /** A binding of {@link #BOUND_SHIPMENT_VIEW} that a processor generates, so it is read in a later round. */
    private static final String LATER_BINDING = """
            package persistence;

            import com.rey.modelquery.annotations.BindQueryModel;
            import models.CarrierEntity;
            import models.ShipmentView;

            @BindQueryModel(model = ShipmentView.class, root = CarrierEntity.class)
            final class Models {}
            """;

    /** Generates one source in the first round. */
    private static final class SourceGenerator extends AbstractProcessor {

        private final String name;
        private final String text;
        private boolean generated;

        SourceGenerator(String name, String text) {
            this.name = name;
            this.text = text;
        }

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
                try (Writer out = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                    out.write(text);
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

    @Test
    void ac_proc_26_a_bound_model_rooted_at_a_generated_type_and_one_nesting_it_are_generated_in_a_later_round() {
        Compilation compilation = javac()
                .withProcessors(new ModelQueryProcessor(), new ShipmentGenerator())
                .compile(BOUND_SHIPMENT_VIEW, PARCEL, BOUND_PARCEL_VIEW, BINDINGS);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(compilation.generatedSourceFile("persistence.QShipmentView")).isPresent();
        assertThat(compilation.generatedSourceFile("persistence.QParcelView")).isPresent();
    }

    @Test
    void ac_proc_26_a_bound_model_whose_root_is_never_generated_reports_mq3017_on_its_binding() {
        var processor = new ProcessorHarness.Recording();

        Compilation compilation = processor.compile(BOUND_SHIPMENT_VIEW, PARCEL, BOUND_PARCEL_VIEW, BINDINGS);

        assertThat(compilation).failed();
        assertThat(errors(compilation)).contains(
                "MQ3017: ShipmentView: root does not name a class, and no annotation processor generated one",
                "MQ3017: ParcelView: nests ShipmentView, whose root does not name a class, and no annotation "
                        + "processor generated one");
        assertThat(processor.messages())
                .extracting(
                        ProcessorHarness.Recording.Message::kind,
                        message -> message.text().substring(0, 6),
                        message -> message.element().toString())
                .containsExactly(
                        tuple(Diagnostic.Kind.ERROR, DiagnosticCode.MQ3017.code(), "persistence.Models"),
                        tuple(Diagnostic.Kind.ERROR, DiagnosticCode.MQ3017.code(), "persistence.Models"));
        assertThat(processor.originating()).isEmpty();
    }

    @Test
    void ac_proc_26_a_deferred_binding_generating_the_qmodel_name_an_earlier_round_generated_is_mq3023() {
        JavaFileObject sameName = source("other.ShipmentView", """
                package other;

                import com.rey.modelquery.annotations.PrimaryKey;

                public record ShipmentView(@PrimaryKey Long id) {}
                """);
        JavaFileObject sameNameBinding = source("persistence.OtherModels", """
                package persistence;

                import com.rey.modelquery.annotations.BindQueryModel;
                import models.ParcelEntity;

                @BindQueryModel(model = other.ShipmentView.class, root = ParcelEntity.class)
                final class OtherModels {}
                """);

        var processor = new ProcessorHarness.Recording();

        Compilation compilation = javac()
                .withProcessors(processor, new ShipmentGenerator())
                .compile(BOUND_SHIPMENT_VIEW, PARCEL, BOUND_PARCEL_VIEW, BINDINGS, sameName, sameNameBinding);

        // The first round generates other.ShipmentView's QModel; the deferred binding finds the name taken (D-142).
        assertThat(errors(compilation)).containsExactly("MQ3023: ShipmentView: @BindQueryModel on persistence.Models "
                + "and @BindQueryModel of other.ShipmentView on persistence.OtherModels each generate "
                + "persistence.QShipmentView; give one another prefix or package");
        assertThat(processor.originating().get("persistence.QShipmentView")).singleElement()
                .hasToString("persistence.OtherModels");
    }

    @Test
    void ac_proc_29_a_model_bound_again_by_a_later_round_binding_in_another_package_is_mq3023_on_the_later() {
        JavaFileObject carrier = source("models.CarrierEntity", """
                package models;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                public class CarrierEntity {
                    @Id
                    private Long id;
                    private String carrier;
                }
                """);
        JavaFileObject earlierBinding = source("early.Models", """
                package early;

                import com.rey.modelquery.annotations.BindQueryModel;
                import models.CarrierEntity;
                import models.ShipmentView;

                @BindQueryModel(model = ShipmentView.class, root = CarrierEntity.class)
                final class Models {}
                """);

        var processor = new ProcessorHarness.Recording();

        // A binding generated in round 1 is read in round 2, after early.Models was generated in round 1.
        Compilation compilation = javac()
                .withProcessors(processor, new SourceGenerator("persistence.Models", LATER_BINDING))
                .compile(BOUND_SHIPMENT_VIEW, carrier, earlierBinding);

        assertThat(errors(compilation)).contains("MQ3023: ShipmentView: @BindQueryModel binds it more than once, "
                + "on early.Models and persistence.Models; keep one");
        assertThat(processor.originating()).containsKey("early.QShipmentView").doesNotContainKey(
                "persistence.QShipmentView");
        assertThat(processor.messages())
                .filteredOn(message -> message.text().startsWith("MQ3023"))
                .singleElement()
                .satisfies(message -> assertThat(message.element()).hasToString("persistence.Models"));
    }
}
