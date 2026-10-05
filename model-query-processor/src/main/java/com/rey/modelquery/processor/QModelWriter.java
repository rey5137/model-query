package com.rey.modelquery.processor;

import com.rey.modelquery.processor.JoinedTable.JoinedColumn;
import com.rey.modelquery.processor.ModelDefinition.AggregateDefinition;
import com.rey.modelquery.processor.ModelDefinition.JoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Emits the QModel source of a validated {@link ModelDefinition} (spec processor/31 §1). The engine's types are named,
 * not referenced, so the processor does not depend on {@code model-query-core}.
 *
 * @implSpec R-GEN-04, R-GEN-05, R-GEN-06, R-GEN-09, R-GEN-10, R-GEN-12, R-GEN-13, R-GEN-14, R-GEN-15, R-GEN-17,
 *     R-GEN-18, R-GEN-19, R-GEN-21, R-GEN-22, R-GEN-24, R-GEN-27, R-PROC-07, R-PROC-09, R-PROC-10, R-PROC-11,
 *     R-PROC-12, R-PROC-13, R-PROC-15, R-PROC-16, R-PROC-17, R-PROC-21, R-PROC-22
 */
final class QModelWriter {

    private static final String CORE = "com.rey.modelquery.core";
    private static final ClassName TABLE_FIELD = ClassName.get(CORE, "TableField");
    private static final ClassName COLUMN_FIELD = ClassName.get(CORE, "ColumnField");
    private static final ClassName ORDERED_COLUMN_FIELD = ClassName.get(CORE, "OrderedColumnField");
    private static final ClassName AGG = ClassName.get(CORE, "Agg");
    private static final ClassName AGGREGATE_FIELD = ClassName.get(CORE, "AggregateField");
    private static final ClassName EXPRESSION_FIELD = ClassName.get(CORE, "ExpressionField");
    private static final ClassName SELECT_SET = ClassName.get(CORE, "SelectSet");
    private static final ClassName PRIMARY_KEY = ClassName.get(CORE, "PrimaryKey");
    private static final ClassName ROW_MAPPER = ClassName.get(CORE, "RowMapper");
    private static final ClassName ROW = ClassName.get(CORE, "Row");
    private static final ClassName MODEL_QUERY = ClassName.get(CORE, "ModelQuery");
    private static final ClassName MODEL_UPDATE = ClassName.get(CORE, "ModelUpdate");
    private static final ClassName MODEL_DELETE = ClassName.get(CORE, "ModelDelete");
    private static final ClassName CHANGES = ClassName.get(CORE, "Changes");
    private static final ClassName INCUBATING = ClassName.get("com.rey.modelquery.annotations", "Incubating");
    private static final ClassName JOIN_TYPE = ClassName.get("jakarta.persistence.criteria", "JoinType");
    private static final ClassName OPTIONAL = ClassName.get(Optional.class);
    private static final Modifier[] CONSTANT = {Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL};

    private final Types types;
    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;
    private final BuiltInConverters builtIns;
    private final FetchFieldWriter fetchFields;

    QModelWriter(Types types, EntityMetamodel metamodel, NestedModels nestedModels, BuiltInConverters builtIns) {
        this.types = types;
        this.metamodel = metamodel;
        this.nestedModels = nestedModels;
        this.builtIns = builtIns;
        this.fetchFields = new FetchFieldWriter(types, metamodel, nestedModels);
    }

    /**
     * The QModel file of {@code model}, whose only originating element is the model's type (R-GEN-05). An update
     * model's has its columns and write methods only: it is never read into (R-GEN-19).
     */
    JavaFile write(ModelDefinition model) {
        boolean update = model.updateModel();
        ClassName modelName = ClassName.get(model.type());
        ClassName entity = ClassName.get(model.root());
        ClassName generated = ClassName.get(modelName.packageName(), model.generatedName());
        List<ModelField> keys = model.keys();
        // A composite key reads as the list of its column values (PrimaryKey.composite).
        TypeName keyType = keys.size() == 1
                ? column(keys.get(0).type()) : ParameterizedTypeName.get(List.class, Object.class);
        // A whole-table aggregate has no row identity, so its query() sets no key (R-GEN-18).
        boolean keyed = !keys.isEmpty() && !model.singleGroup();
        List<ModelField> groupKeys = update ? List.of() : model.groupKeys();
        boolean grouped = !groupKeys.isEmpty() && !model.singleGroup();

        TypeSpec.Builder type = TypeSpec.classBuilder(generated)
                .addOriginatingElement(model.type())
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", ModelQueryProcessor.class.getCanonicalName())
                        .build())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(TABLE_FIELD, entity, entity), "ROOT", CONSTANT)
                        .initializer("$T.root($T.class)", TABLE_FIELD, entity)
                        .build());
        List<JoinedTable> joined = update ? List.of() : nestedModels.tables(model);
        for (JoinedTable table : joined) {
            type.addField(joinedTable(table));
        }
        FilterLayout filters = FilterLayout.of(model, joined, metamodel);
        for (FilterLayout.Table table : filters.tables()) {
            type.addField(filterTable(table));
        }
        // A column is named after its field, so a sort names it as the model does (D-55).
        for (ModelField field : model.columns()) {
            type.addField(column(
                    modelName, model.root(), field.constant(), "ROOT", field.attribute(), field.type(),
                    converter(model, field), field.name()));
        }
        for (JoinedTable table : joined) {
            ClassName nested = generatedName(table.nested());
            for (JoinedColumn column : table.columns()) {
                // Typed as the nested QModel types it, OrderedColumnField when its converter is ordered (D-93).
                ClassName kind = ConverterType.ordered(types, converter(column.owner(), column.field()))
                        ? ORDERED_COLUMN_FIELD : COLUMN_FIELD;
                type.addField(FieldSpec.builder(ParameterizedTypeName.get(kind, modelName,
                                ClassName.get(table.entity()), column(column.type())), column.constant(), CONSTANT)
                        .initializer("$T.$L.withTable($T.class,$W$L)",
                                nested, table.inNested(column.constant()), modelName, table.table())
                        .build());
            }
        }
        // A filter-only column has no field to map: it is in no SelectSet and not in map(Row) (R-PROC-10).
        for (FilterLayout.Column column : filters.columns()) {
            type.addField(column(
                    modelName, column.entity(), column.definition().name(), column.table(), column.attribute(),
                    column.read().type(), column.definition().converter(), null));
        }
        // A computed constant comes after every column constant, so a definition reading Q<Model> columns finds them
        // already set when the generated class initialises it (R-GEN-27).
        for (ModelField field : update ? List.<ModelField>of() : model.computed()) {
            type.addField(computed(modelName, field));
        }
        // Aggregates come last: an aggregate over an expression builds it during the initialiser, and the expression
        // may read any constant of the model declared earlier in this order (R-GEN-27, R-PROC-22). They are in no
        // column set, or every query of the model would be grouped (R-PROC-17).
        for (ModelField field : update ? List.<ModelField>of() : model.aggregates()) {
            type.addField(aggregate(modelName, model, field));
        }
        if (model.selectSets()) {
            TypeName selectSet = ParameterizedTypeName.get(SELECT_SET, modelName);
            List<ModelField> selections = model.selections();
            List<ModelField> excluded = selections.stream().filter(ModelField::excludedFromDefaults).toList();
            type.addField(FieldSpec.builder(selectSet, "ALL", CONSTANT)
                    .initializer("$T.of($L)", SELECT_SET, constants(selections))
                    .build());
            type.addField(FieldSpec.builder(selectSet, "DEFAULT", CONSTANT)
                    .initializer(excluded.isEmpty() ? CodeBlock.of("ALL") : CodeBlock.of("ALL.without($L)",
                            constants(excluded)))
                    .build());
            // Derived from the nested model's own fields, so a column added there is selected here too (R-GEN-04).
            for (JoinedTable table : joined) {
                type.addField(FieldSpec.builder(selectSet, table.prefix(), CONSTANT)
                        .initializer("$T.of($L)", SELECT_SET, table.columns().stream()
                                .map(column -> CodeBlock.of("$L", column.constant()))
                                .collect(CodeBlock.joining(",$W")))
                        .build());
            }
        }
        if (!groupKeys.isEmpty()) {
            type.addField(FieldSpec.builder(ParameterizedTypeName.get(SELECT_SET, modelName), "GROUP_KEYS", CONSTANT)
                    .initializer("$T.of($L)", SELECT_SET, constants(groupKeys))
                    .build());
        }
        // A summary model may have no key: a group has none (R-AGG-09). An update model names its key in place.
        if (!keys.isEmpty() && !update) {
            type.addField(FieldSpec.builder(ParameterizedTypeName.get(PRIMARY_KEY, modelName, keyType), "KEY", CONSTANT)
                    .initializer(keys.size() == 1 ? "$T.of($L)" : "$T.composite($L)", PRIMARY_KEY, constants(keys))
                    .build());
        }
        // What a fetch plan names: the model's own @Joins and its @Child fields (R-FCH-03, R-FCH-07).
        for (ModelField join : update ? List.<ModelField>of() : model.joins()) {
            type.addField(fetchFields.joinField(model, join));
        }
        for (ModelField child : update ? List.<ModelField>of() : model.children()) {
            type.addField(fetchFields.childField(model, child));
        }
        if (update) {
            writes(model, modelName, entity, keyType, type);
            type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
            return file(generated, type);
        }
        type.addField(FieldSpec.builder(ParameterizedTypeName.get(ROW_MAPPER, modelName), "MAPPER", CONSTANT)
                .initializer("$T::map", generated)
                .build());
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        type.addMethod(MethodSpec.methodBuilder("query")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(
                        MODEL_QUERY.nestedClass("Builder"), entity, keyed ? keyType : TypeName.OBJECT, modelName))
                .addStatement("return $T.builder(ROOT, MAPPER)$L$L", MODEL_QUERY,
                        keyed ? ".primaryKey(KEY)" : "", grouped ? ".groupBy(GROUP_KEYS)" : "")
                .build());
        MethodSpec.Builder map = MethodSpec.methodBuilder("map")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(modelName)
                .addParameter(ROW, "row");
        if (model.isRecord()) {
            mapRecord(model, modelName, map);
        } else {
            mapClass(model, modelName, map);
        }
        type.addMethod(map.build());
        if (keyed) {
            writes(model, modelName, entity, keyType, type);
        }
        return file(generated, type);
    }

    private static JavaFile file(ClassName generated, TypeSpec.Builder type) {
        return JavaFile.builder(generated.packageName(), type.build())
                .indent("    ")
                .skipJavaLangImports(true)
                .build();
    }

    /**
     * {@code changes()} and {@code update(changes)} when the model has a change set, and {@code delete()} for an
     * update model and for a query model keyed by its root entity's id, since a delete writes no columns (R-GEN-21,
     * R-GEN-22). All three are {@code @Incubating}, since the bulk-write API they return is (D-85).
     */
    private void writes(
            ModelDefinition model, ClassName modelName, ClassName entity, TypeName keyType, TypeSpec.Builder type) {
        List<ModelField> keys = model.keys();
        // A query model's KEY is there to reuse; an update model names its key in place (R-GEN-19).
        CodeBlock key = !model.updateModel() ? CodeBlock.of("KEY")
                : CodeBlock.of(keys.size() == 1 ? "$T.of($L)" : "$T.composite($L)", PRIMARY_KEY, constants(keys));
        if (model.changes()) {
            ClassName changes = ClassName.get(modelName.packageName(), model.changesName());
            type.addMethod(MethodSpec.methodBuilder("changes")
                    .addAnnotation(INCUBATING)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(changes)
                    .addStatement("return new $T()", changes)
                    .build());
            type.addMethod(MethodSpec.methodBuilder("update")
                    .addAnnotation(INCUBATING)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ParameterizedTypeName.get(MODEL_UPDATE.nestedClass("Builder"), entity, keyType, modelName))
                    .addParameter(ParameterizedTypeName.get(CHANGES, modelName), "changes")
                    .addStatement("return $T.builder(ROOT).primaryKey($L).set(changes)", MODEL_UPDATE, key)
                    .build());
        }
        Set<String> keyAttributes = keys.stream().map(ModelField::attribute).collect(Collectors.toSet());
        if (model.updateModel() || metamodel.id(model.root()).is(keyAttributes)) {
            type.addMethod(MethodSpec.methodBuilder("delete")
                    .addAnnotation(INCUBATING)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ParameterizedTypeName.get(MODEL_DELETE.nestedClass("Builder"), entity, keyType, modelName))
                    .addStatement("return $T.builder(ROOT).primaryKey($L)", MODEL_DELETE, key)
                    .build());
        }
    }

    /**
     * A column constant reading {@code attribute} on {@code table}, named {@code property} unless it is {@code null}.
     * Its type is {@code type}, or what {@code converterClass} makes of the attribute when {@code type} is the
     * attribute's own, as a filter column's is.
     */
    private FieldSpec column(
            ClassName modelName, TypeElement entity, String constant, String table, String attribute,
            TypeMirror type, TypeMirror converterClass, String property) {
        CodeBlock named = property == null ? CodeBlock.of("") : CodeBlock.of("$Z.named($S)", property);
        ConverterType converter = converterClass == null ? null : ConverterType.of(types, converterClass);
        TypeName column = column(converter == null ? type : converter.model());
        // Ordered with no converter or an ordered one, the only kind Agg.min, max and countDistinct take (D-93).
        ClassName kind = ConverterType.ordered(types, converterClass) ? ORDERED_COLUMN_FIELD : COLUMN_FIELD;
        FieldSpec.Builder field = FieldSpec.builder(
                ParameterizedTypeName.get(kind, modelName, ClassName.get(entity), column), constant, CONSTANT);
        if (column instanceof ParameterizedTypeName
                || converter != null && column(converter.attribute()) instanceof ParameterizedTypeName) {
            field.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build());
        }
        if (converter == null) {
            return field.initializer("$T.of($T.class,$W$L,$W$S,$W$L)$L",
                    COLUMN_FIELD, modelName, table, attribute, classOf(column), named).build();
        }
        // The column carries its converter, so Row.get and a filter both convert (R-PROC-07, D-37).
        ClassName converterName = ClassName.get(converter.type());
        return field.initializer("$T.of($T.class,$W$L,$W$S,$W$L,$W$L,$W$L)$L", COLUMN_FIELD, modelName, table,
                attribute, classOf(column), classOf(column(converter.attribute())),
                converter.hasInstance()
                        ? CodeBlock.of("$T.INSTANCE", converterName) : CodeBlock.of("new $T()", converterName),
                named)
                .build();
    }

    /**
     * The converter {@code field}'s column carries: the one {@code @Column} names, else the built-in one between the
     * field's type and its attribute's, else {@code null} (R-PROC-07, D-84).
     */
    private TypeMirror converter(ModelDefinition model, ModelField field) {
        if (field.converter() != null) {
            return field.converter();
        }
        var attribute = metamodel.resolve(model.root(), field.attribute()).attribute();
        return attribute == null ? null : builtIns.between(field.type(), attribute.type());
    }

    /**
     * The constant of a {@code @Computed} field: an {@code ExpressionField} built from the class the field names,
     * named after the field (R-GEN-27, R-PROC-21).
     */
    private FieldSpec computed(ClassName modelName, ModelField field) {
        ExpressionDefinitionType definition = ExpressionDefinitionType.of(types, field.definition());
        return FieldSpec.builder(
                        ParameterizedTypeName.get(EXPRESSION_FIELD, modelName, column(field.type())),
                        field.constant(), CONSTANT)
                .initializer("$L.named($S)", definitionExpression(definition), field.name())
                .build();
    }

    /** The expression of {@code definition}, from its {@code INSTANCE} or a new instance (R-GEN-27). */
    private static CodeBlock definitionExpression(ExpressionDefinitionType definition) {
        ClassName type = ClassName.get(definition.type());
        return definition.hasInstance()
                ? CodeBlock.of("$T.INSTANCE.expression()", type)
                : CodeBlock.of("new $T().expression()", type);
    }

    /**
     * The constant of an {@code @Aggregate} field, over a column built in place from its attribute: the aggregate is
     * keyed by that column, so it equals the same function written by hand (api/13 R-AGG-01).
     */
    private FieldSpec aggregate(ClassName modelName, ModelDefinition model, ModelField field) {
        AggregateDefinition aggregate = field.aggregate();
        TypeName result = column(field.type());
        FieldSpec.Builder constant = FieldSpec.builder(
                ParameterizedTypeName.get(AGGREGATE_FIELD, modelName, result), field.constant(), CONSTANT);
        if (aggregate.expression() != null) {
            // The aggregate is the Agg overload over the definition's expression (R-PROC-22, R-GEN-27).
            ExpressionDefinitionType definition = ExpressionDefinitionType.of(types, aggregate.expression());
            TypeMirror sourceType = definition.value();
            String function = aggregateFunction(aggregate, sourceType);
            return constant.initializer("$T.$L($L)", AGG, function, definitionExpression(definition)).build();
        }
        if (aggregate.attribute().isEmpty() && aggregate.fn().equals("COUNT")) {
            return constant.initializer("$T.count(ROOT)", AGG).build();
        }
        TypeMirror attributeType = metamodel.resolve(model.root(), aggregate.attribute()).attribute().type();
        // MIN or MAX typed as the field reads its Timestamp attribute through the built-in converter (D-84).
        TypeMirror converter = builtIns.between(field.type(), attributeType);
        CodeBlock source = converter == null
                ? CodeBlock.of("$T.of($T.class,$WROOT,$W$S,$W$L)", COLUMN_FIELD, modelName,
                        aggregate.attribute(), classOf(column(attributeType)))
                : CodeBlock.of("$T.of($T.class,$WROOT,$W$S,$W$L,$W$L,$W$T.INSTANCE)", COLUMN_FIELD, modelName,
                        aggregate.attribute(), classOf(result), classOf(column(attributeType)),
                        ClassName.get((TypeElement) types.asElement(converter)));
        String function = aggregate.fn().equals("COUNT") && !aggregate.distinct()
                ? null
                : aggregateFunction(aggregate, attributeType);
        if (function == null) {
            // Agg has no count(column), so it counts the column's non-null values as an expression of its own.
            return constant.initializer("$T.of($S, $T.class,$W(ctx, cb) -> cb.count($Z$L.path(ctx)))",
                    AGG, field.constant(), Long.class, source).build();
        }
        return constant.initializer("$T.$L($Z$L)", AGG, function, source).build();
    }

    /**
     * The {@code Agg} factory for {@code aggregate}'s function over a column of {@code sourceType}: {@code COUNT}
     * counts, {@code SUM} sums, and any other function is its name lower-cased.
     */
    private String aggregateFunction(AggregateDefinition aggregate, TypeMirror sourceType) {
        return switch (aggregate.fn()) {
            case "COUNT" -> aggregate.distinct() ? "countDistinct" : "count";
            // Agg.sum refuses a 32-bit expression; the field is a Long and the QModel sums it as one (MQ3205).
            case "SUM" -> column(sourceType).toString().matches("java\\.lang\\.(Integer|Short|Byte)")
                    ? "sumAsLong" : "sum";
            default -> aggregate.fn().toLowerCase(Locale.ROOT);
        };
    }

    /** A join no {@code @Join} declares: of a filter column's path, or of a collection of the root (R-PROC-13). */
    private static FieldSpec filterTable(FilterLayout.Table table) {
        ClassName parentEntity = ClassName.get(table.parentEntity());
        ClassName entity = ClassName.get(table.entity());
        CodeBlock.Builder initializer = CodeBlock.builder().add("$T.<$T, $T>join($L,$W$S,$W$T.$L)",
                TABLE_FIELD, parentEntity, entity, table.parent(), table.attribute(), JOIN_TYPE, table.joinType());
        if (!table.alias().isEmpty()) {
            initializer.add("$Z.as($S)", table.alias());
        }
        return FieldSpec.builder(
                        ParameterizedTypeName.get(TABLE_FIELD, parentEntity, entity), table.constant(), CONSTANT)
                .initializer(initializer.build())
                .build();
    }

    /** Passes every component to the canonical constructor in order; an unselected column reads as null (R-GEN-06). */
    private void mapRecord(ModelDefinition model, ClassName modelName, MethodSpec.Builder map) {
        scopeJoins(model, map);
        CodeBlock arguments = model.fields().stream()
                .map(field -> field.join() != null ? nested(field) : field.child() != null ? unloaded(field)
                        : field.column() || field.computed() || field.aggregate() != null
                        ? CodeBlock.of("row.get($L)", field.constant()) : CodeBlock.of(unset(field.type())))
                .collect(CodeBlock.joining(",$W"));
        map.addStatement("return new $T($L)", modelName, arguments);
    }

    /**
     * Calls a setter only for a selected column, so a field initialiser survives for the others (R-GEN-09), and
     * always for a {@code @Join} field, which is never left {@code null} (R-GEN-15), and a {@code @Child} field.
     */
    private void mapClass(ModelDefinition model, ClassName modelName, MethodSpec.Builder map) {
        map.addStatement("$T m = new $T()", modelName, modelName);
        for (ModelField field : model.fields()) {
            if (!field.column() && !field.computed() && field.aggregate() == null) {
                continue;
            }
            map.beginControlFlow("if (row.isSelected($L))", field.constant())
                    .addStatement("m.$L(row.get($L))", setter(field), field.constant())
                    .endControlFlow();
        }
        scopeJoins(model, map);
        for (ModelField field : model.joins()) {
            map.addStatement("m.$L($L)", setter(field), nested(field));
        }
        for (ModelField field : model.children()) {
            map.addStatement("m.$L($L)", setter(field), unloaded(field));
        }
        map.addStatement("return m");
    }

    /** A {@code @Child} field until a fetch plan loads it: an empty {@code List} or {@code Optional} (R-FCH-03). */
    private static CodeBlock unloaded(ModelField field) {
        return field.child().toMany() ? CodeBlock.of("$T.of()", List.class) : CodeBlock.of("$T.empty()", OPTIONAL);
    }

    /** Declares, for each join, the view of the row its nested model's own columns are read through. */
    private static void scopeJoins(ModelDefinition model, MethodSpec.Builder map) {
        for (ModelField field : model.joins()) {
            map.addStatement("$T $L = row.scoped($L_TABLE)", ROW, scope(field), field.join().prefix());
        }
    }

    /** The local holding a join's scoped row: the field's name, unless the mapper already uses that name. */
    private static String scope(ModelField field) {
        return field.name().equals("row") || field.name().equals("m") ? field.name() + "Row" : field.name();
    }

    /**
     * The value of a {@code @Join} field: the nested model when its key was read and any component of it is not
     * null, empty otherwise, which covers a LEFT-join miss and a join none of whose columns were selected
     * (R-GEN-12, R-GEN-13).
     */
    private CodeBlock nested(ModelField field) {
        ModelDefinition nested = nestedModels.of(field);
        ClassName qModel = generatedName(nested);
        CodeBlock absent = nested.keys().stream()
                .map(key -> CodeBlock.of("$L.get($T.$L) == null", scope(field), qModel, key.constant()))
                .collect(CodeBlock.joining("$W&& "));
        return CodeBlock.of("$L$W? $T.empty()$W: $T.of($T.MAPPER.map($L))",
                absent, OPTIONAL, OPTIONAL, qModel, scope(field));
    }

    /**
     * A {@code @Join} of the model itself, or a join of its nested model moved under one, which keeps the property
     * the nested model's QModel names it by (R-GEN-13, R-GEN-14).
     */
    private static FieldSpec joinedTable(JoinedTable table) {
        ClassName nested = generatedName(table.nested());
        ClassName parentEntity = ClassName.get(table.parentEntity());
        ClassName entity = ClassName.get(table.entity());
        FieldSpec.Builder constant = FieldSpec.builder(
                ParameterizedTypeName.get(TABLE_FIELD, parentEntity, entity), table.table(), CONSTANT);
        if (table.parent() != null) {
            return constant.initializer("$T.$L.withParent($L_TABLE)",
                    nested, table.inNested(table.table()), table.parent()).build();
        }
        JoinDefinition join = table.join().join();
        CodeBlock.Builder initializer = CodeBlock.builder().add("$T.<$T, $T>join(ROOT,$W$S,$W$T.$L)",
                TABLE_FIELD, parentEntity, entity, join.attribute(), JOIN_TYPE, join.type());
        if (!join.alias().isEmpty()) {
            initializer.add("$Z.as($S)", join.alias());
        }
        // The nested model's key decides whether the join matched a row (D-38), and the join is named after its
        // field, so a sort names a column under it as the model does (D-55).
        return constant.initializer(initializer.add("$Z.presentBy($T.KEY)", nested)
                .add("$Z.named($S)", table.join().name())
                .build()).build();
    }

    static ClassName generatedName(ModelDefinition model) {
        return ClassName.get(ClassName.get(model.type()).packageName(), model.generatedName());
    }

    /**
     * The setter as Lombok names it, so generated and hand-written setters both resolve: a primitive
     * {@code boolean isX} gets {@code setX}, anything else {@code set} and the capitalised field name (R-GEN-10).
     */
    static String setter(ModelField field) {
        String name = field.name();
        if (field.type().getKind() == TypeKind.BOOLEAN && name.length() > 2 && name.startsWith("is")
                && !Character.isLowerCase(name.charAt(2))) {
            name = name.substring(2);
        }
        return "set" + ChangesWriter.capitalized(name);
    }

    /** What a record component that is not a column is constructed with: its type's default value. */
    private static String unset(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "false";
            case BYTE, SHORT, CHAR -> "(" + type.getKind().name().toLowerCase(Locale.ROOT) + ") 0";
            case INT, LONG, FLOAT, DOUBLE -> "0";
            default -> "null";
        };
    }

    /** A column's value type: {@code type}, boxed, since a column that is not selected reads as null. */
    private TypeName column(TypeMirror type) {
        return TypeName.get(ProcessorTypes.boxed(types, type));
    }

    /** The class literal of {@code type}. */
    static CodeBlock classOf(TypeName type) {
        if (type instanceof ParameterizedTypeName parameterized) {
            // A parameterized type has no class literal: its raw class stands in, as a hand-written column's would.
            return CodeBlock.of("($T) ($T) $T.class", ParameterizedTypeName.get(ClassName.get(Class.class), type),
                    ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
                    parameterized.rawType);
        }
        return CodeBlock.of("$T.class", type);
    }

    private static CodeBlock constants(List<ModelField> fields) {
        // Each name is an argument, never part of the format: a field name may hold a '$'.
        return fields.stream().map(field -> CodeBlock.of("$L", field.constant())).collect(CodeBlock.joining(",$W"));
    }
}
