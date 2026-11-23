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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private static final ClassName VALUE_CHILD_FIELD = ClassName.get(CORE, "ValueChildField");
    private static final ClassName MAP_CHILD_FIELD = ClassName.get(CORE, "MapChildField");
    private static final ClassName JOIN_FIELD = ClassName.get(CORE, "JoinField");
    private static final ClassName COLUMN_FIELD = ClassName.get(CORE, "ColumnField");
    private static final ClassName TABLE_FIELD = ClassName.get(CORE, "TableField");
    private static final ClassName MODEL_QUERY = ClassName.get(CORE, "ModelQuery");
    private static final ClassName INCUBATING = ClassName.get("com.rey.modelquery.annotations", "Incubating");
    private static final ClassName JOIN_TYPE = ClassName.get("jakarta.persistence.criteria", "JoinType");
    private static final ClassName OPTIONAL = ClassName.get(Optional.class);
    private static final ClassName CLASS = ClassName.get(Class.class);
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName MAP = ClassName.get(Map.class);
    private static final WildcardTypeName ANY = WildcardTypeName.subtypeOf(Object.class);
    private static final Modifier[] CONSTANT = {Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL};

    private final Types types;
    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;
    private final Nullness nullness;

    FetchFieldWriter(Types types, EntityMetamodel metamodel, NestedModels nestedModels, Nullness nullness) {
        this.nullness = nullness;
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
                .addMethod(nullness.unmarked(with).build())
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
        ChildKey key = ChildKey.of(metamodel, types, model, child.elementModel()
                ? ChildKey.valueKey(metamodel, model, definition) : definition.key());
        boolean through = !definition.through().isEmpty();
        TypeName foreignKeyType = ParameterizedTypeName.get(COLUMN_FIELD, childName, ANY, ANY);
        TypeName throughType = ParameterizedTypeName.get(TABLE_FIELD, ANY, ANY);
        FieldSpec matched = through
                ? FieldSpec.builder(throughType, "through", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer(child.elementModel()
                                ? elementPath(ChildKey.through(metamodel, model.root(), definition.through()))
                                : path(ChildKey.through(metamodel, model.root(), definition.through())))
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
        TypeSpec.Builder body = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(type)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(COLUMN_FIELD, modelName, ANY, ANY), "key",
                                Modifier.PRIVATE, Modifier.FINAL)
                        .initializer(keyColumn(model, key))
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
                        .addStatement(child.elementModel() ? "return $T.builder($T.root($T.class), $T.MAPPER)"
                                        : "return $T.query()",
                                child.elementModel() ? new Object[] {MODEL_QUERY, TABLE_FIELD,
                                        ClassName.get(child.root()), QModelWriter.generatedName(child)}
                                        : new Object[] {QModelWriter.generatedName(child)})
                        .build())
                .addMethod(nullness.unmarked(with).build());
        if (child.elementModel()) {
            boolean ordered = ChildKey.through(metamodel, model.root(), definition.through()).elements()
                    .elements().ordered();
            body.addMethod(method("isElement", TypeName.BOOLEAN).addStatement("return true").build())
                    .addMethod(method("isOrderedElement", TypeName.BOOLEAN)
                            .addStatement("return $L", ordered).build());
        }
        return FieldSpec.builder(type, field.constant(), CONSTANT)
                .addAnnotation(INCUBATING)
                .initializer("$L", body.build())
                .build();
    }

    /**
     * The anonymous body every element collection child field shares: its parent {@code key}, its {@code owner} path,
     * its name and the element collection's {@code attribute}.
     */
    private TypeSpec.Builder elementChildBody(ModelDefinition model, ModelField field, ChildKey.Through through,
            TypeName type, ChildKey key) {
        ClassName modelName = ClassName.get(model.type());
        TypeName table = ParameterizedTypeName.get(TABLE_FIELD, ANY, ANY);
        return TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(type)
                .addField(FieldSpec.builder(ParameterizedTypeName.get(COLUMN_FIELD, modelName, ANY, ANY), "key",
                                Modifier.PRIVATE, Modifier.FINAL)
                        .initializer(keyColumn(model, key))
                        .build())
                .addField(FieldSpec.builder(table, "owner", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer(path(through))
                        .build())
                .addMethod(method("name", ClassName.get(String.class))
                        .addStatement("return $S", field.name())
                        .build())
                .addMethod(method("key", ParameterizedTypeName.get(COLUMN_FIELD, modelName, ANY, ANY))
                        .addStatement("return key")
                        .build())
                .addMethod(method("owner", table)
                        .addStatement("return owner")
                        .build())
                .addMethod(method("attribute", ClassName.get(String.class))
                        .addStatement("return $S", through.elements().name())
                        .build());
    }

    /**
     * The {@code ValueChildField} of the value child {@code field}, whose {@code through} path ends at an element
     * collection: its owner is the path's to-one joins from the parent's {@code ROOT}, each {@code INNER}, and
     * {@code with} copies the values into an unmodifiable {@code List}, or {@code LinkedHashSet} for a {@code Set},
     * that keeps {@code null}s (R-FCH-19, D-247).
     */
    FieldSpec valueChildField(ModelDefinition model, ModelField field) {
        ChildDefinition definition = field.child();
        ChildKey.Through through = ChildKey.elementsOf(metamodel, model, field);
        EntityAttribute.Elements elements = through.elements().elements();
        ClassName modelName = ClassName.get(model.type());
        TypeName element = TypeName.get(definition.model());
        TypeName type = ParameterizedTypeName.get(VALUE_CHILD_FIELD, modelName, element);
        ChildKey key = ChildKey.of(metamodel, types, model, ChildKey.valueKey(metamodel, model, definition));
        MethodSpec.Builder with = method("with", modelName)
                .addParameter(modelName, "parent")
                .addParameter(ParameterizedTypeName.get(LIST, element), "values");
        copy(model, field, definition.set()
                ? CodeBlock.of("$T.unmodifiableSet(new $T<>(values))", Collections.class, LinkedHashSet.class)
                : CodeBlock.of("$T.unmodifiableList(new $T<>(values))", Collections.class, ArrayList.class), with);
        TypeSpec body = elementChildBody(model, field, through, type, key)
                .addMethod(method("elementType", ParameterizedTypeName.get(CLASS, element))
                        .addStatement("return $T.class", element)
                        .build())
                .addMethod(method("isOrdered", TypeName.BOOLEAN)
                        .addStatement("return $L", elements.ordered())
                        .build())
                .addMethod(method("ordersByValue", TypeName.BOOLEAN)
                        .addStatement("return $L", !elements.lob())
                        .build())
                .addMethod(nullness.unmarked(with).build())
                .build();
        return FieldSpec.builder(type, field.constant(), CONSTANT)
                .addAnnotation(INCUBATING)
                .initializer("$L", body)
                .build();
    }

    /**
     * The {@code MapChildField} of the map child {@code field}, whose {@code through} path ends at an element
     * collection: its owner is the path's to-one joins from the parent's {@code ROOT}, each {@code INNER}, and
     * {@code with} copies the entries into an unmodifiable {@code LinkedHashMap} in row order (R-FCH-20, D-248).
     */
    FieldSpec mapChildField(ModelDefinition model, ModelField field) {
        ChildDefinition definition = field.child();
        ChildKey.Through through = ChildKey.elementsOf(metamodel, model, field);
        ClassName modelName = ClassName.get(model.type());
        TypeName keyType = TypeName.get(definition.keyType());
        TypeName valueType = TypeName.get(definition.model());
        TypeName type = ParameterizedTypeName.get(MAP_CHILD_FIELD, modelName, keyType, valueType);
        ChildKey key = ChildKey.of(metamodel, types, model, ChildKey.valueKey(metamodel, model, definition));
        MethodSpec.Builder with = method("with", modelName)
                .addParameter(modelName, "parent")
                .addParameter(ParameterizedTypeName.get(MAP, keyType, valueType), "entries");
        copy(model, field, CodeBlock.of("$T.unmodifiableMap(new $T<>(entries))",
                Collections.class, LinkedHashMap.class), with);
        TypeSpec.Builder body = elementChildBody(model, field, through, type, key)
                .addMethod(method("keyType", ParameterizedTypeName.get(CLASS, keyType))
                        .addStatement("return $T.class", keyType)
                        .build())
                .addMethod(method("valueType", ParameterizedTypeName.get(CLASS, valueType))
                        .addStatement("return $T.class", valueType)
                        .build())
                .addMethod(nullness.unmarked(with).build());
        ModelDefinition child = nestedModels.child(field);
        if (child != null && child.elementModel()) {
            ClassName childGenerated = QModelWriter.generatedName(child);
            body.addMethod(method("isElementModel", TypeName.BOOLEAN)
                            .addStatement("return true").build())
                    .addMethod(method("elementQuery", ParameterizedTypeName.get(MODEL_QUERY.nestedClass("Builder"),
                                    ANY, ANY, valueType))
                            .addStatement("return $T.builder($T.root($T.class), $T.MAPPER).select($T.ALL)",
                                    MODEL_QUERY, TABLE_FIELD, ClassName.get(child.root()),
                                    childGenerated, childGenerated)
                            .build());
        }
        return FieldSpec.builder(type, field.constant(), CONSTANT)
                .addAnnotation(INCUBATING)
                .initializer("$L", body.build())
                .build();
    }

    /**
     * The parent-side key column of {@code model}, read on the generated {@code ROOT}: on an entity root the column
     * {@code key} names, and on a source root the source's own constant through {@code ColumnField.over(...)}, which
     * reads the CTE column the source aliases the field by (api/17 R-SRC-10). {@code ColumnField.of(...)} refuses a
     * source root ({@code MQ1905}), so the source's constant is named.
     */
    private static CodeBlock keyColumn(ModelDefinition model, ChildKey key) {
        if (!model.readsSource()) {
            return column(ClassName.get(model.type()), key, CodeBlock.of("ROOT"));
        }
        ModelField source = model.sourceField(key.attribute());
        return CodeBlock.of("$T.over($T.class,$WROOT,$W$T.$L)", COLUMN_FIELD, ClassName.get(model.type()),
                QModelWriter.generatedName(model.source()), source.constant());
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

    private static CodeBlock elementPath(ChildKey.Through through) {
        return CodeBlock.of("$T.join($L, $S, $T.INNER)", TABLE_FIELD, path(through),
                through.elements().name(), JOIN_TYPE);
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
