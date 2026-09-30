package com.rey.modelquery.processor;

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
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Emits the QModel source of a validated {@link ModelDefinition} (spec processor/31 §1). The engine's types are named,
 * not referenced, so the processor does not depend on {@code model-query-core}.
 *
 * @implSpec R-GEN-05, R-GEN-06, R-GEN-09, R-GEN-10, R-GEN-24
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
    private static final Modifier[] CONSTANT = {Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL};

    private final Types types;

    QModelWriter(Types types) {
        this.types = types;
    }

    /** The QModel file of {@code model}, whose only originating element is the model's type (R-GEN-05). */
    JavaFile write(ModelDefinition model) {
        ClassName modelName = ClassName.get(model.type());
        ClassName entity = ClassName.get(model.root());
        ClassName generated = ClassName.get(modelName.packageName(), model.generatedName());
        List<ModelField> keys = model.keys();
        // A composite key reads as the list of its column values (PrimaryKey.composite).
        TypeName keyType = keys.size() == 1
                ? column(keys.get(0)) : ParameterizedTypeName.get(List.class, Object.class);

        TypeSpec.Builder type = TypeSpec.classBuilder(generated)
                .addOriginatingElement(model.type())
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", ModelQueryProcessor.class.getCanonicalName())
                        .build())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(TABLE_FIELD, entity, entity), "ROOT", CONSTANT)
                        .initializer("$T.root($T.class)", TABLE_FIELD, entity)
                        .build());
        for (ModelField field : model.columns()) {
            TypeName column = column(field);
            FieldSpec.Builder constant = FieldSpec.builder(
                    ParameterizedTypeName.get(COLUMN_FIELD, modelName, entity, column), field.constant(), CONSTANT);
            if (column instanceof ParameterizedTypeName parameterized) {
                // A parameterized type has no class literal: its raw class stands in, as a hand-written column's would.
                ParameterizedTypeName classOf = ParameterizedTypeName.get(ClassName.get(Class.class), column);
                constant.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                                .addMember("value", "$S", "unchecked")
                                .build())
                        .initializer("$T.of($T.class,$WROOT,$W$S,$W($T) ($T) $T.class)", COLUMN_FIELD, modelName,
                                field.attribute(), classOf, ParameterizedTypeName.get(ClassName.get(Class.class),
                                        WildcardTypeName.subtypeOf(Object.class)), parameterized.rawType);
            } else {
                constant.initializer("$T.of($T.class,$WROOT,$W$S,$W$T.class)",
                        COLUMN_FIELD, modelName, field.attribute(), column);
            }
            type.addField(constant.build());
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

    /** Passes every component to the canonical constructor in order; an unselected column reads as null (R-GEN-06). */
    private static void mapRecord(ModelDefinition model, ClassName modelName, MethodSpec.Builder map) {
        CodeBlock arguments = model.fields().stream()
                .map(field -> field.column()
                        ? CodeBlock.of("row.get($L)", field.constant()) : CodeBlock.of(unset(field.type())))
                .collect(CodeBlock.joining(",$W"));
        map.addStatement("return new $T($L)", modelName, arguments);
    }

    /** Calls a setter only for a selected column, so a field initialiser survives for the others (R-GEN-09). */
    private static void mapClass(ModelDefinition model, ClassName modelName, MethodSpec.Builder map) {
        map.addStatement("$T m = new $T()", modelName, modelName);
        for (ModelField field : model.columns()) {
            map.beginControlFlow("if (row.isSelected($L))", field.constant())
                    .addStatement("m.$L(row.get($L))", setter(field), field.constant())
                    .endControlFlow();
        }
        map.addStatement("return m");
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

    /** A column's value type: the field's type, boxed, since a column that is not selected reads as null. */
    private TypeName column(ModelField field) {
        TypeMirror type = field.type();
        return TypeName.get(type.getKind().isPrimitive() ? types.boxedClass(types.getPrimitiveType(type.getKind()))
                .asType() : type);
    }

    private static CodeBlock constants(List<ModelField> fields) {
        // Each name is an argument, never part of the format: a field name may hold a '$'.
        return fields.stream().map(field -> CodeBlock.of("$L", field.constant())).collect(CodeBlock.joining(",$W"));
    }
}
