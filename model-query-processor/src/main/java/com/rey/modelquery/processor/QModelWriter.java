package com.rey.modelquery.processor;

import com.rey.modelquery.processor.JoinedTable.JoinedColumn;
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
 * @implSpec R-GEN-04, R-GEN-05, R-GEN-06, R-GEN-09, R-GEN-10, R-GEN-12, R-GEN-13, R-GEN-14, R-GEN-15, R-GEN-24,
 *     R-PROC-07, R-PROC-09, R-PROC-10, R-PROC-11, R-PROC-12, R-PROC-13
 */
final class QModelWriter {

    private static final String CORE = "com.rey.modelquery.core";
    private static final ClassName TABLE_FIELD = ClassName.get(CORE, "TableField");
    private static final ClassName COLUMN_FIELD = ClassName.get(CORE, "ColumnField");
    private static final ClassName COLUMN_SET = ClassName.get(CORE, "ColumnSet");
    private static final ClassName PRIMARY_KEY = ClassName.get(CORE, "PrimaryKey");
    private static final ClassName ROW_MAPPER = ClassName.get(CORE, "RowMapper");
    private static final ClassName ROW = ClassName.get(CORE, "Row");
    private static final ClassName MODEL_QUERY = ClassName.get(CORE, "ModelQuery");
    private static final ClassName JOIN_TYPE = ClassName.get("jakarta.persistence.criteria", "JoinType");
    private static final ClassName OPTIONAL = ClassName.get(Optional.class);
    private static final Modifier[] CONSTANT = {Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL};

    private final Types types;
    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;

    QModelWriter(Types types, EntityMetamodel metamodel, NestedModels nestedModels) {
        this.types = types;
        this.metamodel = metamodel;
        this.nestedModels = nestedModels;
    }

    /** The QModel file of {@code model}, whose only originating element is the model's type (R-GEN-05). */
    JavaFile write(ModelDefinition model) {
        ClassName modelName = ClassName.get(model.type());
        ClassName entity = ClassName.get(model.root());
        ClassName generated = ClassName.get(modelName.packageName(), model.generatedName());
        List<ModelField> keys = model.keys();
        // A composite key reads as the list of its column values (PrimaryKey.composite).
        TypeName keyType = keys.size() == 1
                ? column(keys.get(0).type()) : ParameterizedTypeName.get(List.class, Object.class);

        TypeSpec.Builder type = TypeSpec.classBuilder(generated)
                .addOriginatingElement(model.type())
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", ModelQueryProcessor.class.getCanonicalName())
                        .build())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(TABLE_FIELD, entity, entity), "ROOT", CONSTANT)
                        .initializer("$T.root($T.class)", TABLE_FIELD, entity)
                        .build());
        List<JoinedTable> joined = nestedModels.tables(model);
        for (JoinedTable table : joined) {
            type.addField(joinedTable(table));
        }
        FilterLayout filters = FilterLayout.of(model, joined, metamodel);
        for (FilterLayout.Table table : filters.tables()) {
            type.addField(filterTable(table));
        }
        for (ModelField field : model.columns()) {
            type.addField(column(
                    modelName, model.root(), field.constant(), "ROOT", field.attribute(), field.type(),
                    field.converter()));
        }
        for (JoinedTable table : joined) {
            ClassName nested = generatedName(table.nested());
            for (JoinedColumn column : table.columns()) {
                type.addField(FieldSpec.builder(ParameterizedTypeName.get(COLUMN_FIELD, modelName,
                                ClassName.get(table.entity()), column(column.type())), column.constant(), CONSTANT)
                        .initializer("$T.$L.withTable($T.class,$W$L)",
                                nested, table.inNested(column.constant()), modelName, table.table())
                        .build());
            }
        }
        // A filter-only column has no field to map: it is in no ColumnSet and not in map(Row) (R-PROC-10).
        for (FilterLayout.Column column : filters.columns()) {
            type.addField(column(
                    modelName, column.entity(), column.definition().name(), column.table(), column.attribute(),
                    column.read().type(), column.definition().converter()));
        }
        if (model.columnSets()) {
            TypeName columnSet = ParameterizedTypeName.get(COLUMN_SET, modelName);
            List<ModelField> excluded = model.columns().stream().filter(ModelField::excludedFromDefaults).toList();
            type.addField(FieldSpec.builder(columnSet, "ALL", CONSTANT)
                    .initializer("$T.of($L)", COLUMN_SET, constants(model.columns()))
                    .build());
            type.addField(FieldSpec.builder(columnSet, "DEFAULT", CONSTANT)
                    .initializer(excluded.isEmpty() ? CodeBlock.of("ALL") : CodeBlock.of("ALL.without($L)",
                            constants(excluded)))
                    .build());
            // Derived from the nested model's own fields, so a column added there is selected here too (R-GEN-04).
            for (JoinedTable table : joined) {
                type.addField(FieldSpec.builder(columnSet, table.prefix(), CONSTANT)
                        .initializer("$T.of($L)", COLUMN_SET, table.columns().stream()
                                .map(column -> CodeBlock.of("$L", column.constant()))
                                .collect(CodeBlock.joining(",$W")))
                        .build());
            }
        }
        type.addField(FieldSpec.builder(ParameterizedTypeName.get(PRIMARY_KEY, modelName, keyType), "KEY", CONSTANT)
                .initializer(keys.size() == 1 ? "$T.of($L)" : "$T.composite($L)", PRIMARY_KEY, constants(keys))
                .build());
        type.addField(FieldSpec.builder(ParameterizedTypeName.get(ROW_MAPPER, modelName), "MAPPER", CONSTANT)
                .initializer("$T::map", generated)
                .build());
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        type.addMethod(MethodSpec.methodBuilder("query")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(MODEL_QUERY.nestedClass("Builder"), entity, keyType, modelName))
                .addStatement("return $T.builder(ROOT, MAPPER).primaryKey(KEY)", MODEL_QUERY)
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
        return JavaFile.builder(generated.packageName(), type.build())
                .indent("    ")
                .skipJavaLangImports(true)
                .build();
    }

    /**
     * A column constant reading {@code attribute} on {@code table}. Its type is {@code type}, or what
     * {@code converterClass} makes of the attribute when {@code type} is the attribute's own, as a filter column's is.
     */
    private FieldSpec column(
            ClassName modelName, TypeElement entity, String constant, String table, String attribute,
            TypeMirror type, TypeMirror converterClass) {
        ConverterType converter = converterClass == null ? null : ConverterType.of(types, converterClass);
        TypeName column = column(converter == null ? type : converter.model());
        FieldSpec.Builder field = FieldSpec.builder(
                ParameterizedTypeName.get(COLUMN_FIELD, modelName, ClassName.get(entity), column), constant, CONSTANT);
        if (column instanceof ParameterizedTypeName
                || converter != null && column(converter.attribute()) instanceof ParameterizedTypeName) {
            field.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build());
        }
        if (converter == null) {
            return field.initializer("$T.of($T.class,$W$L,$W$S,$W$L)",
                    COLUMN_FIELD, modelName, table, attribute, classOf(column)).build();
        }
        // The column carries its converter, so Row.get and a filter both convert (R-PROC-07, D-37).
        ClassName converterName = ClassName.get(converter.type());
        return field.initializer("$T.of($T.class,$W$L,$W$S,$W$L,$W$L,$W$L)", COLUMN_FIELD, modelName, table,
                attribute, classOf(column), classOf(column(converter.attribute())),
                converter.hasInstance()
                        ? CodeBlock.of("$T.INSTANCE", converterName) : CodeBlock.of("new $T()", converterName))
                .build();
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
                .map(field -> field.join() != null ? nested(field) : field.column()
                        ? CodeBlock.of("row.get($L)", field.constant()) : CodeBlock.of(unset(field.type())))
                .collect(CodeBlock.joining(",$W"));
        map.addStatement("return new $T($L)", modelName, arguments);
    }

    /**
     * Calls a setter only for a selected column, so a field initialiser survives for the others (R-GEN-09), and
     * always for a {@code @Join} field, which is never left {@code null} (R-GEN-15).
     */
    private void mapClass(ModelDefinition model, ClassName modelName, MethodSpec.Builder map) {
        map.addStatement("$T m = new $T()", modelName, modelName);
        for (ModelField field : model.columns()) {
            map.beginControlFlow("if (row.isSelected($L))", field.constant())
                    .addStatement("m.$L(row.get($L))", setter(field), field.constant())
                    .endControlFlow();
        }
        scopeJoins(model, map);
        for (ModelField field : model.joins()) {
            map.addStatement("m.$L($L)", setter(field), nested(field));
        }
        map.addStatement("return m");
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

    /** A {@code @Join} of the model itself, or a join of its nested model moved under one (R-GEN-13, R-GEN-14). */
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
        // The nested model's key decides whether the join matched a row (D-38).
        return constant.initializer(initializer.add("$Z.presentBy($T.KEY)", nested).build()).build();
    }

    private static ClassName generatedName(ModelDefinition model) {
        return ClassName.get(ClassName.get(model.type()).packageName(), model.generatedName());
    }

    /**
     * The setter as Lombok names it, so generated and hand-written setters both resolve: a primitive
     * {@code boolean isX} gets {@code setX}, anything else {@code set} and the capitalised field name (R-GEN-10).
     */
    private static String setter(ModelField field) {
        String name = field.name();
        if (field.type().getKind() == TypeKind.BOOLEAN && name.length() > 2 && name.startsWith("is")
                && !Character.isLowerCase(name.charAt(2))) {
            name = name.substring(2);
        }
        return "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
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
        return TypeName.get(type.getKind().isPrimitive() ? types.boxedClass(types.getPrimitiveType(type.getKind()))
                .asType() : type);
    }

    /** The class literal of {@code type}. */
    private static CodeBlock classOf(TypeName type) {
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
