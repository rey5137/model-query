package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.rey.modelquery.processor.ModelDefinition.AggregateDefinition;
import com.rey.modelquery.processor.ModelDefinition.FilterColumnDefinition;
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
import javax.lang.model.element.Element;
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
 * @implSpec R-GEN-03, R-PROC-05, R-PROC-07, R-PROC-08, R-PROC-10, R-PROC-11, R-PROC-12, R-PROC-16, R-DIAG-01,
 *     R-DIAG-02, R-DIAG-03
 */
final class ModelValidator {

    /** Constants every QModel may declare itself, which a field's constant must not take ({@code MQ3015}). */
    private static final Set<String> RESERVED = Set.of("ROOT", "ALL", "DEFAULT", "KEY", "MAPPER", "GROUP_KEYS");

    /** The only constant an update model's generated class declares itself (R-GEN-19). */
    private static final Set<String> RESERVED_BY_UPDATE = Set.of("ROOT");

    private static final String LONG = "java.lang.Long";
    private static final String DOUBLE = "java.lang.Double";
    private static final String NUMBER = "java.lang.Number";
    private static final String COMPARABLE = "java.lang.Comparable";
    /** What {@code Agg.sum} sums to the column's own type (api/13 R-AGG-03). */
    private static final Set<String> SUMMABLE = Set.of("java.math.BigDecimal", DOUBLE, LONG);
    /** What {@code Agg.sumAsLong} sums; the database returns a {@code Long} (api/13 R-AGG-03). */
    private static final Set<String> INTEGRAL = Set.of("java.lang.Integer", "java.lang.Short", "java.lang.Byte");

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
        // An update model is only read, never instantiated, so neither its shape nor its primitives matter.
        if (!model.updateModel()) {
            checkShape(model, diagnostics);
        }
        Set<String> reserved = model.updateModel() ? RESERVED_BY_UPDATE : RESERVED;
        // A group has no row identity, so a summary model needs no key (R-AGG-09).
        if (model.keys().isEmpty() && model.aggregates().isEmpty()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3004, model.name() + (model.updateModel()
                    ? ": no @PrimaryKey; update(...) and delete() choose rows by the entity id"
                    : ": no @PrimaryKey; paging, export and @Join presence need one"));
        }
        // What each constant is generated for, as a clash names it: a joined column first, so that a field taking its
        // name is the one reported.
        var constants = new HashMap<String, String>();
        var joined = new ArrayList<JoinedTable>();
        for (ModelField join : model.joins()) {
            String where = model.name() + "." + join.name() + ": ";
            ModelDefinition nested = checkJoin(model, join, where, diagnostics);
            if (nested != null) {
                List<JoinedTable> tables = nestedModels.tables(model, join, nested);
                claimJoined(tables, constants, where, diagnostics);
                joined.addAll(tables);
            }
        }
        Set<String> ofJoins = Set.copyOf(constants.keySet());
        // A filter path is laid out over the joins that can be generated: one that failed is reported above.
        FilterLayout filters = FilterLayout.of(model, joined, metamodel);
        filters.problems().forEach(problem -> diagnostics.error(model.type(), problem.code(), problem.detail()));
        claimFilterTables(model, filters, constants, diagnostics);
        checkGrouping(model, diagnostics);
        // Only an ungrouped query always selects the key: a grouped one selects it like any other column (D-49).
        boolean ungrouped = model.aggregates().isEmpty() && model.groupKeys().isEmpty();
        for (ModelField field : model.fields()) {
            if (!field.column() && field.aggregate() == null) {
                continue;
            }
            String where = model.name() + "." + field.name() + ": ";
            if (field.aggregate() != null) {
                checkAggregate(model, field, where, diagnostics);
            } else {
                checkAttribute(model, field, where, diagnostics);
            }
            if (field.column() && model.isRecord() && !model.updateModel() && field.type().getKind().isPrimitive()
                    && !(ungrouped && field.primaryKey())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3009,
                        where + "primitive components can't be null when not selected; use "
                                + display(types.boxedClass((PrimitiveType) field.type()).asType()));
            }
            if (field.join() != null) {
                // An @Aggregate on a @Join field is MQ3204; its constant is the join's own prefix, not a clash.
                continue;
            }
            String earlier = constants.putIfAbsent(field.constant(), "field '" + field.name() + "'");
            if (reserved.contains(field.constant())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "constant " + field.constant()
                        + " is reserved by the generated class; rename the field");
            } else if (earlier != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3015, where + "constant " + field.constant()
                        + " is also generated for " + earlier + "; rename the field"
                        + (ofJoins.contains(field.constant()) ? " or set @Join(prefix)" : ""));
            }
        }
        for (FilterColumnDefinition column : model.filterColumns()) {
            String where = model.name() + " " + column.label() + ": ";
            if (!isSimpleName(column.name())) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3013, where + "name '" + column.name()
                        + "' can't be a constant's name; use a Java identifier");
            } else if (reserved.contains(column.name())) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3013,
                        where + "name is reserved by the generated class");
            } else {
                String earlier = constants.putIfAbsent(column.name(), "another @FilterColumn");
                if (earlier != null) {
                    diagnostics.error(model.type(), DiagnosticCode.MQ3013, where + "name already used by " + earlier);
                }
            }
        }
        for (FilterLayout.Column column : filters.columns()) {
            if (column.definition().converter() != null) {
                checkConverter(model, model.type(), column.definition().converter(), null, column.read(),
                        model.name() + " " + column.definition().label() + ": ", diagnostics);
            }
        }
    }

    /**
     * {@code MQ3204} for a {@code @GroupBy} on an {@code @Aggregate} or {@code @Join} field or an {@code @Aggregate}
     * beside {@code @PrimaryKey}, {@code @Column}, {@code @Join} or {@code @Transient}, {@code MQ3207} for
     * {@code singleGroup} with a {@code @GroupBy}, {@code MQ3203} for aggregates that no {@code @GroupBy} groups,
     * unless the model says it is a whole-table total (R-PROC-05).
     */
    private static void checkGrouping(ModelDefinition model, Diagnostics diagnostics) {
        for (ModelField field : model.fields()) {
            if (field.groupBy() && (field.aggregate() != null || field.join() != null)) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3204, model.name() + "." + field.name()
                        + ": @GroupBy can't be combined with " + (field.aggregate() != null ? "@Aggregate" : "@Join"));
            }
        }
        for (ModelField field : model.fields()) {
            for (String annotation : combinedWith(field)) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3204, model.name() + "." + field.name()
                        + ": @Aggregate can't be combined with " + annotation);
            }
        }
        if (model.singleGroup() && model.fields().stream().anyMatch(ModelField::groupBy)) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3207, model.name()
                    + ": singleGroup = true can't be combined with @GroupBy fields; remove one");
        }
        if (!model.aggregates().isEmpty() && model.groupKeys().isEmpty() && !model.singleGroup()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3203, model.name()
                    + ": has @Aggregate fields but no @GroupBy; add one or set @QueryModel(singleGroup = true)");
        }
    }

    /**
     * {@code MQ3201} for a primitive field, {@code MQ3202} for a type that is not what the function returns,
     * {@code MQ3205} for a {@code SUM} over a 32-bit attribute, {@code MQ3206} for {@code distinct} on any function
     * but {@code COUNT}, {@code MQ3001} or {@code MQ3002} for an attribute the function cannot read (R-AGG-03,
     * R-AGG-04). A primitive field is reported once, with the type the function returns.
     */
    private void checkAggregate(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        AggregateDefinition aggregate = field.aggregate();
        String fn = aggregate.fn();
        if (!combinedWith(field).isEmpty()) {
            return;
        }
        if (aggregate.distinct() && !fn.equals("COUNT")) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3206,
                    where + "distinct only applies to COUNT, found " + fn);
        }
        TypeMirror fieldType = boxed(field.type());
        Expected expected = expected(model, field, where, diagnostics);
        if (field.type().getKind().isPrimitive()) {
            String use = expected == null ? display(fieldType) : simpleName(expected.type());
            diagnostics.error(field.element(), DiagnosticCode.MQ3201, where + (fn.equals("COUNT")
                    ? "COUNT is NULL-safe only as an object; use " : fn + " is NULL over zero rows; use ")
                    + use + ", not a primitive");
        } else if (expected != null && !qualified(fieldType).equals(expected.type())) {
            if (expected.thirtyTwoBit()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3205, where + expected.subject()
                        + " returns Long; declare the field as Long");
            } else {
                diagnostics.error(field.element(), DiagnosticCode.MQ3202, where + expected.subject() + " returns "
                        + simpleName(expected.type()) + ", field is " + display(fieldType));
            }
        }
    }

    /** What an aggregate returns: the qualified class, how a message names the call, and whether it sums 32 bits. */
    private record Expected(String type, String subject, boolean thirtyTwoBit) {}

    /**
     * The result of {@code field}'s aggregate, or {@code null} with {@code MQ3001}, {@code MQ3002} or {@code MQ3202}
     * when its attribute cannot be read by the function.
     */
    private Expected expected(ModelDefinition model, ModelField field, String where, Diagnostics diagnostics) {
        AggregateDefinition aggregate = field.aggregate();
        String fn = aggregate.fn();
        boolean count = fn.equals("COUNT");
        if (count && aggregate.attribute().isEmpty() && !aggregate.distinct()) {
            return new Expected(LONG, "COUNT", false);
        }
        if (aggregate.attribute().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + fn
                    + (count ? " distinct" : "") + " needs an attribute to read; write @Aggregate(attribute)");
            return null;
        }
        Resolution resolution = metamodel.resolve(model.root(), aggregate.attribute());
        if (resolution.attribute() == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3001, where + resolution.problem());
            return null;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute.kind() == EntityAttribute.Kind.TO_ONE || attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "@Aggregate attribute '"
                    + attribute.name() + "' is an association on " + model.root().getSimpleName()
                    + "; aggregate a basic attribute");
            return null;
        }
        TypeMirror source = boxed(attribute.type());
        String sourceName = display(source);
        String over = fn + " over " + sourceName;
        switch (fn) {
            case "COUNT" -> {
                return new Expected(LONG, "COUNT", false);
            }
            case "SUM" -> {
                if (isOneOf(source, SUMMABLE)) {
                    return new Expected(qualified(source), over, false);
                }
                if (isOneOf(source, INTEGRAL)) {
                    // Agg.sum refuses a 32-bit column; the field is a Long and the QModel sums it as one.
                    return new Expected(LONG, over, true);
                }
                diagnostics.error(field.element(), DiagnosticCode.MQ3202, where + "SUM over " + sourceName
                        + " is not supported; it sums BigDecimal, Double, Long, Integer, Short and Byte");
            }
            case "AVG" -> {
                if (isSubtypeOf(source, NUMBER)) {
                    return new Expected(DOUBLE, over, false);
                }
                diagnostics.error(field.element(), DiagnosticCode.MQ3202,
                        where + "AVG over " + sourceName + " is not supported; it averages a numeric attribute");
            }
            default -> {
                if (isSubtypeOf(source, COMPARABLE)) {
                    return new Expected(qualified(source), over, false);
                }
                diagnostics.error(field.element(), DiagnosticCode.MQ3202, where + over
                        + " is not supported; " + fn + " needs a Comparable attribute");
            }
        }
        return null;
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** The annotations {@code @Aggregate} can't share a field with, as written on {@code field}. */
    private static List<String> combinedWith(ModelField field) {
        var found = new ArrayList<String>();
        if (field.aggregate() == null) {
            return found;
        }
        if (field.primaryKey()) {
            found.add("@PrimaryKey");
        }
        if (field.element().getAnnotation(Column.class) != null) {
            found.add("@Column");
        }
        if (field.join() != null) {
            found.add("@Join");
        }
        if (field.element().getAnnotation(Transient.class) != null) {
            found.add("@Transient");
        }
        return found;
    }

    /** The qualified name of {@code type}'s class, or {@code ""} when it is no class. */
    private static String qualified(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED
                ? ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString() : "";
    }

    private static boolean isOneOf(TypeMirror type, Set<String> classes) {
        return classes.contains(qualified(type));
    }

    /** Whether {@code type} is, or extends or implements, the class named {@code name}, through its supertypes. */
    private boolean isSubtypeOf(TypeMirror type, String name) {
        if (qualified(type).equals(name)) {
            return true;
        }
        return types.directSupertypes(type).stream().anyMatch(supertype -> isSubtypeOf(supertype, name));
    }

    /** Whether {@code name} may be written where Java takes a simple name: an identifier that is no keyword. */
    private static boolean isSimpleName(String name) {
        return SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
    }

    /**
     * {@code MQ3015} for a join of a filter column, or of a collection of the root, whose constant is already
     * generated for something else, or whose alias can't start that constant's name.
     */
    private static void claimFilterTables(
            ModelDefinition model, FilterLayout filters, Map<String, String> constants, Diagnostics diagnostics) {
        for (FilterLayout.Table table : filters.tables()) {
            String where = model.name() + (table.typedBy() == null ? "" : " @FilterColumn(" + table.typedBy() + ")")
                    + ": ";
            if (!table.alias().isEmpty() && !isSimpleName(table.alias())) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3015, where + "alias '" + table.alias()
                        + "' can't start the name of its join's constant; use a Java identifier");
                continue;
            }
            String earlier = constants.putIfAbsent(table.constant(), table.description());
            if (earlier != null) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3015, where + "constant " + table.constant() + " of "
                        + table.description() + " is also generated for " + earlier
                        + "; set another @Join(prefix) or @FilterColumn(alias)");
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
        if (!isSimpleName(join.prefix())) {
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
        if (!nested.aggregates().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3005, where + "@Join model " + nested.name()
                    + " has @Aggregate fields; a summary model can't be joined");
            joinable = false;
        } else if (nested.keys().isEmpty()) {
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
        // An update model writes a to-one by id, so the field holds the target's id, not the target (R-GEN-19).
        if (model.updateModel() && attribute.kind() == EntityAttribute.Kind.TO_ONE && field.converter() == null) {
            return;
        }
        if (attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "model type " + display(field.type())
                    + ", entity attribute '" + attribute.name()
                    + "' is a collection, which a column can't select; filter on it with Filters.exists");
            return;
        }
        if (field.converter() != null) {
            if (!checkConverter(
                    model, field.element(), field.converter(), field.type(), attribute, where, diagnostics)) {
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
     * {@code MQ3014} unless {@code converterClass} is a {@code ColumnConverter} to the attribute's type that the
     * QModel can obtain, and one from {@code modelType} when a field holds the value; a filter column has no field,
     * so it passes {@code null} and takes the converter's model type (R-PROC-07, R-PROC-10).
     */
    private boolean checkConverter(
            ModelDefinition model, Element element, TypeMirror converterClass, TypeMirror modelType,
            EntityAttribute attribute, String where, Diagnostics diagnostics) {
        String name = display(converterClass);
        ConverterType converter = ConverterType.of(types, converterClass);
        if (converter == null) {
            diagnostics.error(element, DiagnosticCode.MQ3014, where + name + " is not a ColumnConverter<"
                    + (modelType == null ? "?" : display(boxed(modelType))) + ", "
                    + display(boxed(attribute.type())) + ">");
            return false;
        }
        boolean fits = true;
        var expected = new ArrayList<String>();
        if (modelType != null && !types.isSameType(converter.model(), boxed(modelType))) {
            expected.add("model type " + display(modelType));
        }
        if (!types.isSameType(converter.attribute(), boxed(attribute.type()))) {
            expected.add("entity attribute type " + display(attribute.type()));
        }
        if (!expected.isEmpty()) {
            diagnostics.error(element, DiagnosticCode.MQ3014, where + name + " converts "
                    + display(converter.model()) + " to " + display(converter.attribute()) + ", "
                    + String.join(", ", expected));
            fits = false;
        }
        if (!converter.hasInstance() && !converter.hasVisibleConstructor(model.type())) {
            diagnostics.error(element, DiagnosticCode.MQ3014, where + name
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
