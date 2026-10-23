package com.rey.modelquery.processor;

import com.rey.modelquery.processor.JoinedTable.JoinedColumn;
import com.rey.modelquery.processor.ModelDefinition.AggregateDefinition;
import com.rey.modelquery.processor.ModelDefinition.AggregateOrder;
import com.rey.modelquery.processor.ModelDefinition.JoinOn;
import com.rey.modelquery.processor.ModelDefinition.JoinSourceDefinition;
import com.rey.modelquery.processor.ModelDefinition.JoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import com.rey.modelquery.processor.ModelDefinition.WindowDefinition;
import com.rey.modelquery.processor.ModelDefinition.WindowOrder;
import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import java.util.ArrayList;
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
 *     R-PROC-12, R-PROC-13, R-PROC-15, R-PROC-16, R-PROC-17, R-PROC-21, R-PROC-22, R-PROC-23, R-PROC-25, R-GEN-28,
 *     R-GEN-29, R-GEN-30
 */
final class QModelWriter {

    /** The name of the private set of every field the mapper reads, which a {@code @Selected} field is filled from. */
    static final String SELECTED_FIELDS = "SELECTED_FIELDS";

    private static final String CORE = "com.rey.modelquery.core";
    private static final ClassName TABLE_FIELD = ClassName.get(CORE, "TableField");
    private static final ClassName COLUMN_FIELD = ClassName.get(CORE, "ColumnField");
    private static final ClassName ORDERED_COLUMN_FIELD = ClassName.get(CORE, "OrderedColumnField");
    private static final ClassName AGG = ClassName.get(CORE, "Agg");
    private static final ClassName AGGREGATE_FIELD = ClassName.get(CORE, "AggregateField");
    private static final ClassName WINDOW_FIELD = ClassName.get(CORE, "WindowField");
    private static final ClassName WINDOW_FUNCTION = ClassName.get("com.rey.modelquery.annotations", "WindowFunction");
    private static final ClassName WINDOW_FRAME = ClassName.get("com.rey.modelquery.annotations", "WindowFrame");
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName EXPRESSION_FIELD = ClassName.get(CORE, "ExpressionField");
    private static final ClassName SELECT_SET = ClassName.get(CORE, "SelectSet");
    private static final ClassName FIELD_INDEX = ClassName.get(CORE, "FieldIndex");
    private static final ClassName PRIMARY_KEY = ClassName.get(CORE, "PrimaryKey");
    private static final ClassName ROW_MAPPER = ClassName.get(CORE, "RowMapper");
    private static final ClassName ROW = ClassName.get(CORE, "Row");
    private static final ClassName MODEL_QUERY = ClassName.get(CORE, "ModelQuery");
    private static final ClassName SOURCE_QUERY = ClassName.get(CORE, "SourceQuery");
    private static final ClassName SOURCE_JOIN = ClassName.get(CORE, "SourceJoin");
    private static final ClassName JOIN_KIND = ClassName.get("com.rey.modelquery.annotations", "JoinKind");
    private static final ClassName MODEL_UPDATE = ClassName.get(CORE, "ModelUpdate");
    private static final ClassName MODEL_DELETE = ClassName.get(CORE, "ModelDelete");
    private static final ClassName CHANGES = ClassName.get(CORE, "Changes");
    private static final ClassName INSERT_COLUMNS = ClassName.get(CORE, "InsertColumns");
    private static final ClassName MODEL_INSERT = ClassName.get(CORE, "ModelInsert");
    private static final ClassName VALUES_INSERT = ClassName.get(CORE, "ValuesInsert");
    private static final ClassName MODEL_PERSIST = ClassName.get(CORE, "ModelPersist");
    private static final ClassName INCUBATING = ClassName.get("com.rey.modelquery.annotations", "Incubating");
    private static final ClassName JOIN_TYPE = ClassName.get("jakarta.persistence.criteria", "JoinType");
    private static final ClassName OPTIONAL = ClassName.get(Optional.class);
    private static final Modifier[] CONSTANT = {Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL};

    private final Types types;
    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;
    private final BuiltInConverters builtIns;
    private final FetchFieldWriter fetchFields;
    private final Nullness nullness;

    QModelWriter(
            Types types, EntityMetamodel metamodel, NestedModels nestedModels, BuiltInConverters builtIns,
            Nullness nullness) {
        this.nullness = nullness;
        this.types = types;
        this.metamodel = metamodel;
        this.nestedModels = nestedModels;
        this.builtIns = builtIns;
        this.fetchFields = new FetchFieldWriter(types, metamodel, nestedModels, nullness);
    }

    /**
     * The QModel file of {@code model}, whose only originating element is the model's type, or its binding, in whose
     * package it is generated (R-GEN-05, R-GEN-35). An update or insert model's has its columns and write methods
     * only: it is never read into (R-GEN-19, R-GEN-28).
     */
    JavaFile write(ModelDefinition model) {
        // An update or insert model's QModel has its columns and write methods only: it is never read into.
        boolean writeOnly = !model.readModel();
        ClassName modelName = ClassName.get(model.type());
        // A model rooted at a source reads the source: its ROOT is a source root over the model the source reads,
        // not an entity (R-GEN-37).
        ClassName entity = ClassName.get(model.readsSource() ? model.source().type() : model.root());
        ClassName generated = generatedName(model);
        List<ModelField> keys = model.keys();
        // A composite key reads as the list of its column values (PrimaryKey.composite).
        TypeName keyType = keys.size() == 1
                ? column(keys.get(0).type()) : ParameterizedTypeName.get(List.class, Object.class);
        // A whole-table aggregate has no row identity, so its query() sets no key (R-GEN-18).
        boolean keyed = !keys.isEmpty() && !model.singleGroup();
        List<ModelField> groupKeys = writeOnly ? List.of() : model.groupKeys();
        boolean grouped = !groupKeys.isEmpty() && !model.singleGroup();

        TypeSpec.Builder type = TypeSpec.classBuilder(generated)
                .addOriginatingElement(model.origin())
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", ModelQueryProcessor.class.getCanonicalName())
                        .build())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(TABLE_FIELD, entity, entity), "ROOT", CONSTANT)
                        .initializer(model.readsSource() ? "$T.source($T.class)" : "$T.root($T.class)", TABLE_FIELD,
                                entity)
                        .build());
        nullness.marked(type);
        for (TypeElement subtype : TreatTables.targets(model)) {
            type.addField(FieldSpec.builder(ParameterizedTypeName.get(TABLE_FIELD, entity, ClassName.get(subtype)),
                            TreatTables.constant(subtype), CONSTANT)
                    .initializer("ROOT.treat($T.class)", ClassName.get(subtype)).build());
        }
        List<JoinedTable> joined = writeOnly ? List.of() : nestedModels.tables(model);
        for (JoinedTable table : joined) {
            type.addField(joinedTable(table));
        }
        FilterLayout filters = FilterLayout.of(model, joined, metamodel);
        for (FilterLayout.Table table : filters.tables()) {
            type.addField(filterTable(table));
        }
        // A column is named after its field, so a sort names it as the model does (D-55).
        for (ModelField field : model.columns()) {
            if (field.sourceColumn() != null) {
                continue;
            }
            type.addField(field.subtype() ? subtype(modelName, field.constant(), "ROOT", field.name(), field)
                    : model.readsSource()
                    ? overColumn(modelName, model, field)
                    : column(modelName, EntityMetamodel.treatedRoot(model.root(), field.treat()), field.constant(),
                            TreatTables.root(field.treat()), field.attribute(), field.type(), converter(model, field),
                            field.name()));
        }
        // A joined source's table comes before the columns read through it (R-SRC-21).
        for (JoinSourceDefinition join : model.joinSources()) {
            type.addField(sourceJoin(modelName, model, join));
        }
        for (ModelField field : model.columns()) {
            if (field.sourceColumn() != null) {
                type.addField(joinedColumn(modelName, model, field));
            }
        }
        for (JoinedTable table : joined) {
            ClassName nested = generatedName(table.nested());
            for (JoinedColumn column : table.columns()) {
                ModelField field = column.field();
                if (field.subtype()) {
                    type.addField(subtype(modelName, column.constant(), table.table(), column.path(), field));
                    continue;
                }
                // Typed as the nested QModel types it, OrderedColumnField when its converter is ordered (D-93).
                ClassName kind = ConverterType.ordered(types, converter(column.owner(), field))
                        ? ORDERED_COLUMN_FIELD : COLUMN_FIELD;
                CodeBlock columnTable = field.treat() == null ? CodeBlock.of("$L", table.table())
                        : CodeBlock.of("$T.$L.withParent($L)", generatedName(column.owner()),
                                TreatTables.root(field.treat()), table.table());
                type.addField(FieldSpec.builder(ParameterizedTypeName.get(kind, modelName,
                                ClassName.get(EntityMetamodel.treatedRoot(table.entity(), field.treat())),
                                column(column.type())), column.constant(), CONSTANT)
                        .initializer("$T.$L.withTable($T.class,$W$L)",
                                nested, table.inNested(column.constant()), modelName, columnTable)
                        .build());
            }
        }
        // A filter-only column has no field to map: it is in no SelectSet and not in map(Row) (R-PROC-10).
        for (FilterLayout.Column column : filters.columns()) {
            type.addField(model.readsSource()
                    ? overColumn(modelName, model, column.definition().name(), column.attribute(), false)
                    : column(modelName, column.entity(), column.definition().name(), column.table(), column.attribute(),
                            column.read().type(), column.definition().converter(), null));
        }
        // A computed constant comes after every column constant, so a definition reading Q<Model> columns finds them
        // already set when the generated class initialises it (R-GEN-27).
        for (ModelField field : writeOnly ? List.<ModelField>of() : model.computed()) {
            type.addField(computed(modelName, field));
        }
        // Aggregates come last: an aggregate over an expression builds it during the initialiser, and the expression
        // may read any constant of the model declared earlier in this order (R-GEN-27, R-PROC-22). They are in no
        // column set, or every query of the model would be grouped (R-PROC-17).
        for (ModelField field : writeOnly ? List.<ModelField>of() : model.aggregates()) {
            type.addField(aggregate(modelName, model, field));
        }
        // Window fields come after the aggregates, since a window term may reference an aggregate constant (R-GEN-38).
        for (ModelField field : writeOnly ? List.<ModelField>of() : model.windows()) {
            type.addField(window(modelName, model, field));
        }
        if (model.selectSets()) {
            TypeName selectSet = ParameterizedTypeName.get(SELECT_SET, modelName);
            List<ModelField> selections = model.sourceModel() ? model.sourceFields() : model.selections();
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
        // A summary model may have no key: a group has none (R-AGG-09). A write-only model names its key in place.
        if (!keys.isEmpty() && !writeOnly) {
            type.addField(FieldSpec.builder(ParameterizedTypeName.get(PRIMARY_KEY, modelName, keyType), "KEY", CONSTANT)
                    .initializer(keys.size() == 1 ? "$T.of($L)" : "$T.composite($L)", PRIMARY_KEY, constants(keys))
                    .build());
        }
        // What a fetch plan names: the model's own @Joins and its @Child fields (R-FCH-03, R-FCH-07).
        for (ModelField join : writeOnly ? List.<ModelField>of() : model.joins()) {
            type.addField(fetchFields.joinField(model, join));
        }
        for (ModelField child : writeOnly ? List.<ModelField>of() : model.children()) {
            type.addField(fetchFields.childField(model, child));
        }
        // The set a @Selected field is filled from is declared after every other constant: it holds them, so a class
        // initialiser that reached it earlier would read them null (R-GEN-29).
        if (model.hasSelected()) {
            type.addField(selectedFields(modelName, model, joined));
        }
        if (writeOnly) {
            if (model.insertModel()) {
                inserts(model, modelName, entity, type);
            } else {
                writes(model, modelName, entity, keyType, type);
            }
            type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
            return file(generated, type);
        }
        if (model.sourceModel()) {
            type.addMethod(source(model, modelName, entity, grouped));
            if (!model.branch()) {
                type.addType(rows(model, modelName, generated, keyType));
                type.addMethod(sourceQuery(modelName, keyed, keyType));
            }
            type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
            fieldIndex(model, modelName, generated, joined, FilterIndex.of(model, joined, filters), type);
            return file(generated, type);
        }
        type.addField(FieldSpec.builder(ParameterizedTypeName.get(ROW_MAPPER, modelName), "MAPPER", CONSTANT)
                .initializer("$T::map", generated)
                .build());
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        type.addMethod(query(model, modelName, entity, keyed, keyType, grouped));
        MethodSpec.Builder map = MethodSpec.methodBuilder("map")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(modelName)
                .addParameter(ROW, "row");
        if (model.isRecord()) {
            mapRecord(model, modelName, generated, map);
        } else {
            mapClass(model, modelName, generated, map);
        }
        type.addMethod(nullness.unmarked(map).build());
        for (JoinedTable table : joined) {
            if (SubtypeScopes.needed(table, joined)) {
                type.addMethod(nullness.unmarked(SubtypeScopes.method(table, joined, nestedModels)).build());
            }
        }
        // After the mapper, whose constants it names; the holder is read on the first call, not when this class loads.
        fieldIndex(model, modelName, generated, joined, FilterIndex.of(model, joined, filters), type);
        // No write reads a source: an outer model declares delete(), update(...) and the insert members only when it
        // reads an entity (R-GEN-37).
        if (keyed && !model.readsSource()) {
            writes(model, modelName, entity, keyType, type);
        }
        if (model.inserts()) {
            inserts(model, modelName, entity, type);
        }
        return file(generated, type);
    }

    /**
     * {@code fields()} and the private holder that builds the model's {@code FieldIndex} on the first call. It passes
     * constants by kind in declaration order, never key strings apart from the set names {@code ALL} and
     * {@code DEFAULT}, and omits a call with nothing to pass (R-GEN-32, R-GEN-33).
     */
    private static void fieldIndex(
            ModelDefinition model, ClassName modelName, ClassName generated, List<JoinedTable> joined,
            FilterIndex filters, TypeSpec.Builder type) {
        TypeName index = ParameterizedTypeName.get(FIELD_INDEX, modelName);
        // What the mapper reads, in the order of SELECTED_FIELDS (R-GEN-29).
        var select = new ArrayList<String>();
        model.columns().forEach(field -> select.add(field.constant()));
        joined.forEach(table -> table.columns().forEach(column -> select.add(column.constant())));
        model.computed().forEach(field -> select.add(field.constant()));
        model.aggregates().forEach(field -> select.add(field.constant()));
        model.windows().forEach(field -> select.add(field.constant()));
        CodeBlock.Builder init = CodeBlock.builder().add("$T.builder($T.class)$>$>", FIELD_INDEX, modelName);
        indexCall(init, "select", select);
        indexCall(init, "filterOnly", filters.kept().stream().map(column -> column.definition().name()).toList());
        if (model.selectSets()) {
            init.add("\n.set($S, ALL)\n.set($S, DEFAULT)", "ALL", "DEFAULT");
            joined.forEach(table -> init.add("\n.joinSet($L, $L)", table.table(), table.prefix()));
        }
        indexCall(init, "child", model.children().stream().map(ModelField::constant).toList());
        init.add("\n.build()$<$<");
        // The holder is named Index and may share a name with a user's type; JavaPoet qualifies the one it must.
        ClassName holder = generated.nestedClass("Index");
        type.addMethod(MethodSpec.methodBuilder("fields")
                .addJavadoc("This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.\n")
                .addAnnotation(INCUBATING)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(index)
                .addStatement("return $T.INSTANCE", holder)
                .build());
        type.addType(TypeSpec.classBuilder(holder.simpleName())
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addField(FieldSpec.builder(index, "INSTANCE", Modifier.STATIC, Modifier.FINAL)
                        .initializer(init.build())
                        .build())
                .build());
    }

    /** Adds {@code .name(constants)} to {@code init}, or nothing when there are no constants. */
    private static void indexCall(CodeBlock.Builder init, String name, List<String> constants) {
        if (!constants.isEmpty()) {
            // Each name is an argument, never part of the format: a field name may hold a '$'.
            init.add("\n.$L($L)", name, constants.stream().map(constant -> CodeBlock.of("$L", constant))
                    .collect(CodeBlock.joining(",$W")));
        }
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
            ClassName changes = ClassName.get(model.packageName(), model.changesName());
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
     * {@code INSERT_COLUMNS}, the columns {@link InsertedColumns} writes in declaration order, each read from a row by
     * its record accessor or getter, and the {@code insertFrom}, {@code insert} and {@code persist} that start from
     * it. {@code K} is the root's id type as the processor sees it, else {@code Object} ({@code MQ3504}). All four are
     * {@code @Incubating}, as the insert API is (R-GEN-28, D-117). On a {@code generateInserts} query model the
     * constant's Javadoc names what it leaves out (R-GEN-34).
     */
    private void inserts(ModelDefinition model, ClassName modelName, ClassName entity, TypeSpec.Builder type) {
        InsertedColumns inserted = InsertedColumns.of(model, metamodel);
        CodeBlock.Builder columns = CodeBlock.builder().add("$T.<$T, $T>of(ROOT)", INSERT_COLUMNS, modelName, entity);
        for (InsertedColumns.Written column : inserted.written()) {
            ModelField field = column.field();
            columns.add("\n$>$>.$L($L, $T::$L)$<$<", column.key() ? "addKey" : "add", field.constant(), modelName,
                    model.isRecord() ? field.name() : ChangesWriter.getter(field));
        }
        FieldSpec.Builder constant = FieldSpec.builder(ParameterizedTypeName.get(INSERT_COLUMNS, modelName, entity),
                        "INSERT_COLUMNS", CONSTANT)
                .addAnnotation(INCUBATING)
                .initializer(columns.build());
        if (model.queryModel()) {
            constant.addJavadoc("The root columns {@code insert}, {@code insertFrom} and {@code persist} write.\n");
            if (!inserted.leftOut().isEmpty()) {
                constant.addJavadoc("<p>Left out:\n<ul>\n");
                inserted.leftOut().forEach(field -> constant.addJavadoc("<li>$L</li>\n", field));
                constant.addJavadoc("</ul>\n");
            }
        }
        type.addField(constant.build());
        TypeMirror id = metamodel.id(model.root()).type();
        TypeName key = id == null ? TypeName.OBJECT
                : column(id.getKind() == TypeKind.TYPEVAR ? types.erasure(id) : id);
        TypeVariableName source = TypeVariableName.get("S");
        type.addMethod(MethodSpec.methodBuilder("insertFrom")
                .addAnnotation(INCUBATING)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(source)
                .returns(ParameterizedTypeName.get(MODEL_INSERT.nestedClass("SelectStart"), entity, modelName))
                .addParameter(ParameterizedTypeName.get(TABLE_FIELD, source, source), "sourceRoot")
                .addStatement("return $T.select(INSERT_COLUMNS, sourceRoot)", MODEL_INSERT)
                .build());
        type.addMethod(MethodSpec.methodBuilder("insert")
                .addAnnotation(INCUBATING)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(VALUES_INSERT.nestedClass("Rows"), entity, key, modelName))
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class),
                        WildcardTypeName.subtypeOf(modelName)), "rows")
                .addStatement("return $T.builder(INSERT_COLUMNS, $L, rows)", VALUES_INSERT, classOf(key))
                .build());
        type.addMethod(MethodSpec.methodBuilder("persist")
                .addAnnotation(INCUBATING)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(MODEL_PERSIST, entity, key, modelName))
                .addParameter(modelName, "row")
                .addStatement("return $T.of(INSERT_COLUMNS, $L, row)", MODEL_PERSIST, classOf(key))
                .build());
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
        var attribute = metamodel.resolve(EntityMetamodel.treatedRoot(model.root(), field.treat()), field.attribute())
                .attribute();
        return attribute == null ? null : builtIns.between(field.type(), attribute.type());
    }

    /**
     * {@code query()}, or {@code query(SourceQuery<S>)} for a model rooted at a source: an outer query carries the
     * source it reads, so one built without it doesn't compile (R-GEN-37).
     */
    private static MethodSpec query(
            ModelDefinition model, ClassName modelName, ClassName entity, boolean keyed, TypeName keyType,
            boolean grouped) {
        String key = keyed ? ".primaryKey(KEY)" : "";
        String group = grouped ? ".groupBy(GROUP_KEYS)" : "";
        MethodSpec.Builder method = MethodSpec.methodBuilder("query")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(
                        MODEL_QUERY.nestedClass("Builder"), entity, keyed ? keyType : TypeName.OBJECT, modelName));
        if (!model.readsSource()) {
            // Each joined source is a parameter, in declaration order, so a query without its source doesn't compile
            // (R-SRC-21).
            var joins = new StringBuilder();
            for (JoinSourceDefinition join : model.joinSources()) {
                method.addParameter(ParameterizedTypeName.get(SOURCE_QUERY, ClassName.get(join.sourceModel().type())),
                        join.name());
                joins.append(".joinSource(").append(QueryModelReader.constantName(join.name())).append(", ")
                        .append(join.name()).append(")");
            }
            return method.addStatement("return $T.builder(ROOT, MAPPER)$L$L$L", MODEL_QUERY, key, group, joins)
                    .build();
        }
        return method.addParameter(ParameterizedTypeName.get(SOURCE_QUERY, entity), "source")
                .addStatement("return $T.builder(ROOT, MAPPER, source)$L$L", MODEL_QUERY, key, group)
                .build();
    }

    private static MethodSpec sourceQuery(ClassName modelName, boolean keyed, TypeName keyType) {
        return MethodSpec.methodBuilder("query")
                .addAnnotation(INCUBATING)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(MODEL_QUERY.nestedClass("Builder"), modelName,
                        keyed ? keyType : TypeName.OBJECT, modelName))
                .addParameter(ParameterizedTypeName.get(SOURCE_QUERY, modelName), "source")
                .addStatement("return $T.builder(Rows.ROOT, Rows.MAPPER, source)$L", MODEL_QUERY,
                        keyed ? ".primaryKey(Rows.KEY)" : "")
                .build();
    }

    /** The read vocabulary of a source, whose own constants describe its CTE body (R-GEN-40). */
    private TypeSpec rows(ModelDefinition model, ClassName modelName, ClassName generated, TypeName keyType) {
        ClassName rowModel = qualified(modelName);
        ClassName selectSet = qualified(SELECT_SET);
        TypeSpec.Builder rows = TypeSpec.classBuilder("Rows")
                .addAnnotation(qualified(INCUBATING))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(qualified(TABLE_FIELD), rowModel, rowModel),
                                "ROOT", CONSTANT)
                        .initializer("$T.source($T.class)", qualified(TABLE_FIELD), rowModel)
                        .build());
        List<ModelField> fields = model.sourceFields();
        for (ModelField field : fields) {
            rows.addField(overColumn(rowModel, model, field, field.constant(), true, true));
        }
        rows.addField(FieldSpec.builder(ParameterizedTypeName.get(selectSet, rowModel), "ALL", CONSTANT)
                .initializer("$T.of($L)", selectSet, constants(fields)).build());
        List<ModelField> excluded = fields.stream().filter(ModelField::excludedFromDefaults).toList();
        rows.addField(FieldSpec.builder(ParameterizedTypeName.get(selectSet, rowModel), "DEFAULT", CONSTANT)
                .initializer(excluded.isEmpty() ? CodeBlock.of("ALL")
                        : CodeBlock.of("ALL.without($L)", constants(excluded))).build());
        List<ModelField> keys = model.keys();
        if (!keys.isEmpty()) {
            ClassName primaryKey = qualified(PRIMARY_KEY);
            rows.addField(FieldSpec.builder(ParameterizedTypeName.get(primaryKey, rowModel, qualified(keyType)),
                            "KEY", CONSTANT)
                    .initializer(keys.size() == 1 ? "$T.of($L)" : "$T.composite($L)", primaryKey, constants(keys))
                    .build());
        }
        rows.addField(FieldSpec.builder(ParameterizedTypeName.get(qualified(ROW_MAPPER), rowModel), "MAPPER", CONSTANT)
                .initializer("$T::map", qualified(generated.nestedClass("Rows"))).build());
        rows.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        MethodSpec.Builder map = MethodSpec.methodBuilder("map")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(rowModel)
                .addParameter(qualified(ROW), "row");
        if (model.isRecord()) {
            mapRecord(model, rowModel, generated, map);
        } else {
            mapClass(model, rowModel, generated, map);
        }
        if (nullness.on()) {
            map.addAnnotation(qualified(ClassName.get("org.jspecify.annotations", "NullUnmarked")));
        }
        rows.addMethod(map.build());
        return rows.build();
    }

    /** An import-free name keeps the nested Rows vocabulary from shadowing a user's types. */
    private static ClassName qualified(ClassName type) {
        return ClassName.get("", type.canonicalName());
    }

    private static TypeName qualified(TypeName type) {
        if (type instanceof ClassName name) {
            return qualified(name);
        }
        if (type instanceof ParameterizedTypeName parameterized) {
            return ParameterizedTypeName.get(qualified(parameterized.rawType), parameterized.typeArguments.stream()
                    .map(QModelWriter::qualified).toArray(TypeName[]::new));
        }
        if (type instanceof ArrayTypeName array) {
            return ArrayTypeName.of(qualified(array.componentType));
        }
        if (type instanceof WildcardTypeName wildcard) {
            return wildcard.lowerBounds.isEmpty() ? WildcardTypeName.subtypeOf(qualified(wildcard.upperBounds.get(0)))
                    : WildcardTypeName.supertypeOf(qualified(wildcard.lowerBounds.get(0)));
        }
        return type;
    }

    /**
     * {@code source()}: the query an outer model reads this source with, over the source model's own root, fields
     * and key. An ungrouped source takes the four-argument factory, whose {@code having} is {@code MQ1407}
     * (R-GEN-37). A branch names its shape and fixes its {@code @Discriminator} to its constant, so its query is a
     * {@code SourceQuery<Shape>} that combines with its shape's other branches (R-PROC-38, R-SRC-06).
     */
    private static MethodSpec source(ModelDefinition model, ClassName modelName, ClassName entity, boolean grouped) {
        ClassName shapeName = ClassName.get(model.branch() ? model.shape().type() : model.type());
        CodeBlock.Builder body = CodeBlock.builder().add("return $T.builder($T.class, ROOT, ALL, KEY", SOURCE_QUERY,
                modelName);
        if (grouped) {
            body.add(", GROUP_KEYS");
        }
        if (model.readsSource()) {
            body.add(", inner");
        }
        body.add(")");
        // Only a branch names a shape: a source over its own root is read as its own model (R-PROC-38).
        if (model.branch()) {
            body.add(".shape($T.class)", shapeName);
        }
        // A shape's own source() is its first branch, so it fixes its discriminator exactly as a branch does.
        for (ModelField field : model.discriminators()) {
            body.add(".discriminator($L, $S)", field.constant(), field.discriminator());
        }
        // A recursive model names the @Depth field its level column is aliased by, which also makes the builder
        // require the recursive step before build() (R-PROC-37, R-GEN-37).
        for (ModelField field : model.depths()) {
            body.add(".depth($L)", field.constant());
        }
        MethodSpec.Builder method = MethodSpec.methodBuilder("source")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(SOURCE_QUERY.nestedClass("Builder"), entity, modelName,
                        shapeName))
                .addStatement("$L", body.build());
        if (model.readsSource()) {
            method.addAnnotation(INCUBATING)
                    .addParameter(ParameterizedTypeName.get(SOURCE_QUERY, entity), "inner");
        }
        return method.build();
    }

    /**
     * The constant of a column of a model rooted at a source: {@code ColumnField.over} reads the CTE column the
     * source's own constant names, keeping its alias, its type and its converter (R-GEN-37).
     */
    private FieldSpec overColumn(ClassName modelName, ModelDefinition model, ModelField field) {
        return overColumn(modelName, model, field.constant(), field.attribute(), true);
    }

    private FieldSpec overColumn(
            ClassName modelName, ModelDefinition model, String constant, String attribute, boolean named) {
        return overColumn(modelName, model.source(), model.sourceField(attribute), constant, named, false);
    }

    private FieldSpec overColumn(ClassName modelName, ModelDefinition sourceModel, ModelField source,
            String constant, boolean named, boolean qualify) {
        return overColumn(modelName, sourceModel, source, constant, named, qualify, CodeBlock.of("ROOT"));
    }

    private FieldSpec overColumn(ClassName modelName, ModelDefinition sourceModel, ModelField source,
            String constant, boolean named, boolean qualify, CodeBlock root) {
        ClassName kind = ConverterType.ordered(types, converter(sourceModel, source))
                ? ORDERED_COLUMN_FIELD : COLUMN_FIELD;
        ClassName sourceType = ClassName.get(sourceModel.type());
        ClassName sourceQModel = generatedName(sourceModel);
        TypeName value = column(source.type());
        return FieldSpec.builder(
                        ParameterizedTypeName.get(qualify ? qualified(kind) : kind, modelName,
                                qualify ? qualified(sourceType) : sourceType, qualify ? qualified(value) : value),
                        constant, CONSTANT)
                .initializer("$T.over($T.class,$W$L,$W$T.$L)$L", qualify ? qualified(COLUMN_FIELD) : COLUMN_FIELD,
                        modelName, root, qualify ? qualified(sourceQModel) : sourceQModel, source.constant(),
                        named ? CodeBlock.of(".named($S)", source.name()) : CodeBlock.of(""))
                .build();
    }

    /**
     * The {@code SourceJoin} constant of a {@code @JoinSource}, named after the join: the outer columns are built in
     * place from the attribute paths, as an aggregate's are (R-SRC-21, R-PROC-44).
     */
    private FieldSpec sourceJoin(ClassName modelName, ModelDefinition model, JoinSourceDefinition join) {
        ModelDefinition source = join.sourceModel();
        var pairs = new ArrayList<CodeBlock>();
        for (JoinOn pair : join.on()) {
            TypeMirror attribute = metamodel.resolve(model.root(), pair.column()).attribute().type();
            ModelField key = source.readable().stream().filter(field -> field.name().equals(pair.key()))
                    .findFirst().orElseThrow();
            pairs.add(CodeBlock.of("$T.on($T.of($T.class,$WROOT,$W$S,$W$L),$W$T.$L)", SOURCE_JOIN, COLUMN_FIELD,
                    modelName, pair.column(), classOf(column(attribute)), generatedName(source), key.constant()));
        }
        ClassName sourceType = ClassName.get(source.type());
        return FieldSpec.builder(ParameterizedTypeName.get(SOURCE_JOIN, modelName, sourceType),
                        QueryModelReader.constantName(join.name()), CONSTANT)
                .initializer("$T.of($S, $T.class, $T.$L,$W$L)", SOURCE_JOIN, join.name(), sourceType, JOIN_KIND,
                        join.kind(), listOf(pairs))
                .build();
    }

    /**
     * The constant of a {@code @SourceColumn} field: the source field's column over the join's table, which keeps its
     * type, alias and converter (R-PROC-44).
     */
    private FieldSpec joinedColumn(ClassName modelName, ModelDefinition model, ModelField field) {
        JoinSourceDefinition join = model.joinSources().stream()
                .filter(each -> each.name().equals(field.sourceColumn().join())).findFirst().orElseThrow();
        ModelField read = join.sourceModel().readable().stream()
                .filter(each -> each.name().equals(field.sourceColumn().field())).findFirst().orElseThrow();
        return overColumn(modelName, join.sourceModel(), read, field.constant(), false, false,
                CodeBlock.of("$L.root()", QueryModelReader.constantName(join.name())));
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
        // Named after the field, so a client-facing key never carries the canonical text of the function (R-GEN-33).
        CodeBlock named = CodeBlock.of("$Z.named($S)", field.name());
        TypeName result = column(field.type());
        FieldSpec.Builder constant = FieldSpec.builder(
                ParameterizedTypeName.get(AGGREGATE_FIELD, modelName, result), field.constant(), CONSTANT);
        if (aggregate.expression() != null) {
            // The aggregate is the Agg overload over the definition's expression (R-PROC-22, R-GEN-27).
            ExpressionDefinitionType definition = ExpressionDefinitionType.of(types, aggregate.expression());
            TypeMirror sourceType = definition.value();
            String function = aggregateFunction(field, sourceType);
            CodeBlock call = CodeBlock.of("$T.$L($L$L)", AGG, function, definitionExpression(definition),
                    firstByOrders(aggregate, model, modelName));
            return constant.initializer("$L$L", refined(aggregate, call), named).build();
        }
        if (aggregate.attribute().isEmpty() && aggregate.fn().equals("COUNT")) {
            // The model can't be inferred from ROOT, once .named() is chained on the call.
            CodeBlock call = CodeBlock.of("$T.<$T>count(ROOT)", AGG, modelName);
            return constant.initializer("$L$L", refined(aggregate, call), named).build();
        }
        // An aggregate over a model rooted at a source reads a source constant: it carries the CTE alias, the type
        // and the converter the source declares, so no column is built in place for it (R-SRC-10).
        ModelField fromSource = model.sourceField(aggregate.attribute());
        TypeMirror attributeType = fromSource == null
                ? metamodel.resolve(model.root(), aggregate.attribute()).attribute().type() : fromSource.type();
        // MIN or MAX typed as the field reads its Timestamp attribute through the built-in converter (D-84).
        TypeMirror converter = fromSource == null ? builtIns.between(field.type(), attributeType) : null;
        CodeBlock source = fromSource != null
                ? CodeBlock.of("$T.over($T.class,$WROOT,$W$T.$L)", COLUMN_FIELD, modelName,
                        generatedName(model.source()), fromSource.constant())
                : converter == null
                        ? CodeBlock.of("$T.of($T.class,$WROOT,$W$S,$W$L)", COLUMN_FIELD, modelName,
                                aggregate.attribute(), classOf(column(attributeType)))
                        : CodeBlock.of("$T.of($T.class,$WROOT,$W$S,$W$L,$W$L,$W$T.INSTANCE)", COLUMN_FIELD,
                                modelName, aggregate.attribute(), classOf(result), classOf(column(attributeType)),
                                ClassName.get((TypeElement) types.asElement(converter)));
        // COUNT is Agg.count(column), keyed by the column, so a count of the id is the row count (R-AGG-19) and the
        // field takes a filter like any count (R-AGG-01).
        String function = aggregateFunction(field, attributeType);
        CodeBlock call = CodeBlock.of("$T.$L($Z$L$L)", AGG, function, source,
                firstByOrders(aggregate, model, modelName));
        return constant.initializer("$L$L", refined(aggregate, call), named).build();
    }

    /**
     * {@code call} filtered by the {@code FilterDefinition} {@code aggregate} names, then rounded to its
     * {@code scale}: the filter goes on the inner aggregate, as {@code Agg.round(...).filter(...)} is refused
     * (R-PROC-45).
     */
    private CodeBlock refined(AggregateDefinition aggregate, CodeBlock call) {
        CodeBlock filtered = call;
        if (aggregate.filter() != null) {
            FilterDefinitionType definition = FilterDefinitionType.of(types, aggregate.filter());
            ClassName type = ClassName.get(definition.type());
            filtered = CodeBlock.of("$L.filter($L.condition())", call, definition.hasInstance()
                    ? CodeBlock.of("$T.INSTANCE", type) : CodeBlock.of("new $T()", type));
        }
        return aggregate.scale() == null ? filtered
                : CodeBlock.of("$T.round($L, $L)", AGG, filtered, aggregate.scale());
    }

    /** The {@code , order...} arguments of a {@code FIRST_BY}, and nothing for any other function. */
    private CodeBlock firstByOrders(AggregateDefinition aggregate, ModelDefinition model, ClassName modelName) {
        if (!aggregate.fn().equals("FIRST_BY")) {
            return CodeBlock.of("");
        }
        var keys = new ArrayList<CodeBlock>();
        for (String entry : aggregate.orderBy()) {
            AggregateOrder order = AggregateOrder.parse(entry);
            ModelField fromSource = model.sourceField(order.path());
            CodeBlock column = fromSource != null && model.readsSource()
                    ? CodeBlock.of("$T.over($T.class,$WROOT,$W$T.$L)", COLUMN_FIELD, modelName,
                            generatedName(model.source()), fromSource.constant())
                    : CodeBlock.of("$T.of($T.class,$WROOT,$W$S,$W$L)", COLUMN_FIELD, modelName, order.path(),
                            classOf(column(metamodel.resolve(model.root(), order.path()).attribute().type())));
            keys.add(CodeBlock.of(",$W$L.$L()$L", column, order.ascending() ? "asc" : "desc",
                    order.nulls().isEmpty() ? "" : order.nulls().equals("first") ? ".nullsFirst()" : ".nullsLast()"));
        }
        return keys.stream().collect(CodeBlock.joining(""));
    }

    /**
     * The {@code Agg} factory for {@code field}'s function over a column of {@code sourceType}: {@code COUNT}
     * counts, {@code SUM} sums, an {@code AVG} on a {@code BigDecimal} field is the exact average, and any other
     * function is its name lower-cased.
     */
    private String aggregateFunction(ModelField field, TypeMirror sourceType) {
        AggregateDefinition aggregate = field.aggregate();
        return switch (aggregate.fn()) {
            case "COUNT" -> aggregate.distinct() ? "countDistinct" : "count";
            // Agg.sum refuses a 32-bit expression; the field is a Long and the QModel sums it as one (MQ3205).
            case "SUM" -> column(sourceType).toString().matches("java\\.lang\\.(Integer|Short|Byte)")
                    ? "sumAsLong" : "sum";
            case "AVG" -> column(field.type()).toString().equals("java.math.BigDecimal") ? "avgExact" : "avg";
            case "FIRST_BY" -> "firstBy";
            default -> aggregate.fn().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * The constant of a {@code @Window} field: a {@code WindowField} over the model's own constants, named after the
     * field so its CTE column and its {@code fields()} key are the field's name (R-GEN-38). A term that names no
     * declared field is built as a column in place, as an aggregate over an attribute is.
     */
    private FieldSpec window(ClassName modelName, ModelDefinition model, ModelField field) {
        WindowDefinition window = field.window();
        CodeBlock argument = window.argument().isEmpty()
                ? CodeBlock.of("null") : windowTerm(window.argument(), model, modelName);
        return FieldSpec.builder(
                        ParameterizedTypeName.get(WINDOW_FIELD, modelName, column(field.type())), field.constant(),
                        CONSTANT)
                .initializer("$T.of($T.class,$W$T.$L,$W$L,$W$L,$W$L,$W$L,$W$T.$L)$Z.named($S)", WINDOW_FIELD,
                        modelName, WINDOW_FUNCTION, window.function(), classOf(column(field.type())), argument,
                        windowTerms(window.partitionBy(), model, modelName),
                        windowOrders(window.orderBy(), model, modelName),
                        WINDOW_FRAME, window.frame(), field.name())
                .build();
    }

    /** The {@code partitionBy} list of a window: {@code List.of(...)} of the model's constants, or an empty list. */
    private CodeBlock windowTerms(List<String> terms, ModelDefinition model, ClassName modelName) {
        return listOf(terms.stream().map(term -> windowTerm(term, model, modelName)).toList());
    }

    private CodeBlock windowOrders(List<WindowOrder> orders, ModelDefinition model, ClassName modelName) {
        return listOf(orders.stream()
                .map(order -> CodeBlock.of("$L.$L()", windowTerm(order.path(), model, modelName),
                        order.ascending() ? "asc" : "desc"))
                .toList());
    }

    private static CodeBlock listOf(List<CodeBlock> elements) {
        return elements.isEmpty() ? CodeBlock.of("$T.of()", LIST)
                : CodeBlock.of("$T.of($L)", LIST, elements.stream().collect(CodeBlock.joining(",$W")));
    }

    private CodeBlock windowTerm(String term, ModelDefinition model, ClassName modelName) {
        ModelField declared = model.fields().stream()
                .filter(each -> each.column() ? each.attribute().equals(term) : each.name().equals(term))
                .findFirst().orElse(null);
        if (declared != null) {
            return CodeBlock.of("$L", declared.constant());
        }
        if (model.readsSource()) {
            return CodeBlock.of("$T.over($T.class,$WROOT,$W$T.$L)", COLUMN_FIELD, modelName,
                    generatedName(model.source()), model.sourceField(term).constant());
        }
        Resolution resolution = metamodel.resolve(model.root(), term);
        CodeBlock type = resolution.attribute() == null
                ? classOf(ClassName.get(Object.class)) : classOf(column(resolution.attribute().type()));
        return CodeBlock.of("$T.of($T.class,$WROOT,$W$S,$W$L)", COLUMN_FIELD, modelName, term, type);
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

    /**
     * The private set of every column, expression and aggregate constant the mapper reads, the joined columns of
     * every depth included, for a {@code @Selected} field to be filled from (R-GEN-29).
     */
    private static FieldSpec selectedFields(ClassName modelName, ModelDefinition model, List<JoinedTable> joined) {
        var fields = new ArrayList<CodeBlock>();
        model.columns().forEach(field -> fields.add(CodeBlock.of("$L", field.constant())));
        joined.forEach(table -> table.columns().forEach(column -> fields.add(CodeBlock.of("$L", column.constant()))));
        model.computed().forEach(field -> fields.add(CodeBlock.of("$L", field.constant())));
        model.aggregates().forEach(field -> fields.add(CodeBlock.of("$L", field.constant())));
        return FieldSpec.builder(ParameterizedTypeName.get(SELECT_SET, modelName), SELECTED_FIELDS,
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.of($L)", SELECT_SET, fields.stream().collect(CodeBlock.joining(",$W")))
                .build();
    }

    /**
     * What a {@code @Selected} field is filled with: the set's subset the row selected, read through the generated
     * class by name, so that a {@code @Join} local of the same name can't shadow it (R-GEN-30).
     */
    private static CodeBlock selected(ClassName generated) {
        return CodeBlock.of("$T.$L.selectedIn(row)", generated, SELECTED_FIELDS);
    }

    /** Passes every component to the canonical constructor in order; an unselected column reads as null (R-GEN-06). */
    private void mapRecord(
            ModelDefinition model, ClassName modelName, ClassName generated, MethodSpec.Builder map) {
        scopeJoins(model, map);
        CodeBlock arguments = model.fields().stream()
                .map(field -> field.join() != null ? nested(field) : field.child() != null ? unloaded(field)
                        : field.selected() ? selected(generated)
                        : field.column() || field.computed() || field.aggregate() != null || field.window() != null
                        ? CodeBlock.of("row.get($L)", field.constant()) : CodeBlock.of(unset(field.type())))
                .collect(CodeBlock.joining(",$W"));
        map.addStatement("return new $T($L)", modelName, arguments);
    }

    /**
     * Calls a setter only for a selected column, so a field initialiser survives for the others (R-GEN-09), and
     * always for a {@code @Join} field, which is never left {@code null} (R-GEN-15), a {@code @Child} field and a
     * {@code @Selected} field (R-GEN-30).
     */
    private void mapClass(
            ModelDefinition model, ClassName modelName, ClassName generated, MethodSpec.Builder map) {
        map.addStatement("$T m = new $T()", modelName, modelName);
        for (ModelField field : model.fields()) {
            if (!field.column() && !field.computed() && field.aggregate() == null && field.window() == null) {
                continue;
            }
            map.beginControlFlow("if (row.isSelected($L))", field.constant())
                    .addStatement("m.$L(row.get($L))", setter(field), field.constant())
                    .endControlFlow();
        }
        for (ModelField field : model.selected()) {
            map.addStatement("m.$L($L)", setter(field), selected(generated));
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
    private void scopeJoins(ModelDefinition model, MethodSpec.Builder map) {
        List<JoinedTable> joined = nestedModels.tables(model);
        for (ModelField field : model.joins()) {
            JoinedTable table = joined.stream().filter(candidate -> candidate.join().equals(field)).findFirst()
                    .orElseThrow();
            if (SubtypeScopes.needed(table, joined)) {
                map.addStatement("$T $L = $L(row)", ROW, scope(field), SubtypeScopes.name(table));
            } else {
                map.addStatement("$T $L = row.scoped($L_TABLE)", ROW, scope(field), field.join().prefix());
            }
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
    private FieldSpec joinedTable(JoinedTable table) {
        ClassName nested = generatedName(table.nested());
        ClassName parentEntity = ClassName.get(table.parentEntity());
        ClassName entity = ClassName.get(table.entity());
        JoinDefinition join = table.declaration().join();
        TypeElement target = (TypeElement) metamodel.resolve(table.parentEntity(), join.attribute())
                .attribute().target().asElement();
        boolean downcast = metamodel.strictEntitySubtype(table.entity().asType(), target);
        FieldSpec.Builder constant = FieldSpec.builder(
                ParameterizedTypeName.get(TABLE_FIELD, downcast ? ClassName.get(target) : parentEntity, entity),
                table.table(), CONSTANT);
        if (table.parent() != null && join.treat() == null && !downcast) {
            return constant.initializer("$T.$L.withParent($L_TABLE)",
                    nested, table.inNested(table.table()), table.parent()).build();
        }
        CodeBlock parent = table.parent() == null ? CodeBlock.of("$L", TreatTables.root(join.treat()))
                : join.treat() == null ? CodeBlock.of("$L_TABLE", table.parent())
                : CodeBlock.of("$T.$L.withParent($L_TABLE)", generatedName(table.owner()),
                        TreatTables.root(join.treat()), table.parent());
        CodeBlock.Builder initializer = CodeBlock.builder().add("$T.<$T, $T>join($L,$W$S,$W$T.$L)",
                TABLE_FIELD, parentEntity, downcast ? ClassName.get(target) : entity, parent,
                join.attribute(), JOIN_TYPE, join.type());
        if (!join.alias().isEmpty()) {
            initializer.add("$Z.as($S)", join.alias());
        }
        // The join is named after its field, so a sort or join set names a column under it as the model does (D-55);
        // a treated node's path is its join's, so a downcast is named before treat(...).
        if (downcast) {
            initializer.add("$Z.named($S)$Z.treat($T.class)", table.declaration().name(), entity);
        }
        // The nested model's key decides whether the join matched a row (D-38).
        initializer.add("$Z.presentBy($T.KEY)", generatedName(nestedModels.of(table.declaration())));
        if (!downcast) {
            initializer.add("$Z.named($S)", table.declaration().name());
        }
        return constant.initializer(initializer.build()).build();
    }

    /**
     * A subtype value is an expression, selected and mapped like an ordinary field (R-GEN-42), named by its path as a
     * column is, so a joined one never shares a name with the outer one or another join's.
     */
    private FieldSpec subtype(ClassName modelName, String constant, String table, String name, ModelField field) {
        return FieldSpec.builder(ParameterizedTypeName.get(EXPRESSION_FIELD, modelName, column(field.type())),
                        constant, CONSTANT)
                .initializer("$T.typeOf($T.class,$W$L).named($S)", ClassName.get(CORE, "Expr"), modelName, table,
                        name).build();
    }

    static ClassName generatedName(ModelDefinition model) {
        return ClassName.get(model.packageName(), model.generatedName());
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
