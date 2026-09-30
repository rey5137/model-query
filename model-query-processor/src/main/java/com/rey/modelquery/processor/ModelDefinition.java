package com.rey.modelquery.processor;

import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * A {@code @QueryModel} type as written, before any check: what the validation and emission steps both read.
 *
 * @param type the model class or record
 * @param root the entity named by {@code @QueryModel(root)}
 * @param generatedName the simple name of the QModel class, in the model's package
 * @param columnSets whether {@code ALL} and {@code DEFAULT} are generated
 * @param fields the model's fields or record components, in declaration order
 */
record ModelDefinition(
        TypeElement type, TypeElement root, String generatedName, boolean columnSets, List<ModelField> fields) {

    ModelDefinition {
        fields = List.copyOf(fields);
    }

    boolean isRecord() {
        return type.getKind() == ElementKind.RECORD;
    }

    /** The model's simple name, as diagnostics name it. */
    String name() {
        return type.getSimpleName().toString();
    }

    /** The fields that are columns, in declaration order. */
    List<ModelField> columns() {
        return fields.stream().filter(ModelField::column).toList();
    }

    /** The {@code @PrimaryKey} columns, in declaration order. */
    List<ModelField> keys() {
        return fields.stream().filter(field -> field.column() && field.primaryKey()).toList();
    }

    /**
     * One field of a class model, or one component of a record model, read from the record's field of the same name.
     *
     * @param element the field, which diagnostics are reported on
     * @param column {@code false} for a {@code @Transient} field, which keeps its place in a record's constructor
     * @param attribute the entity attribute path the column reads, dotted through embedded values
     * @param constant the name of the generated column constant
     * @param primaryKey whether the field is a {@code @PrimaryKey}
     * @param excludedFromDefaults whether the field is left out of {@code DEFAULT}
     */
    record ModelField(
            VariableElement element, boolean column, String attribute, String constant, boolean primaryKey,
            boolean excludedFromDefaults) {

        String name() {
            return element.getSimpleName().toString();
        }

        TypeMirror type() {
            return element.asType();
        }
    }
}
