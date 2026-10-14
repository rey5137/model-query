package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.BindQueryModel;
import com.rey.modelquery.annotations.BindQueryModels;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * The {@code @BindQueryModel}s of a round, each naming a model compiled without its entity, and the access a bound
 * model needs, since its QModel is generated in the binding's package (R-PROC-28, R-GEN-35, D-134, D-140).
 */
final class ModelBindings {

    /**
     * One binding as written.
     *
     * @param origin the type or {@code package} it is written on, which the generated file originates from
     * @param annotation the {@code @BindQueryModel}, which holds the settings a {@code @QueryModel} would
     * @param model what {@code model} names: a class or record, or another type, which is {@code MQ3024}
     */
    record Binding(Element origin, AnnotationMirror annotation, TypeMirror model) {}

    private ModelBindings() {}

    /**
     * Every binding on the round's types and packages, repeated ones included. A {@code model} that names no class is
     * left out: javac reports the class literal itself.
     */
    static List<Binding> in(RoundEnvironment round) {
        var bindings = new ArrayList<Binding>();
        for (Element origin : round.getElementsAnnotatedWithAny(Set.of(BindQueryModel.class, BindQueryModels.class))) {
            var mirrors = new ArrayList<AnnotationMirror>();
            AnnotationMirror single = QueryModelReader.mirror(origin, BindQueryModel.class);
            if (single != null) {
                mirrors.add(single);
            }
            AnnotationMirror container = QueryModelReader.mirror(origin, BindQueryModels.class);
            if (container != null && QueryModelReader.explicit(container, "value") instanceof List<?> values) {
                for (Object value : values) {
                    mirrors.add((AnnotationMirror) ((AnnotationValue) value).getValue());
                }
            }
            for (AnnotationMirror mirror : mirrors) {
                if (QueryModelReader.explicit(mirror, "model") instanceof TypeMirror model
                        && model.getKind() != TypeKind.ERROR) {
                    bindings.add(new Binding(origin, mirror, model));
                }
            }
        }
        return bindings;
    }

    /** How a message names where a binding is written: a type, or a package's {@code package-info}. */
    static String name(Element origin) {
        return origin instanceof PackageElement pkg ? pkg.getQualifiedName() + ".package-info"
                : ((TypeElement) origin).getQualifiedName().toString();
    }

    /** The class or record a binding names, or {@code null} for another type. */
    static TypeElement modelType(Binding binding) {
        if (binding.model() instanceof DeclaredType declared
                && (declared.asElement().getKind() == ElementKind.CLASS
                        || declared.asElement().getKind() == ElementKind.RECORD)) {
            return (TypeElement) declared.asElement();
        }
        return null;
    }

    /**
     * {@code MQ3024} on the binding for what the QModel, when in another package, cannot reach: the model type or a
     * type enclosing it, a class's no-arg constructor, and a setter or {@code @Join} getter it calls. One that is
     * missing is left to {@code MQ3008} or to javac, as on an annotated model (R-GEN-11). A record needs no more than
     * its type: the JLS makes a public record's canonical constructor and accessors public.
     */
    static void checkAccess(ModelDefinition model, Element origin, Elements elements, Diagnostics diagnostics) {
        if (elements.getPackageOf(model.type()).getQualifiedName().contentEquals(model.packageName())) {
            return;
        }
        String generated = model.generatedName() + " is generated in " + model.packageName();
        for (Element type = model.type(); type instanceof TypeElement; type = type.getEnclosingElement()) {
            if (!type.getModifiers().contains(Modifier.PUBLIC)) {
                diagnostics.error(origin, DiagnosticCode.MQ3024, model.name()
                        + (type == model.type() ? "" : ": its enclosing " + type.getSimpleName())
                        + " is not public, but " + generated + "; make it public");
                return;
            }
        }
        if (model.isRecord()) {
            return;
        }
        String calls = ", but " + generated + " and calls it; make it public";
        boolean hiddenConstructor = ElementFilter.constructorsIn(model.type().getEnclosedElements()).stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty()
                        && !constructor.getModifiers().contains(Modifier.PUBLIC));
        if (hiddenConstructor) {
            diagnostics.error(origin, DiagnosticCode.MQ3024,
                    model.name() + ": the no-arg constructor is not public" + calls);
        }
        List<ExecutableElement> methods = ElementFilter.methodsIn(elements.getAllMembers(model.type()));
        for (ModelField field : model.fields()) {
            boolean mapped = field.column() || field.computed() || field.aggregate() != null || field.selected()
                    || field.join() != null || field.child() != null;
            String setter = QModelWriter.setter(field);
            if (mapped && hidden(methods, setter, 1)) {
                diagnostics.error(origin, DiagnosticCode.MQ3024,
                        model.name() + "." + setter + " is not public" + calls);
            }
            String getter = "get" + ChangesWriter.capitalized(field.name());
            if (field.join() != null && hidden(methods, getter, 0)) {
                diagnostics.error(origin, DiagnosticCode.MQ3024,
                        model.name() + "." + getter + " is not public" + calls);
            }
        }
    }

    /** Whether the model declares or inherits {@code name} with {@code parameters} parameters, and none is public. */
    private static boolean hidden(List<ExecutableElement> methods, String name, int parameters) {
        List<ExecutableElement> named = methods.stream()
                .filter(method -> method.getSimpleName().contentEquals(name)
                        && method.getParameters().size() == parameters)
                .toList();
        return !named.isEmpty() && named.stream().noneMatch(method -> method.getModifiers().contains(Modifier.PUBLIC));
    }
}
