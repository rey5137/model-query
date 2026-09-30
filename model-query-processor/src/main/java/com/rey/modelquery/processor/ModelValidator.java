package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.rey.modelquery.processor.ModelDefinition.JoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
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
 * @implSpec R-GEN-03, R-PROC-07, R-PROC-08, R-DIAG-01, R-DIAG-02, R-DIAG-03
 */
final class ModelValidator {

    /** Constants every QModel may declare itself, which a field's constant must not take ({@code MQ3015}). */
    private static final Set<String> RESERVED = Set.of("ROOT", "ALL", "DEFAULT", "KEY", "MAPPER", "GROUP_KEYS");

    private static final String LOMBOK_NO_ARGS = "lombok.NoArgsConstructor";

    private final Types types;
    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;

    ModelValidator(Types types, EntityMetamodel metamodel, NestedModels nestedModels) {
        this.types = types;
        this.metamodel = metamodel;
        this.nestedModels = nestedModels;
    }

    void validate(ModelDefinition model, Diagnostics diagnostics) {
        checkShape(model, diagnostics);
        if (model.keys().isEmpty()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3004,
                    model.name() + ": no @PrimaryKey; paging, export and @Join presence need one");
        }
        // What each constant is generated for, as a clash names it: a joined column first, so that a field taking its
        // name is the one reported.
        var constants = new HashMap<String, String>();
        for (ModelField join : model.joins()) {
            String where = model.name() + "." + join.name() + ": ";
            ModelDefinition nested = checkJoin(model, join, where, diagnostics);
            if (nested != null) {
                claimJoined(nestedModels.tables(model, join, nested), constants, where, diagnostics);
            }
        }
        for (ModelField field : model.columns()) {
            String where = model.name() + "." + field.name() + ": ";
            checkAttribute(model, field, where, diagnostics);
            if (model.isRecord() && field.type().getKind().isPrimitive() && !field.primaryKey()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3009,
                        where + "primitive components can't be null when not selected; use "
                                + display(types.boxedClass((PrimitiveType) field.type()).asType()));
            }
            String earlier = constants.putIfAbsent(field.constant(), "field '" + field.name() + "'");
            if (RESERVED.contains(field.constant())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "constant " + field.constant()
                        + " is reserved by the generated class; rename the field");
            } else if (earlier != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "constant " + field.constant()
                        + " is also generated for " + earlier + "; rename the field"
                        + (earlier.startsWith("field ") ? "" : " or set @Join(prefix)"));
            }
        }
    }

    /**
     * Checks one {@code @Join} and returns the model it nests, or {@code null} when the join cannot be generated:
     * {@code MQ3005} for the field's type, {@code MQ3003} for the association, {@code MQ3006} for a nested model
     * without a key, {@code MQ3007} for a cycle.
     */
    private ModelDefinition checkJoin(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        JoinDefinition join = field.join();
        ModelDefinition nested = nestedModels.of(field);
        if (join.nested() == null) {
            boolean isModel = field.type().getKind() == TypeKind.DECLARED
                    && ((DeclaredType) field.type()).asElement().getAnnotation(QueryModel.class) != null;
            String expected = isModel ? "Optional<" + display(field.type()) + ">" : "Optional<X> of a @QueryModel X";
            diagnostics.error(field.element(), DiagnosticCode.MQ3005,
                    where + "@Join field must be " + expected + ", found " + display(field.type()));
        } else if (nested == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3005,
                    where + display(join.nested()) + " is not a @QueryModel");
        }
        boolean joinable = nested != null;
        if (!SourceVersion.isIdentifier(join.prefix())) {
            // The prefix starts every constant of the join, so it must be one itself.
            diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "@Join(prefix = \"" + join.prefix()
                    + "\") can't start a constant's name; use a Java identifier such as "
                    + QueryModelReader.constantName(field.name()));
            joinable = false;
        }
        TypeElement target = checkAssociation(model, field, where, diagnostics);
        if (target == null) {
            joinable = false;
        } else if (nested != null && !target.equals(nested.root())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3003, where + nested.name() + ".root is "
                    + nested.root().getSimpleName() + ", association targets " + target.getSimpleName());
            joinable = false;
        }
        if (nested == null) {
            return null;
        }
        if (nested.keys().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3006,
                    where + nested.name() + " needs a @PrimaryKey to be used in @Join");
            joinable = false;
        }
        String cycle = cycle(new ArrayList<>(List.of(model.type())), model.name() + "." + field.name(), nested);
        if (cycle != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3007, cycle);
            joinable = false;
        }
        return joinable ? nested : null;
    }

    /** The entity a {@code @Join}'s attribute reaches, or {@code null} with {@code MQ3001} or {@code MQ3003}. */
    private TypeElement checkAssociation(
            ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        String attribute = field.join().attribute();
        String root = model.root().getSimpleName().toString();
        if (attribute.contains(".")) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3003, where + "'" + attribute
                    + "' is a path; @Join takes a to-one association of " + root + " itself");
            return null;
        }
        Resolution resolution = metamodel.resolve(model.root(), attribute);
        if (resolution.attribute() == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + resolution.problem());
            return null;
        }
        return switch (resolution.attribute().kind()) {
            case TO_ONE -> (TypeElement) ((DeclaredType) resolution.attribute().type()).asElement();
            case COLLECTION -> {
                diagnostics.error(field.element(), DiagnosticCode.MQ3003, where + "'" + attribute + "' on " + root
                        + " is a collection, which a @Join can't select; filter on it with Filters.exists");
                yield null;
            }
            default -> {
                diagnostics.error(field.element(), DiagnosticCode.MQ3003,
                        where + "'" + attribute + "' on " + root + " is not a to-one association");
                yield null;
            }
        };
    }

    /**
     * The chain of joins that leads from {@code chain} through {@code nested} back to a model already on the way,
     * as {@code MQ3007} shows it, or {@code null} when there is none.
     */
    private String cycle(List<TypeElement> visiting, String chain, ModelDefinition nested) {
        if (visiting.contains(nested.type())) {
            return chain + " → " + nested.name();
        }
        visiting.add(nested.type());
        for (ModelField join : nested.joins()) {
            ModelDefinition below = nestedModels.of(join);
            String cycle = below == null
                    ? null : cycle(visiting, chain + " → " + nested.name() + "." + join.name(), below);
            if (cycle != null) {
                return cycle;
            }
        }
        visiting.remove(visiting.size() - 1);
        return null;
    }

    /** {@code MQ3015} for a join one of whose constants is reserved, or already generated for an earlier join. */
    private static void claimJoined(
            List<JoinedTable> tables, Map<String, String> constants, String where, Diagnostics diagnostics) {
        for (JoinedTable table : tables) {
            var generated = new LinkedHashMap<String, String>();
            generated.put(table.table(), "the join " + table.path());
            generated.put(table.prefix(), "the columns of " + table.path());
            table.columns().forEach(column -> generated.put(column.constant(), column.path()));
            // A join whose prefix is taken clashes on every constant: the first one says it all.
            boolean reported = false;
            for (var constant : generated.entrySet()) {
                String earlier = constants.putIfAbsent(constant.getKey(), constant.getValue());
                String problem = RESERVED.contains(constant.getKey()) ? " is reserved by the generated class"
                        : earlier != null ? " is also generated for " + earlier : null;
                if (problem != null && !reported) {
                    diagnostics.error(table.join().element(), DiagnosticCode.MQ3015, where + "constant "
                            + constant.getKey() + problem + "; rename the field or set @Join(prefix)");
                    reported = true;
                }
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

    /**
     * {@code MQ3001} for a path that does not resolve, {@code MQ3002} for one of another type than the field,
     * {@code MQ3014} for a converter that does not bridge the two, {@code MQ3016} for a whole entity.
     */
    private void checkAttribute(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        Resolution resolution = metamodel.resolve(model.root(), field.attribute());
        if (resolution.attribute() == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + resolution.problem());
            return;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "model type " + display(field.type())
                    + ", entity attribute '" + attribute.name()
                    + "' is a collection, which a column can't select; filter on it with Filters.exists");
            return;
        }
        if (field.converter() != null) {
            if (!checkConverter(model, field, attribute, where, diagnostics)) {
                return;
            }
        } else if (!types.isSameType(boxed(field.type()), boxed(attribute.type()))) {
            // The engine compares the two boxed types for identity at first use (MQ1001), so assignable is not enough.
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
        // A converted column holds what its converter makes of the entity, not the entity.
        if (attribute.kind() == EntityAttribute.Kind.TO_ONE && field.converter() == null) {
            String entity = display(attribute.type());
            diagnostics.warning(field.element(), DiagnosticCode.MQ3016, where + "selects the whole " + entity
                    + " entity; use @Join with a query model of " + entity + " to select only its columns");
        }
    }

    /**
     * {@code MQ3014} unless the field's converter is a {@code ColumnConverter} from the field's type to the
     * attribute's that the QModel can obtain (R-PROC-07).
     */
    private boolean checkConverter(
            ModelDefinition model, ModelField field, EntityAttribute attribute, String where,
            Diagnostics diagnostics) {
        String name = display(field.converter());
        ConverterType converter = ConverterType.of(types, field.converter());
        if (converter == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3014, where + name + " is not a ColumnConverter<"
                    + display(boxed(field.type())) + ", " + display(boxed(attribute.type())) + ">");
            return false;
        }
        boolean fits = true;
        var expected = new ArrayList<String>();
        if (!types.isSameType(converter.model(), boxed(field.type()))) {
            expected.add("model type " + display(field.type()));
        }
        if (!types.isSameType(converter.attribute(), boxed(attribute.type()))) {
            expected.add("entity attribute type " + display(attribute.type()));
        }
        if (!expected.isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3014, where + name + " converts "
                    + display(converter.model()) + " to " + display(converter.attribute()) + ", "
                    + String.join(", ", expected));
            fits = false;
        }
        if (!converter.hasInstance() && !converter.hasVisibleConstructor(model.type())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3014, where + name
                    + " needs a public static INSTANCE or a no-arg constructor visible to " + model.generatedName());
            fits = false;
        }
        return fits;
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
