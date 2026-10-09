package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.rey.modelquery.processor.ModelDefinition.FilterColumnDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.List;

/**
 * What {@code INSERT_COLUMNS} holds: every column of an insert model, keyed by {@code @PrimaryKey}; or the root
 * columns of a {@code generateInserts} query model, keyed by the root's id, and each field or filter column it leaves
 * out with the reason, which the constant's Javadoc lists.
 *
 * @param written the columns written, in declaration order
 * @param leftOut each field or filter column left out, as the Javadoc names it: its name, then the reason
 * @implSpec R-PROC-26, R-GEN-28, R-GEN-34
 */
record InsertedColumns(List<Written> written, List<String> leftOut) {

    /** One column written, and whether it is added with {@code addKey}. */
    record Written(ModelField field, boolean key) {}

    static InsertedColumns of(ModelDefinition model, EntityMetamodel metamodel) {
        if (model.insertModel()) {
            return new InsertedColumns(
                    model.columns().stream().map(field -> new Written(field, field.primaryKey())).toList(), List.of());
        }
        EntityMetamodel.Id id = metamodel.id(model.root());
        var written = new ArrayList<Written>();
        var leftOut = new ArrayList<String>();
        for (ModelField field : model.fields()) {
            String reason = field.column() ? leftOut(metamodel.resolve(model.root(), field.attribute()), id,
                    field.attribute()) : notAColumn(field);
            if (reason == null) {
                written.add(new Written(field, id.covers(field.attribute())));
            } else {
                leftOut.add("{@code " + field.name() + "}: " + reason);
            }
        }
        for (FilterColumnDefinition column : model.filterColumns()) {
            leftOut.add("{@code " + column.label() + "}: filter-only, no field of the model");
        }
        return new InsertedColumns(written, leftOut);
    }

    /** Why a field that is no column is left out. */
    private static String notAColumn(ModelField field) {
        if (field.child() != null) {
            return "{@code @Child}, rows of another model";
        }
        if (field.join() != null) {
            return "{@code @Join}, columns of another table";
        }
        if (field.aggregate() != null) {
            return "{@code @Aggregate}, a group's value";
        }
        if (field.computed()) {
            return "{@code @Computed}, an expression";
        }
        if (field.selected()) {
            return "{@code @Selected}, the columns the row selected";
        }
        return field.element().getAnnotation(Transient.class) != null ? "{@code @Transient}, no column" : "no column";
    }

    /**
     * Why a root column is left out, or {@code null} when it is written. A path that does not resolve is written: it
     * is {@code MQ3001} or {@code MQ3002}, and nothing is generated.
     */
    private static String leftOut(Resolution resolution, EntityMetamodel.Id id, String path) {
        EntityAttribute attribute = resolution.attribute();
        if (resolution.problem() != null || attribute == null) {
            return null;
        }
        if (attribute.kind() == EntityAttribute.Kind.TO_ONE) {
            return "a to-one; only an {@code @InsertModel} writes a foreign key";
        }
        if (id.generated() && id.covers(path)) {
            return "the id, which is generated";
        }
        if (attribute.version()) {
            return "the {@code @Version}, which the provider writes";
        }
        return attribute.insertable() ? null : "{@code @Column(insertable = false)}";
    }
}
