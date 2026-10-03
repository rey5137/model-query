package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.processor.JoinedTable.JoinedColumn;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Reads the models that {@code @Join} fields nest and {@code @Child} fields hold, and lays out the constants an outer
 * model derives from nested ones. Such a model is read from its own fields and its QModel is only named, never looked
 * up, so the order in which models are processed does not matter (R-GEN-04, D-39).
 */
final class NestedModels {

    private final QueryModelReader reader;
    private final Map<TypeElement, ModelDefinition> read = new HashMap<>();

    NestedModels(QueryModelReader reader) {
        this.reader = reader;
    }

    /**
     * The model {@code join} nests, or {@code null} when its field is not an {@code Optional} of a
     * {@code @QueryModel}. The model may be a source of this compilation or a class on its classpath: a class file
     * keeps the fields, record components and {@code CLASS}-retained annotations that are read here (D-45).
     */
    ModelDefinition of(ModelField join) {
        if (join.join().nested() == null) {
            return null;
        }
        var type = (TypeElement) ((DeclaredType) join.join().nested()).asElement();
        return type.getAnnotation(QueryModel.class) != null ? read.computeIfAbsent(type, reader::read) : null;
    }

    /**
     * The model a {@code @Child} field holds, or {@code null} when its field is not a {@code List} or
     * {@code Optional} of a {@code @QueryModel}; read as {@link #of} reads a nested model (D-45).
     */
    ModelDefinition child(ModelField child) {
        if (child.child().model() == null) {
            return null;
        }
        var type = (TypeElement) ((DeclaredType) child.child().model()).asElement();
        return type.getAnnotation(QueryModel.class) != null ? read.computeIfAbsent(type, reader::read) : null;
    }

    /**
     * The first model that {@code model}'s {@code @Join} and {@code @Child} fields nest, at any depth, whose
     * {@code root} does not name a class yet, or {@code null} when every one does (D-107).
     */
    TypeElement unresolved(ModelDefinition model) {
        return unresolved(model, new HashSet<>());
    }

    private TypeElement unresolved(ModelDefinition model, Set<TypeElement> seen) {
        for (ModelField field : model.fields()) {
            TypeMirror nested = field.join() != null ? field.join().nested()
                    : field.child() != null ? field.child().model() : null;
            if (nested instanceof DeclaredType declared && declared.asElement() instanceof TypeElement type
                    && type.getAnnotation(QueryModel.class) != null && seen.add(type)) {
                ModelDefinition below = read.computeIfAbsent(type, reader::read);
                TypeElement found = below == null ? type : unresolved(below, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Every join of {@code model}, its own and those below them; the joins must not form a cycle. */
    List<JoinedTable> tables(ModelDefinition model) {
        var tables = new ArrayList<JoinedTable>();
        for (ModelField join : model.joins()) {
            ModelDefinition nested = of(join);
            if (nested != null) {
                tables.addAll(tables(model, join, nested));
            }
        }
        return tables;
    }

    /** The join {@code join} of {@code model} makes to {@code nested}, then the joins of {@code nested} under it. */
    List<JoinedTable> tables(ModelDefinition model, ModelField join, ModelDefinition nested) {
        String prefix = join.join().prefix();
        var tables = new ArrayList<JoinedTable>();
        tables.add(new JoinedTable(
                join, nested, prefix, null, join.name(), join.join().attribute(), model.root(), nested.root(),
                join.join().type(),
                nested.columns().stream()
                        .map(column -> new JoinedColumn(prefix + "_" + column.constant(), column.type(),
                                join.name() + "." + column.name(), nested, column))
                        .toList()));
        for (JoinedTable below : tables(nested)) {
            tables.add(new JoinedTable(
                    join, nested, prefix + "_" + below.prefix(),
                    below.parent() == null ? prefix : prefix + "_" + below.parent(),
                    join.name() + "." + below.path(), join.join().attribute() + "." + below.attributes(),
                    below.parentEntity(), below.entity(), below.joinType(),
                    below.columns().stream()
                            .map(column -> new JoinedColumn(prefix + "_" + column.constant(), column.type(),
                                    join.name() + "." + column.path(), column.owner(), column.field()))
                            .toList()));
        }
        return tables;
    }
}
