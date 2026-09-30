package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.processor.JoinedTable.JoinedColumn;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

/**
 * Reads the models that {@code @Join} fields nest, and lays out the constants an outer model derives from them. A
 * nested model is read from its own fields and its QModel is only named, never looked up, so the order in which
 * models are processed does not matter (R-GEN-04, D-39).
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
                        .map(column -> new JoinedColumn(
                                prefix + "_" + column.constant(), column.type(), join.name() + "." + column.name()))
                        .toList()));
        for (JoinedTable below : tables(nested)) {
            tables.add(new JoinedTable(
                    join, nested, prefix + "_" + below.prefix(),
                    below.parent() == null ? prefix : prefix + "_" + below.parent(),
                    join.name() + "." + below.path(), join.join().attribute() + "." + below.attributes(),
                    below.parentEntity(), below.entity(), below.joinType(),
                    below.columns().stream()
                            .map(column -> new JoinedColumn(prefix + "_" + column.constant(), column.type(),
                                    join.name() + "." + column.path()))
                            .toList()));
        }
        return tables;
    }
}
