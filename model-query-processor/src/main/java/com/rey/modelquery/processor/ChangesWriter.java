package com.rey.modelquery.processor;

import com.rey.modelquery.processor.ModelDefinition.ModelField;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Emits the change set of a validated update model, or of a query model with {@code generateChanges = true}
 * (spec processor/31 §6). The change set is a mutable value builder, never a constant (R-GEN-20): one bit per
 * writable column records that its setter was called, so NULL and "not set" stay apart (api/14 R-WRT-02).
 *
 * <p>When Bean Validation and {@code @ValidChanges} both resolve, the change set is annotated {@code @ValidChanges}
 * naming its model; the model's own constraint annotations are never copied onto it (R-GEN-23).
 *
 * @implSpec R-GEN-19, R-GEN-20, R-GEN-21, R-GEN-23, R-WRT-02, R-WRT-03, R-WRT-04
 */
final class ChangesWriter {

    /** The class-level constraint a change set carries when it and {@link #CONSTRAINT} resolve (api/14 R-WRT-22). */
    static final String VALID_CHANGES = "com.rey.modelquery.jpa.ValidChanges";
    static final String CONSTRAINT = "jakarta.validation.Constraint";

    private static final String CORE = "com.rey.modelquery.core";
    private static final ClassName CHANGES = ClassName.get(CORE, "Changes");
    private static final ClassName ASSIGNMENT = ClassName.get(CORE, "Assignment");
    private static final ClassName COLUMN_FIELD = ClassName.get(CORE, "ColumnField");
    private static final ClassName SELECT_SET = ClassName.get(CORE, "SelectSet");
    private static final ClassName SELECT_FIELD = ClassName.get(CORE, "SelectField");
    private static final ClassName DEFINITION_EXCEPTION = ClassName.get(CORE, "ModelQueryDefinitionException");
    private static final ClassName MQ_CODE = ClassName.get(CORE, "MqCode");
    private static final WildcardTypeName ANY = WildcardTypeName.subtypeOf(Object.class);

    private final Types types;
    private final EntityMetamodel metamodel;
    private final boolean validChanges;

    /**
     * @param metamodel the entities' attributes, to tell a to-one column
     * @param validChanges whether {@link #VALID_CHANGES} and {@link #CONSTRAINT} resolve on the classpath
     */
    ChangesWriter(Types types, EntityMetamodel metamodel, boolean validChanges) {
        this.types = types;
        this.metamodel = metamodel;
        this.validChanges = validChanges;
    }

    /** The change-set file of {@code model}, whose only originating element is the model's type (R-GEN-05). */
    JavaFile write(ModelDefinition model) {
        ClassName modelName = ClassName.get(model.type());
        String packageName = modelName.packageName();
        ClassName qModel = ClassName.get(packageName, model.generatedName());
        ClassName changes = ClassName.get(packageName, model.changesName());
        ChangedColumns changed = ChangedColumns.of(model, metamodel);
        List<ModelField> writable = changed.written();
        // The bit set is named "set" unless a writable field already is.
        String bits = writable.stream().anyMatch(field -> field.name().equals("set")) ? "setColumns" : "set";
        TypeName assignment = ParameterizedTypeName.get(ASSIGNMENT, modelName, ANY);
        TypeName anyColumn = ParameterizedTypeName.get(COLUMN_FIELD, modelName, ANY, ANY);

        TypeSpec.Builder type = TypeSpec.classBuilder(changes)
                .addOriginatingElement(model.type())
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", ModelQueryProcessor.class.getCanonicalName())
                        .build())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(CHANGES, modelName))
                .addField(FieldSpec.builder(BitSet.class, bits, Modifier.PRIVATE, Modifier.FINAL)
                        .initializer("new $T()", BitSet.class)
                        .build());
        if (!changed.leftOut().isEmpty()) {
            type.addJavadoc("The columns of {@link $T} it writes; left out:\n<ul>\n", modelName);
            changed.leftOut().forEach(column -> type.addJavadoc("<li>$L</li>\n", column));
            type.addJavadoc("</ul>\n");
        }
        if (validChanges) {
            type.addAnnotation(AnnotationSpec.builder(ClassName.bestGuess(VALID_CHANGES))
                    .addMember("value", "$T.class", modelName)
                    .build());
        }
        for (ModelField field : writable) {
            type.addField(boxed(field.type()), field.name(), Modifier.PRIVATE);
        }
        // Binders such as Jackson create the change set through it, then call only the setters of present properties.
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build());
        for (int i = 0; i < writable.size(); i++) {
            ModelField field = writable.get(i);
            TypeName value = boxed(field.type());
            String property = capitalized(field.name());
            type.addMethod(MethodSpec.methodBuilder(field.name())
                    .addModifiers(Modifier.PUBLIC)
                    .returns(changes)
                    .addParameter(value, "value")
                    .addStatement("this.$N = value", field.name())
                    .addStatement("this.$N.set($L)", bits, i)
                    .addStatement("return this")
                    .build());
            type.addMethod(MethodSpec.methodBuilder("set" + property)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(value, "value")
                    .addStatement("this.$N(value)", field.name())
                    .build());
            type.addMethod(MethodSpec.methodBuilder("get" + property)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(value)
                    .addStatement("return this.$N", field.name())
                    .build());
        }

        MethodSpec.Builder isSet = override("isSet", TypeName.BOOLEAN).addParameter(anyColumn, "column");
        MethodSpec.Builder unset = override("unset", changes).addParameter(anyColumn, "column");
        MethodSpec.Builder assignments = override("assignments", ParameterizedTypeName.get(ClassName.get(List.class),
                assignment))
                .addStatement("$T assignments = new $T<>()", ParameterizedTypeName.get(ClassName.get(List.class),
                        assignment), ArrayList.class);
        for (int i = 0; i < writable.size(); i++) {
            ModelField field = writable.get(i);
            isSet.beginControlFlow("if ($T.$N.equals(column))", qModel, field.constant())
                    .addStatement("return this.$N.get($L)", bits, i)
                    .endControlFlow();
            unset.beginControlFlow("if ($T.$N.equals(column))", qModel, field.constant())
                    .addStatement("this.$N = null", field.name())
                    .addStatement("this.$N.clear($L)", bits, i)
                    .addStatement("return this")
                    .endControlFlow();
            assignments.beginControlFlow("if (this.$N.get($L))", bits, i)
                    .addStatement("assignments.add(assignment($T.$N, this.$N))", qModel, field.constant(), field.name())
                    .endControlFlow();
        }
        type.addMethod(isSet.addStatement("return false").build());
        type.addMethod(unset.addStatement("return this").build());
        type.addMethod(override("isEmpty", TypeName.BOOLEAN).addStatement("return this.$N.isEmpty()", bits).build());
        // Model values, NULLs included: the engine converts each once, when it binds it (D-37, D-60).
        type.addMethod(assignments.addStatement("return $T.unmodifiableList(assignments)", Collections.class)
                .build());
        TypeVariableName c = TypeVariableName.get("C");
        type.addMethod(MethodSpec.methodBuilder("assignment")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addTypeVariable(c)
                .returns(ParameterizedTypeName.get(ASSIGNMENT, modelName, c))
                .addParameter(ParameterizedTypeName.get(COLUMN_FIELD, modelName, ANY, c), "column")
                .addParameter(c, "value")
                .addStatement("return value == null ? $T.ofNull(column) : $T.of(column, value)", ASSIGNMENT,
                        ASSIGNMENT)
                .build());
        if (!model.updateModel()) {
            type.addMethod(from(model, writable));
        }
        return JavaFile.builder(packageName, type.build())
                .indent("    ")
                .skipJavaLangImports(true)
                .build();
    }

    /**
     * {@code from(model, columns)}: copies each named column from the model's getter or record accessor, NULLs
     * included, and throws {@code MQ1607} for a column the change set does not write (R-GEN-21, api/14 R-WRT-04).
     */
    private static MethodSpec from(ModelDefinition model, List<ModelField> writable) {
        ClassName modelName = ClassName.get(model.type());
        ClassName qModel = ClassName.get(modelName.packageName(), model.generatedName());
        ClassName changes = ClassName.get(modelName.packageName(), model.changesName());
        MethodSpec.Builder from = MethodSpec.methodBuilder("from")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(changes)
                .addParameter(modelName, "model")
                .addParameter(ParameterizedTypeName.get(SELECT_SET, modelName), "columns")
                .addStatement("$T.requireNonNull(model, $S)", Objects.class, "model")
                .addStatement("$T changes = new $T()", changes, changes)
                .beginControlFlow("for ($T column : $T.requireNonNull(columns, $S).fields())",
                        ParameterizedTypeName.get(SELECT_FIELD, modelName, ANY), Objects.class, "columns");
        for (int i = 0; i < writable.size(); i++) {
            ModelField field = writable.get(i);
            String read = model.isRecord() ? field.name() : getter(field);
            if (i == 0) {
                from.beginControlFlow("if ($T.$N.equals(column))", qModel, field.constant());
            } else {
                from.nextControlFlow("else if ($T.$N.equals(column))", qModel, field.constant());
            }
            from.addStatement("changes.$N(model.$N())", field.name(), read);
        }
        if (!writable.isEmpty()) {
            from.nextControlFlow("else");
        }
        from.addStatement("throw new $T($T.MQ1607, column + $S)", DEFINITION_EXCEPTION, MQ_CODE,
                ": not a column " + model.changesName() + " writes; a change set writes " + model.name()
                        + "'s root, non-key columns only");
        if (!writable.isEmpty()) {
            from.endControlFlow();
        }
        return from.endControlFlow().addStatement("return changes").build();
    }

    private static MethodSpec.Builder override(String name, TypeName returns) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returns);
    }

    /**
     * The getter as Lombok names it: a primitive {@code boolean x} or {@code boolean isX} gets {@code isX}, anything
     * else {@code get} and the capitalised field name (R-GEN-10).
     */
    static String getter(ModelField field) {
        String name = field.name();
        if (field.type().getKind() != TypeKind.BOOLEAN) {
            return "get" + capitalized(name);
        }
        boolean prefixed = name.length() > 2 && name.startsWith("is") && !Character.isLowerCase(name.charAt(2));
        return prefixed ? name : "is" + capitalized(name);
    }

    static String capitalized(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** A change-set value's type: the field's, boxed, since every column may be set to NULL. */
    private TypeName boxed(TypeMirror type) {
        return TypeName.get(ProcessorTypes.boxed(types, type));
    }
}
