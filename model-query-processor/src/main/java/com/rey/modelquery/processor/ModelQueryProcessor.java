package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.QueryModel;
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
 * Generates a QModel class for every type annotated with {@code @QueryModel}: each model is read, validated and, when
 * it has no error, written to one file of its own.
 *
 * @implSpec R-GEN-01, R-GEN-05, R-DIAG-03
 */
@SupportedAnnotationTypes("com.rey.modelquery.annotations.QueryModel")
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
        var validator = new ModelValidator(types, metamodel, nestedModels);
        var writer = new QModelWriter(types, metamodel, nestedModels);
        for (Element element : roundEnv.getElementsAnnotatedWith(QueryModel.class)) {
            var type = (TypeElement) element;
            if (QueryModelReader.usesLaterFeature(type)) {
                continue;
            }
            ModelDefinition model = reader.read(type);
            if (model == null) {
                continue;
            }
            var diagnostics = new Diagnostics(processingEnv.getMessager());
            validator.validate(model, diagnostics);
            if (diagnostics.hasErrors()) {
                continue;
            }
            try {
                writer.write(model).writeTo(processingEnv.getFiler());
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        model.name() + ": could not write " + model.generatedName() + ": " + e.getMessage(), type);
            }
        }
        return false;
    }
}
