package com.rey.modelquery.processor;

import com.rey.modelquery.processor.ModelDefinition.ChildDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Modifier;
import javax.lang.model.util.Types;

/**
 * Emits the {@code JoinField} of each {@code @Join} and the {@code ChildField} of each {@code @Child} that a fetch
 * plan names (api/15 R-FCH-03, R-FCH-07). A {@code ChildField} reads the child's QModel only inside its methods, so a
 * {@code @Child} and a back-{@code @Join} between two models initialise in either order.
 *
 * @implSpec R-FCH-03, R-FCH-07, R-GEN-25, R-GEN-26
 */
final class FetchFieldWriter {

    private static final String CORE = "com.rey.modelquery.core";
    private static final ClassName CHILD_FIELD = ClassName.get(CORE, "ChildField");
    private static final ClassName JOIN_FIELD = ClassName.get(CORE, "JoinField");
    private static final ClassName COLUMN_FIELD = ClassName.get(CORE, "ColumnField");
    private static final ClassName TABLE_FIELD = ClassName.get(CORE, "TableField");
    private static final ClassName MODEL_QUERY = ClassName.get(CORE, "ModelQuery");
    private static final ClassName INCUBATING = ClassName.get("com.rey.modelquery.annotations", "Incubating");
    private static final ClassName JOIN_TYPE = ClassName.get("jakarta.persistence.criteria", "JoinType");
    private static final ClassName OPTIONAL = ClassName.get(Optional.class);
    private static final ClassName LIST = ClassName.get(List.class);
    private static final WildcardTypeName ANY = WildcardTypeName.subtypeOf(Object.class);
    private static final Modifier[] CONSTANT = {Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL};

    private final Types types;
    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;

    FetchFieldWriter(Types types, EntityMetamodel metamodel, NestedModels nestedModels) {
        this.types = types;
        this.metamodel = metamodel;
        this.nestedModels = nestedModels;
    }

    /**
     * {@code CUSTOMER_JOIN}: the {@code JoinField} of the {@code @Join} {@code field}, on the join its
     * {@code CUSTOMER_TABLE} declares. A class model's field is read through its getter, named as Lombok names it.
     */
    FieldSpec joinField(ModelDefinition model, ModelField field) {
        ClassName modelName = ClassName.get(model.type());
        TypeName nested = TypeName.get(field.join().nested());
        TypeName type = ParameterizedTypeName.get(JOIN_FIELD, modelName, nested);
        String getter = model.isRecord() ? field.name() : "get" + ChangesWriter.capitalized(field.name());
        MethodSpec.Builder with = method("with", modelName)
                .addParameter(modelName, "parent")
                .addParameter(nested, "nested");
        copy(model, field, CodeBlock.of("$T.of(nested)", OPTIONAL), with);
        TypeSpec body = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(type)
                .addMethod(method("name", ClassName.get(String.class))
                        .addStatement("return $S", field.name())
                        .build())
                .addMethod(method("model", ParameterizedTypeName.get(ClassName.get(Class.class), modelName))
                        .addStatement("return $T.class", modelName)
                        .build())
                .addMethod(method("table", ParameterizedTypeName.get(TABLE_FIELD, ANY, ANY))
                        .addStatement("return $L_TABLE", field.join().prefix())
                        .build())
                .addMethod(method("get", ParameterizedTypeName.get(OPTIONAL, nested))
                        .addParameter(modelName, "parent")
                        .addStatement("return parent.$L()", getter)
                        .build())
                .addMethod(with.build())
                .build();
        return FieldSpec.builder(type, field.join().prefix() + "_JOIN", CONSTANT)
                .addAnnotation(INCUBATING)
                .initializer("$L", body)
                .build();
    }

    /**
     * The {@code ChildField} of the {@code @Child} {@code field}, named as a column of the field would be. Its key
     * columns read the attribute values on each side, without converter (R-FCH-05), and the child's QModel is named
     * in {@code query()} alone. A {@code through} child carries its path as {@code INNER} joins from the parent's
     * {@code ROOT} in place of a foreign key (R-FCH-14).
     */
    FieldSpec childField(ModelDefinition model, ModelField field) {
        ChildDefinition definition = field.child();
        ModelDefinition child = nestedModels.child(field);
        ClassName modelName = ClassName.get(model.type());
        ClassName childName = ClassName.get(child.type());
        TypeName type = ParameterizedTypeName.get(CHILD_FIELD, modelName, childName);
        ChildKey key = ChildKey.of(metamodel, types, model, definition.key());
        boolean through = !definition.through().isEmpty();
        TypeName foreignKeyType = ParameterizedTypeName.get(COLUMN_FIELD, childName, ANY, ANY);
        TypeName throughType = ParameterizedTypeName.get(TABLE_FIELD, ANY, ANY);
        FieldSpec matched = through
                ? FieldSpec.builder(throughType, "through", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer(path(ChildKey.through(metamodel, model.root(), definition.through())))
                        .build()
                : FieldSpec.builder(foreignKeyType, "foreignKey", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer(column(childName, ChildKey.of(metamodel, types, child, definition.foreignKey()),
                                CodeBlock.of("$T.root($T.class)", TABLE_FIELD, ClassName.get(child.root()))))
                        .build();
        TypeName children = ParameterizedTypeName.get(LIST, childName);
        MethodSpec.Builder with = method("with", modelName)
                .addParameter(modelName, "parent")
                .addParameter(children, "children");
        copy(model, field, definition.toMany() ? CodeBlock.of("$T.copyOf(children)", LIST)
                : CodeBlock.of("children.isEmpty() ? $T.empty() : $T.of(children.get(0))", OPTIONAL, OPTIONAL), with);
        TypeSpec body = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(type)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(COLUMN_FIELD, modelName, ANY, ANY), "key",
                                Modifier.PRIVATE, Modifier.FINAL)
                        .initializer(column(modelName, key, CodeBlock.of("ROOT")))
                        .build())
                .addField(matched)
                .addMethod(method("name", ClassName.get(String.class))
                        .addStatement("return $S", field.name())
                        .build())
                .addMethod(method("key", ParameterizedTypeName.get(COLUMN_FIELD, modelName, ANY, ANY))
                        .addStatement("return key")
                        .build())
                .addMethod(method("foreignKey", ParameterizedTypeName.get(OPTIONAL, foreignKeyType))
                        .addStatement(through ? "return $T.empty()" : "return $T.of(foreignKey)", OPTIONAL)
                        .build())
                .addMethod(method("through", ParameterizedTypeName.get(OPTIONAL, throughType))
                        .addStatement(through ? "return $T.of(through)" : "return $T.empty()", OPTIONAL)
                        .build())
                .addMethod(method("isToMany", TypeName.BOOLEAN)
                        .addStatement("return $L", definition.toMany())
                        .build())
                .addMethod(method("query", ParameterizedTypeName.get(MODEL_QUERY.nestedClass("Builder"), ANY, ANY,
                                childName))
                        .addStatement("return $T.query()", QModelWriter.generatedName(child))
                        .build())
                .addMethod(with.build())
                .build();
        return FieldSpec.builder(type, field.constant(), CONSTANT)
                .addAnnotation(INCUBATING)
                .initializer("$L", body)
                .build();
    }

    /**
     * The column {@code key} reads, of {@code model}: on {@code root}, or on the {@code LEFT} joins its path crosses
     * from there, which a {@code @Join} on the same association shares.
     */
    private static CodeBlock column(TypeName model, ChildKey key, CodeBlock root) {
        CodeBlock table = joinChain(root, key.joins(), "", "LEFT");
        return CodeBlock.of("$T.of($T.class,$W$L,$W$S,$W$L)", COLUMN_FIELD, model, table, key.attribute(),
                QModelWriter.classOf(TypeName.get(key.type())));
    }

    /** The {@code through} path {@code through}: its joins from the parent's {@code ROOT}, each {@code INNER}. */
    private static CodeBlock path(ChildKey.Through through) {
        return joinChain(CodeBlock.of("ROOT"), through.joins(), "$Z", "INNER");
    }

    /** {@code table} with {@code joins}' chain appended, each joining with the {@code prefix} and {@code joinType}. */
    private static CodeBlock joinChain(CodeBlock table, List<ChildKey.Join> joins, String prefix, String joinType) {
        for (ChildKey.Join join : joins) {
            table = CodeBlock.of("$T.<$T, $T>join(" + prefix + "$L,$W$S,$W$T." + joinType + ")", TABLE_FIELD,
                    ClassName.get(join.parent()), ClassName.get(join.entity()), table, join.attribute(), JOIN_TYPE);
        }
        return table;
    }

    /**
     * Returns {@code parent} with {@code field} set to {@code value}: a record rebuilt through its canonical
     * constructor, a class through its setter, which sets it on {@code parent} itself.
     */
    private static void copy(ModelDefinition model, ModelField field, CodeBlock value, MethodSpec.Builder method) {
        if (model.isRecord()) {
            CodeBlock arguments = model.fields().stream()
                    .map(component -> component == field ? value : CodeBlock.of("parent.$L()", component.name()))
                    .collect(CodeBlock.joining(",$W"));
            method.addStatement("return new $T($L)", ClassName.get(model.type()), arguments);
        } else {
            method.addStatement("parent.$L($L)", QModelWriter.setter(field), value)
                    .addStatement("return parent");
        }
    }

    private static MethodSpec.Builder method(String name, TypeName returns) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returns);
    }
}
