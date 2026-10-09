package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.processor.ModelDefinition.Kind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Generates a QModel class for every type annotated with {@code @QueryModel}, {@code @UpdateModel} or
 * {@code @InsertModel}: each model is read, validated and, when it has no error, written to one file of its own,
 * beside its change set when it has one. A type carrying two of them is reported and generates nothing.
 *
 * @implSpec R-GEN-01, R-GEN-05, R-GEN-19, R-GEN-21, R-GEN-23, R-PROC-24, R-DIAG-03, D-107
 */
@Incubating
@SupportedAnnotationTypes({"com.rey.modelquery.annotations.QueryModel", "com.rey.modelquery.annotations.UpdateModel",
        "com.rey.modelquery.annotations.InsertModel"})
@SupportedOptions({QueryModelReader.PREFIX_OPTION, QueryModelReader.SUFFIX_OPTION})
public final class ModelQueryProcessor extends AbstractProcessor {

    /** The models whose {@code root}, or a nested model's, named no class in the round before, to retry in this one. */
    private final List<Deferred> deferred = new ArrayList<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        var types = processingEnv.getTypeUtils();
        var reader = new QueryModelReader(processingEnv.getOptions());
        var nestedModels = new NestedModels(reader);
        var metamodel = new EntityMetamodel(types);
        var elements = processingEnv.getElementUtils();
        var builtIns = new BuiltInConverters(elements);
        var round = new Round(reader, nestedModels, new ModelValidator(types, metamodel, nestedModels, builtIns),
                new QModelWriter(types, metamodel, nestedModels, builtIns),
                // A model module without Bean Validation, or without model-query-jpa, compiles and references neither.
                new ChangesWriter(types, metamodel, elements.getTypeElement(ChangesWriter.VALID_CHANGES) != null
                        && elements.getTypeElement(ChangesWriter.CONSTRAINT) != null),
                roundEnv.processingOver());
        // An element is only valid in the round that produced it, so a deferred model is looked up again by name.
        var retried = List.copyOf(deferred);
        deferred.clear();
        for (Deferred model : retried) {
            TypeElement type = elements.getTypeElement(model.name());
            if (type != null) {
                generate(type, model.kind(), round);
                continue;
            }
            // An ambiguous name across JPMS modules resolves to no type element: report it rather than drop the
            // model silently (D-107).
            new Diagnostics(processingEnv.getMessager()).error(DiagnosticCode.MQ3017, model.name()
                    + ": could not be resolved: root does not name a class, and no annotation processor generated one");
        }
        for (Kind kind : Kind.values()) {
            for (Element element : roundEnv.getElementsAnnotatedWith(QueryModelReader.annotation(kind))) {
                var type = (TypeElement) element;
                List<Kind> carried = Arrays.stream(Kind.values())
                        .filter(each -> QueryModelReader.mirror(type, QueryModelReader.annotation(each)) != null)
                        .toList();
                if (carried.size() == 1) {
                    generate(type, kind, round);
                } else if (carried.get(0) == kind) {
                    reportTwoKinds(type, carried);
                }
            }
        }
        return false;
    }

    /** {@code MQ3503} for a type carrying several model annotations, each of which would generate its QModel. */
    private void reportTwoKinds(TypeElement type, List<Kind> carried) {
        List<String> names = carried.stream()
                .map(each -> "@" + QueryModelReader.annotation(each).getSimpleName())
                .toList();
        String annotations = String.join(", ", names.subList(0, names.size() - 1)) + " and "
                + names.get(names.size() - 1);
        new Diagnostics(processingEnv.getMessager()).error(type, DiagnosticCode.MQ3503, type.getSimpleName() + ": "
                + annotations + " each generate a QModel class for it; keep one");
    }

    /**
     * Generates one model, or defers it to the next round while its {@code root}, or a nested model's, names no class
     * yet: another processor may generate that class in this round. The last round reports it instead (D-107).
     */
    private void generate(TypeElement type, Kind kind, Round round) {
        ModelDefinition model = round.reader().read(type, kind);
        TypeElement unresolved = model == null ? type : round.nestedModels().unresolved(model);
        if (unresolved != null) {
            if (!round.last()) {
                deferred.add(new Deferred(type.getQualifiedName().toString(), kind));
                return;
            }
            // An unresolved class reads as an error value, which keeps no name; javac reports the name on its own.
            String root = "root does not name a class, and no annotation processor generated one";
            new Diagnostics(processingEnv.getMessager()).error(type, DiagnosticCode.MQ3017, type.getSimpleName()
                    + ": " + (unresolved == type ? root : "nests " + unresolved.getSimpleName() + ", whose " + root));
            return;
        }
        var diagnostics = new Diagnostics(processingEnv.getMessager());
        round.validator().validate(model, diagnostics);
        if (diagnostics.hasErrors()) {
            return;
        }
        String file = model.generatedName();
        try {
            round.writer().write(model).writeTo(processingEnv.getFiler());
            if (model.changes()) {
                file = model.changesName();
                round.changesWriter().write(model).writeTo(processingEnv.getFiler());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    model.name() + ": could not write " + file + ": " + e.getMessage(), model.type());
        }
    }

    /** A model deferred to the next round, by qualified name, and the annotation it carries (D-107). */
    private record Deferred(String name, Kind kind) {}

    /** What one round reads, checks and writes models with. */
    private record Round(
            QueryModelReader reader, NestedModels nestedModels, ModelValidator validator, QModelWriter writer,
            ChangesWriter changesWriter, boolean last) {}
}
