package com.rey.modelquery.processor;

import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * Checks a {@link ModelDefinition} against its entity and against what {@link QModelWriter} can emit. Every check
 * runs whatever the others found, so one compilation reports every independent problem (R-DIAG-03).
 *
 * @implSpec R-GEN-03, R-DIAG-01, R-DIAG-02, R-DIAG-03
 */
final class ModelValidator {

    /** Constants every QModel may declare itself, which a field's constant must not take ({@code MQ3015}). */
    private static final Set<String> RESERVED = Set.of("ROOT", "ALL", "DEFAULT", "KEY", "MAPPER", "GROUP_KEYS");

    private static final String LOMBOK_NO_ARGS = "lombok.NoArgsConstructor";

    private final Types types;
    private final EntityMetamodel metamodel;

    ModelValidator(Types types, EntityMetamodel metamodel) {
        this.types = types;
        this.metamodel = metamodel;
    }

    void validate(ModelDefinition model, Diagnostics diagnostics) {
        checkShape(model, diagnostics);
        if (model.keys().isEmpty()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3004,
                    model.name() + ": no @PrimaryKey; paging, export and @Join presence need one");
        }
        var constants = new HashMap<String, ModelField>();
        for (ModelField field : model.columns()) {
            String where = model.name() + "." + field.name() + ": ";
            checkAttribute(model, field, where, diagnostics);
            if (model.isRecord() && field.type().getKind().isPrimitive() && !field.primaryKey()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3009,
                        where + "primitive components can't be null when not selected; use "
                                + display(types.boxedClass((PrimitiveType) field.type()).asType()));
            }
            ModelField earlier = constants.putIfAbsent(field.constant(), field);
            if (RESERVED.contains(field.constant())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "constant " + field.constant()
                        + " is reserved by the generated class; rename the field");
            } else if (earlier != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "constant " + field.constant()
                        + " is also generated for field '" + earlier.name() + "'; rename the field");
            }
        }
    }

    /** {@code MQ3008} for a class the mapper cannot create, {@code MQ3010} for a record it cannot construct. */
    private static void checkShape(ModelDefinition model, Diagnostics diagnostics) {
        if (model.isRecord()) {
            if (!model.type().getTypeParameters().isEmpty()) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3010, model.name()
                        + ": a generic record can't be mapped through its canonical constructor; "
                        + "remove the type parameters");
            }
            return;
        }
        // Lombok may run after this processor, and its constructor is then not there to see: javac checks the
        // generated call instead, as it does a setter (R-GEN-11).
        boolean lombok = model.type().getAnnotationMirrors().stream()
                .anyMatch(mirror -> ((TypeElement) mirror.getAnnotationType().asElement())
                        .getQualifiedName().contentEquals(LOMBOK_NO_ARGS));
        if (lombok) {
            return;
        }
        // The QModel sits in the model's package, so any constructor that is not private is visible to it.
        boolean creatable = ElementFilter.constructorsIn(model.type().getEnclosedElements()).stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty()
                        && !constructor.getModifiers().contains(Modifier.PRIVATE));
        if (!creatable) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3008,
                    model.name() + ": needs a no-arg constructor for setter mapping");
        }
    }

    /** {@code MQ3001} for a path that does not resolve, {@code MQ3002} for one of another type than the field, {@code MQ3016} for a whole entity. */
    private void checkAttribute(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        Resolution resolution = metamodel.resolve(model.root(), field.attribute());
        if (resolution.attribute() == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + resolution.problem());
            return;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "model type " + display(field.type())
                    + ", entity attribute '" + attribute.name() + "' is a collection, which a column can't select; filter on it with Filters.exists");
            return;
        }
        // The engine compares the two boxed types for identity at first use (MQ1001), so assignable is not enough.
        if (!types.isSameType(boxed(field.type()), boxed(attribute.type()))) {
            String modelType = display(field.type());
            String attributeType = display(attribute.type());
            if (modelType.equals(attributeType)) {
                modelType = field.type().toString();
                attributeType = attribute.type().toString();
            }
            diagnostics.error(field.element(), DiagnosticCode.MQ3002,
                    where + "model type " + modelType + ", entity attribute type " + attributeType);
            return;
        }
        if (attribute.kind() == EntityAttribute.Kind.TO_ONE) {
            String entity = display(attribute.type());
            diagnostics.warning(field.element(), DiagnosticCode.MQ3016, where + "selects the whole " + entity
                    + " entity; use @Join with a query model of " + entity + " to select only its columns");
        }
    }

    private TypeMirror boxed(TypeMirror type) {
        return type.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) type).asType() : type;
    }

    /** {@code type} as a message shows it: simple names, with type arguments. */
    private static String display(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            var declared = (DeclaredType) type;
            String name = declared.asElement().getSimpleName().toString();
            return declared.getTypeArguments().isEmpty() ? name : name + declared.getTypeArguments().stream()
                    .map(ModelValidator::display).collect(Collectors.joining(", ", "<", ">"));
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return display(((ArrayType) type).getComponentType()) + "[]";
        }
        return type.toString();
    }
}
