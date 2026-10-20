package com.rey.modelquery.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;

/** Adapts a nested subtype expression to its outer constant; Row.scoped otherwise reads columns only. */
final class SubtypeScopes {

    private static final String CORE = "com.rey.modelquery.core";
    private static final ClassName ROW = ClassName.get(CORE, "Row");
    private static final ClassName SELECT_FIELD = ClassName.get(CORE, "SelectField");
    private static final ClassName TABLE_FIELD = ClassName.get(CORE, "TableField");

    private SubtypeScopes() {}

    static boolean needed(JoinedTable table, List<JoinedTable> joined) {
        return table.columns().stream().anyMatch(column -> column.field().subtype())
                || children(table, joined).stream().anyMatch(child -> needed(child, joined));
    }

    static String name(JoinedTable table) {
        return "scope$" + table.prefix();
    }

    static MethodSpec.Builder method(JoinedTable table, List<JoinedTable> joined, NestedModels nestedModels) {
        ClassName nested = QModelWriter.generatedName(nestedModels.of(table.declaration()));
        var adapter = TypeSpec.anonymousClassBuilder("").addSuperinterface(ROW);
        TypeVariableName value = TypeVariableName.get("C");
        TypeName any = WildcardTypeName.subtypeOf(Object.class);
        for (String operation : List.of("get", "raw", "isSelected")) {
            MethodSpec.Builder method = MethodSpec.methodBuilder(operation).addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC);
            if (operation.equals("get")) {
                method.addTypeVariable(value).returns(value)
                        .addParameter(ParameterizedTypeName.get(SELECT_FIELD, any, value), "column");
            } else {
                method.returns(operation.equals("raw") ? TypeName.OBJECT : TypeName.BOOLEAN)
                        .addParameter(ParameterizedTypeName.get(SELECT_FIELD, any, any), "column");
            }
            for (JoinedTable.JoinedColumn column : columns(table, joined).toList()) {
                if (column.field().subtype()) {
                    method.beginControlFlow("if (column.equals($T.$L))", nested,
                            column.constant().substring(table.prefix().length() + 1));
                    if (operation.equals("get")) {
                        method.addStatement("return column.type().cast(row.get($L))", column.constant());
                    } else {
                        method.addStatement("return row.$L($L)", operation, column.constant());
                    }
                    method.endControlFlow();
                }
            }
            adapter.addMethod(method.addStatement("return scoped.$L(column)", operation).build());
        }
        MethodSpec.Builder scope = MethodSpec.methodBuilder("scoped").addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC).returns(ROW)
                .addParameter(ParameterizedTypeName.get(TABLE_FIELD, any, any), "join");
        for (JoinedTable child : children(table, joined)) {
            if (needed(child, joined)) {
                scope.beginControlFlow("if (join.equals($T.$L_TABLE))", nested, child.declaration().join().prefix())
                        .addStatement("return $L(row)", name(child)).endControlFlow();
            }
        }
        adapter.addMethod(scope.addStatement("return scoped.scoped(join)").build());
        return MethodSpec.methodBuilder(name(table)).addModifiers(Modifier.PRIVATE, Modifier.STATIC).returns(ROW)
                .addParameter(ROW, "row").addStatement("$T scoped = row.scoped($L)", ROW, table.table())
                .addStatement("return $L", adapter.build());
    }

    private static List<JoinedTable> children(JoinedTable table, List<JoinedTable> joined) {
        return joined.stream().filter(child -> table.prefix().equals(child.parent())).toList();
    }

    private static Stream<JoinedTable.JoinedColumn> columns(JoinedTable table, List<JoinedTable> joined) {
        return Stream.concat(table.columns().stream(), children(table, joined).stream()
                .flatMap(child -> columns(child, joined)));
    }
}
