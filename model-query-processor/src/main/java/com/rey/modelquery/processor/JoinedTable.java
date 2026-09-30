package com.rey.modelquery.processor;

import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * One join a QModel declares: a {@code @Join} of the model itself, or a join of its nested model re-rooted under
 * one. Either way its constants are read from the QModel of the {@code @Join}'s own nested model, which already
 * declares every join below it (R-GEN-04, R-GEN-14).
 *
 * @param join the model's {@code @Join} field this join is, or sits under
 * @param nested the model {@code join} nests, whose QModel the constants are derived from
 * @param prefix what the join's constants start with: {@code CUSTOMER_TABLE}, the {@code ColumnSet}
 *     {@code CUSTOMER}, a column {@code CUSTOMER_ID}
 * @param parent the prefix of the join this one hangs from, or {@code null} for a {@code @Join} of the model itself
 * @param path the fields that lead to the join, as a diagnostic names it: {@code customer.address}
 * @param parentEntity the entity the join starts at
 * @param entity the entity the join reaches
 * @param columns the columns read through the join
 */
record JoinedTable(
        ModelField join, ModelDefinition nested, String prefix, String parent, String path,
        TypeElement parentEntity, TypeElement entity, List<JoinedColumn> columns) {

    JoinedTable {
        columns = List.copyOf(columns);
    }

    /** The name of the join's {@code TableField} constant. */
    String table() {
        return prefix + "_TABLE";
    }

    /** {@code constant}, one of this join's, as the nested model's QModel names it: without the join's prefix. */
    String inNested(String constant) {
        return constant.substring(join.join().prefix().length() + 1);
    }

    /**
     * A column of a nested model, as the outer model declares it.
     *
     * @param constant the name of the outer model's constant
     * @param type the column's model type
     * @param path the fields that lead to it, as a diagnostic names it: {@code customer.id}
     */
    record JoinedColumn(String constant, TypeMirror type, String path) {}
}
