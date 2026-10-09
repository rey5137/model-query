package com.rey.modelquery.processor;

import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.List;

/**
 * What a change set writes: every non-key column of an update model; or the non-key root columns of a
 * {@code generateChanges} query model but its to-one columns, which hold the target entity and so could never be
 * written by id. Each to-one left out is named with the reason, which the change set's Javadoc lists.
 *
 * @param written the columns written, in declaration order
 * @param leftOut each to-one column left out, as the Javadoc names it: its name, then the reason
 * @implSpec R-PROC-19, R-GEN-19, R-GEN-21
 */
record ChangedColumns(List<ModelField> written, List<String> leftOut) {

    static ChangedColumns of(ModelDefinition model, EntityMetamodel metamodel) {
        if (!model.queryModel()) {
            return new ChangedColumns(model.writable(), List.of());
        }
        var written = new ArrayList<ModelField>();
        var leftOut = new ArrayList<String>();
        for (ModelField field : model.writable()) {
            if (InsertedColumns.toOne(metamodel.resolve(model.root(), field.attribute()))) {
                leftOut.add("{@code " + field.name()
                        + "}: a to-one; only an {@code @UpdateModel} writes a foreign key");
            } else {
                written.add(field);
            }
        }
        return new ChangedColumns(written, leftOut);
    }
}
