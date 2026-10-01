package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.UpdateModel;
import java.io.IOException;
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
 * Generates a QModel class for every type annotated with {@code @QueryModel} or {@code @UpdateModel}: each model is
 * read, validated and, when it has no error, written to one file of its own, beside its change set when it has one.
 *
 * @implSpec R-GEN-01, R-GEN-05, R-GEN-19, R-GEN-21, R-GEN-23, R-DIAG-03
 */
@SupportedAnnotationTypes({"com.rey.modelquery.annotations.QueryModel", "com.rey.modelquery.annotations.UpdateModel"})
@SupportedOptions({QueryModelReader.PREFIX_OPTION, QueryModelReader.SUFFIX_OPTION})
public final class ModelQueryProcessor extends AbstractProcessor {

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
        var validator = new ModelValidator(types, metamodel, nestedModels, builtIns);
        var writer = new QModelWriter(types, metamodel, nestedModels, builtIns);
        // A model module without Bean Validation, or without model-query-jpa, compiles and references neither.
        var changesWriter = new ChangesWriter(types, elements.getTypeElement(ChangesWriter.VALID_CHANGES) != null
                && elements.getTypeElement(ChangesWriter.CONSTRAINT) != null);
        for (Element element : roundEnv.getElementsAnnotatedWith(QueryModel.class)) {
            generate(reader.read((TypeElement) element), validator, writer, changesWriter);
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(UpdateModel.class)) {
            generate(reader.readUpdate((TypeElement) element), validator, writer, changesWriter);
        }
        return false;
    }

    private void generate(
            ModelDefinition model, ModelValidator validator, QModelWriter writer, ChangesWriter changesWriter) {
        if (model == null) {
            return;
        }
        var diagnostics = new Diagnostics(processingEnv.getMessager());
        validator.validate(model, diagnostics);
        if (diagnostics.hasErrors()) {
            return;
        }
        String file = model.generatedName();
        try {
            writer.write(model).writeTo(processingEnv.getFiler());
            if (model.changes()) {
                file = model.changesName();
                changesWriter.write(model).writeTo(processingEnv.getFiler());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    model.name() + ": could not write " + file + ": " + e.getMessage(), model.type());
        }
    }
}
