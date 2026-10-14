package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.BindInsertModel;
import com.rey.modelquery.annotations.BindInsertModels;
import com.rey.modelquery.annotations.BindQueryModel;
import com.rey.modelquery.annotations.BindQueryModels;
import com.rey.modelquery.annotations.BindUpdateModel;
import com.rey.modelquery.annotations.BindUpdateModels;
import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.processor.ModelDefinition.Kind;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
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
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * The {@code @BindQueryModel}s, {@code @BindUpdateModel}s and {@code @BindInsertModel}s of a round, each naming a model
 * compiled without its entity, and the access a bound model needs, since its QModel is generated in the binding's
 * package (R-PROC-28, R-GEN-35, D-134, D-140).
 */
final class ModelBindings {

    /**
     * One binding as written.
     *
     * @param origin the type or {@code package} it is written on, which the generated file originates from
     * @param kind the kind of model it binds
     * @param annotation the binding, which holds the settings the model annotation of {@code kind} would
     * @param model what {@code model} names: a class or record, or another type, which is {@code MQ3024}
     */
    record Binding(Element origin, Kind kind, AnnotationMirror annotation, TypeMirror model) {}

    /**
     * One {@code @BindExpression} as written (R-PROC-29).
     *
     * @param field the model field it names
     * @param value the definition class it names
     */
    record Expression(String field, TypeMirror value) {}

    private ModelBindings() {}

    /**
     * Every binding on the round's types and packages, repeated ones included, query bindings first. A {@code model}
     * that names no class is left out: javac reports the class literal itself.
     */
    static List<Binding> in(RoundEnvironment round) {
        var bindings = new ArrayList<Binding>();
        for (Kind kind : Kind.values()) {
            Class<? extends Annotation> single = annotation(kind);
            Class<? extends Annotation> repeated = container(kind);
            for (Element origin : round.getElementsAnnotatedWithAny(Set.of(single, repeated))) {
                var mirrors = new ArrayList<AnnotationMirror>();
                AnnotationMirror mirror = QueryModelReader.mirror(origin, single);
                if (mirror != null) {
                    mirrors.add(mirror);
                }
                AnnotationMirror container = QueryModelReader.mirror(origin, repeated);
                if (container != null && QueryModelReader.explicit(container, "value") instanceof List<?> values) {
                    for (Object value : values) {
                        mirrors.add((AnnotationMirror) ((AnnotationValue) value).getValue());
                    }
                }
                for (AnnotationMirror each : mirrors) {
                    if (QueryModelReader.explicit(each, "model") instanceof TypeMirror model
                            && model.getKind() != TypeKind.ERROR) {
                        bindings.add(new Binding(origin, kind, each, model));
                    }
                }
            }
        }
        return bindings;
    }

    /** The binding annotation of {@code kind}. */
    static Class<? extends Annotation> annotation(Kind kind) {
        return switch (kind) {
            case QUERY -> BindQueryModel.class;
            case UPDATE -> BindUpdateModel.class;
            case INSERT -> BindInsertModel.class;
        };
    }

    /** The container that makes the binding annotation of {@code kind} repeatable. */
    private static Class<? extends Annotation> container(Kind kind) {
        return switch (kind) {
            case QUERY -> BindQueryModels.class;
            case UPDATE -> BindUpdateModels.class;
            case INSERT -> BindInsertModels.class;
        };
    }

    /** The {@code @BindExpression}s of {@code annotation} in declaration order, none on a model annotation. */
    static List<Expression> expressions(AnnotationMirror annotation) {
        var expressions = new ArrayList<Expression>();
        if (QueryModelReader.explicit(annotation, "expressions") instanceof List<?> values) {
            for (Object value : values) {
                var mirror = (AnnotationMirror) ((AnnotationValue) value).getValue();
                if (QueryModelReader.explicit(mirror, "field") instanceof String field
                        && QueryModelReader.explicit(mirror, "value") instanceof TypeMirror definition) {
                    expressions.add(new Expression(field, definition));
                }
            }
        }
        return expressions;
    }

    /**
     * {@code MQ3025} on the binding for each {@code @BindExpression} that matches nothing: one naming no field of
     * the model, a field with neither {@code @Computed} nor {@code @Aggregate}, a field whose annotation already names
     * a definition, or a field an earlier {@code @BindExpression} names (R-PROC-29).
     */
    static void checkExpressions(Binding binding, TypeElement type, Diagnostics diagnostics) {
        var named = new HashSet<String>();
        for (Expression expression : expressions(binding.annotation())) {
            String where = type.getSimpleName() + "." + expression.field() + ": ";
            VariableElement field = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                    .filter(each -> !each.getModifiers().contains(Modifier.STATIC)
                            && each.getSimpleName().contentEquals(expression.field()))
                    .findFirst().orElse(null);
            AnnotationMirror computed = field == null ? null : QueryModelReader.mirror(field, Computed.class);
            AnnotationMirror aggregate = field == null ? null : QueryModelReader.mirror(field, Aggregate.class);
            String problem;
            if (field == null) {
                problem = "@BindExpression names a field " + type.getSimpleName() + " does not declare";
            } else if (!named.add(expression.field())) {
                problem = "@BindExpression names it more than once; keep one";
            } else if (computed != null) {
                problem = named(computed, "value") ? "@Computed already names "
                        + QueryModelReader.explicit(computed, "value") + "; keep one" : null;
            } else if (aggregate != null) {
                problem = named(aggregate, "expression") ? "@Aggregate already names "
                        + QueryModelReader.explicit(aggregate, "expression") + " as its expression; keep one" : null;
            } else {
                problem = "@BindExpression names a field that is neither @Computed nor @Aggregate";
            }
            if (problem != null) {
                diagnostics.error(binding.origin(), DiagnosticCode.MQ3025, where + problem);
            }
        }
    }

    /** Whether {@code annotation} names a class other than {@code void.class} as {@code member}. */
    private static boolean named(AnnotationMirror annotation, String member) {
        return QueryModelReader.explicit(annotation, member) instanceof TypeMirror type
                && type.getKind() != TypeKind.VOID;
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
     * {@code MQ3024} on the binding for what the generated classes, when in another package, cannot reach: the model
     * type or a type enclosing it; for a query model's class its no-arg constructor and a setter or {@code @Join}
     * getter its mapper calls; and the getter of each column a change set's {@code from} or the insert members read.
     * One that is missing is left to {@code MQ3008} or to javac, as on an annotated model (R-GEN-11). A record needs
     * no more than its type: the JLS makes a public record's canonical constructor and accessors public. Run on a
     * model that validated, since the columns read are the ones the writers find.
     */
    static void checkAccess(ModelDefinition model, Element origin, Elements elements, EntityMetamodel metamodel,
            Diagnostics diagnostics) {
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
        List<ExecutableElement> methods = ElementFilter.methodsIn(elements.getAllMembers(model.type()));
        var hidden = new ArrayList<String>();
        if (model.kind() == Kind.QUERY) {
            boolean hiddenConstructor = ElementFilter.constructorsIn(model.type().getEnclosedElements()).stream()
                    .anyMatch(constructor -> constructor.getParameters().isEmpty()
                            && !constructor.getModifiers().contains(Modifier.PUBLIC));
            if (hiddenConstructor) {
                diagnostics.error(origin, DiagnosticCode.MQ3024,
                        model.name() + ": the no-arg constructor is not public" + calls);
            }
            for (ModelField field : model.fields()) {
                boolean mapped = field.column() || field.computed() || field.aggregate() != null || field.selected()
                        || field.join() != null || field.child() != null;
                String setter = QModelWriter.setter(field);
                if (mapped && hidden(methods, setter, 1)) {
                    hidden.add(setter);
                }
                String getter = "get" + ChangesWriter.capitalized(field.name());
                if (field.join() != null && hidden(methods, getter, 0)) {
                    hidden.add(getter);
                }
            }
        }
        var read = new ArrayList<ModelField>();
        if (model.changes() && !model.updateModel()) {
            read.addAll(ChangedColumns.of(model, metamodel).written());
        }
        if (model.inserts()) {
            InsertedColumns.of(model, metamodel).written().forEach(column -> read.add(column.field()));
        }
        read.stream().map(ChangesWriter::getter).distinct()
                .filter(getter -> !hidden.contains(getter) && hidden(methods, getter, 0))
                .forEach(hidden::add);
        hidden.forEach(method -> diagnostics.error(origin, DiagnosticCode.MQ3024,
                model.name() + "." + method + " is not public" + calls));
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
