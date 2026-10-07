package com.rey.modelquery.processor;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * A {@code @QueryModel}, {@code @UpdateModel} or {@code @InsertModel} type as written, before any check: what the
 * validation and emission steps both read.
 *
 * @param type the model class or record
 * @param root the entity named by {@code @QueryModel(root)}
 * @param generatedName the simple name of the QModel class, in the model's package
 * @param selectSets whether {@code ALL} and {@code DEFAULT} are generated
 * @param singleGroup whether {@code @QueryModel(singleGroup)} declares aggregates with no {@code @GroupBy}
 * @param fields the model's fields or record components, in declaration order
 * @param filterColumns the model's {@code @FilterColumn}s, in declaration order
 * @param kind which of the three model annotations the type carries
 * @param generateChanges whether {@code @QueryModel(generateChanges)} asks for a change set
 */
record ModelDefinition(
        TypeElement type, TypeElement root, String generatedName, boolean selectSets, boolean singleGroup,
        List<ModelField> fields, List<FilterColumnDefinition> filterColumns, Kind kind,
        boolean generateChanges) {

    ModelDefinition {
        fields = List.copyOf(fields);
        filterColumns = List.copyOf(filterColumns);
    }

    /** Whether the type is a {@code @QueryModel}, the only kind that is read into. */
    boolean queryModel() {
        return kind == Kind.QUERY;
    }

    /** Whether the type is an {@code @UpdateModel}, which is only read by the processor and never instantiated. */
    boolean updateModel() {
        return kind == Kind.UPDATE;
    }

    /** Whether the type is an {@code @InsertModel}, whose instances are the rows an insert writes (R-PROC-23). */
    boolean insertModel() {
        return kind == Kind.INSERT;
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

    /** The fields carrying {@code @Computed}, in declaration order. */
    List<ModelField> computed() {
        return fields.stream().filter(ModelField::computed).toList();
    }

    /**
     * The fields a {@code SelectSet} can hold, in the order they are emitted: the columns, then the computed
     * expressions, which are declared after every column constant (R-GEN-27).
     */
    List<ModelField> selections() {
        var all = new ArrayList<>(columns());
        all.addAll(computed());
        return all;
    }

    /** Whether a change set is generated: always for an update model, on request for a query model (R-GEN-21). */
    boolean changes() {
        return updateModel() || generateChanges;
    }

    /** The change set's simple name, in the model's package: {@code OrderPatchChanges}. */
    String changesName() {
        return name() + "Changes";
    }

    /**
     * The columns a change set writes, in declaration order: the non-key columns, every one of which is on the root,
     * since a column's path never crosses an association (R-GEN-19, R-PROC-19).
     */
    List<ModelField> writable() {
        return fields.stream().filter(field -> field.column() && !field.primaryKey()).toList();
    }

    /** The {@code @Join} fields, in declaration order. */
    List<ModelField> joins() {
        return fields.stream().filter(field -> field.join() != null).toList();
    }

    /** The {@code @Child} fields, in declaration order. */
    List<ModelField> children() {
        return fields.stream().filter(field -> field.child() != null).toList();
    }

    /** The {@code @Aggregate} fields, in declaration order. */
    List<ModelField> aggregates() {
        return fields.stream().filter(field -> field.aggregate() != null).toList();
    }

    /** The {@code @GroupBy} columns or computed expressions, in declaration order: the members of {@code GROUP_KEYS}. */
    List<ModelField> groupKeys() {
        return fields.stream().filter(field -> (field.column() || field.computed()) && field.groupBy()).toList();
    }

    /** The fields carrying {@code @Selected}, in declaration order: one in a valid model (R-PROC-25). */
    List<ModelField> selected() {
        return fields.stream().filter(ModelField::selected).toList();
    }

    /**
     * Whether the generated class holds the set of every field its mapper reads, which a query model with a
     * {@code @Selected} field fills (R-GEN-29).
     */
    boolean hasSelected() {
        return queryModel() && fields.stream().anyMatch(ModelField::selected);
    }

    /** The {@code @PrimaryKey} columns, in declaration order. */
    List<ModelField> keys() {
        return fields.stream().filter(field -> field.column() && field.primaryKey()).toList();
    }

    /** The annotation a model type carries, which decides what is generated for it. */
    enum Kind {
        /** {@code @QueryModel}: read into, and written through a change set when it asks for one. */
        QUERY,
        /** {@code @UpdateModel}: the columns a bulk update writes, through a change set. */
        UPDATE,
        /** {@code @InsertModel}: the columns an insert writes, read from each row (R-PROC-23). */
        INSERT
    }

    /**
     * One field of a class model, or one component of a record model, read from the record's field of the same name.
     *
     * @param element the field, which diagnostics are reported on
     * @param column {@code false} for a {@code @Transient}, {@code @Join}, {@code @Aggregate} or {@code @Child}
     *     field, which keeps its place in a record's constructor
     * @param attribute the entity attribute path the column reads, dotted through embedded values
     * @param constant the name of the generated column constant
     * @param primaryKey whether the field is a {@code @PrimaryKey}
     * @param excludedFromDefaults whether the field is left out of {@code DEFAULT}
     * @param converter the class named by {@code @Column(converter)}, or {@code null} for none
     * @param definition the class named by {@code @Computed}, or {@code null} for none
     * @param join what {@code @Join} says of the field, or {@code null} when it carries none
     * @param aggregate what {@code @Aggregate} says of the field, or {@code null} when it carries none
     * @param groupBy whether the field carries {@code @GroupBy}
     * @param child what {@code @Child} says of the field, or {@code null} when it carries none; a {@code @Child}
     *     field has no {@code join} and no {@code aggregate}, whatever else it carries
     * @param selected whether the field carries {@code @Selected}, which makes it no column (R-PROC-25)
     */
    record ModelField(
            VariableElement element, boolean column, String attribute, String constant, boolean primaryKey,
            boolean excludedFromDefaults, TypeMirror converter, TypeMirror definition, JoinDefinition join,
            AggregateDefinition aggregate, boolean groupBy, ChildDefinition child, boolean selected) {

        String name() {
            return element.getSimpleName().toString();
        }

        TypeMirror type() {
            return element.asType();
        }

        /** Whether the field carries {@code @Computed}. */
        boolean computed() {
            return definition != null;
        }
    }

    /**
     * A {@code @Join} as written on its field.
     *
     * @param attribute the association on the model's root entity
     * @param type the JPA join type's name, {@code LEFT} or {@code INNER}
     * @param prefix what the join's constants start with: {@code CUSTOMER} for {@code CUSTOMER_TABLE}
     * @param alias the join's alias, the field's name when another {@code @Join} reads the same attribute
     *     (R-PROC-09); {@code ""} for none
     * @param nested {@code X} of a field declared {@code Optional<X>}, or {@code null} for any other type
     */
    record JoinDefinition(String attribute, String type, String prefix, String alias, TypeMirror nested) {}

    /**
     * A {@code @Child} as written on its field.
     *
     * @param key the paths written for {@code key}; empty for the model's {@code @PrimaryKey} attribute
     * @param foreignKey the paths written for {@code foreignKey}; empty for the child's {@code @PrimaryKey} attribute
     * @param through the association path written for {@code through}; {@code ""} for none
     * @param toMany whether the field is a {@code List}, rather than an {@code Optional}
     * @param model {@code X} of a field declared {@code List<X>} or {@code Optional<X>}, or {@code null} for any
     *     other type
     */
    record ChildDefinition(List<String> key, List<String> foreignKey, String through, boolean toMany,
            TypeMirror model) {

        ChildDefinition {
            key = List.copyOf(key);
            foreignKey = List.copyOf(foreignKey);
        }
    }

    /**
     * An {@code @Aggregate} as written on its field.
     *
     * @param fn the function's name: {@code COUNT}, {@code SUM}, {@code AVG}, {@code MIN} or {@code MAX}
     * @param attribute the entity attribute path the function reads; {@code ""} for a {@code COUNT} over the root or
     *     an aggregate over an expression
     * @param expression the class named by {@code expression}, or {@code null} for none
     * @param distinct whether {@code distinct = true} was written
     */
    record AggregateDefinition(String fn, String attribute, TypeMirror expression, boolean distinct) {}

    /**
     * A {@code @FilterColumn} as written on the model's type.
     *
     * @param name the name of the generated column constant
     * @param path the dotted attribute path from the root entity
     * @param joinType the JPA join type's name for an association no {@code @Join} joins, {@code LEFT} or
     *     {@code INNER}
     * @param explicitJoinType whether {@code joinType} was written, which a path across a collection needs and
     *     which alone can differ from the type of a {@code @Join} the path reuses
     * @param alias the alias that puts the path on a join of its own; {@code ""} for none
     * @param converter the class named by {@code converter}, or {@code null} for none
     */
    record FilterColumnDefinition(
            String name, String path, String joinType, boolean explicitJoinType, String alias, TypeMirror converter) {

        /** The column as a diagnostic names it, after the model's name: {@code @FilterColumn(CUSTOMER_COUNTRY)}. */
        String label() {
            return "@FilterColumn(" + name + ")";
        }
    }
}
