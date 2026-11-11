package com.rey.modelquery.processor;

import com.rey.modelquery.processor.ModelDefinition.EntityJoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.JoinSourceDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.List;

/**
 * The source and entity joins a nested model declares, as the outer model registers them under the prefix of the
 * {@code @Join} that reaches the model: each is a fresh constant {@code <PREFIX>_<JOIN>} registered within the
 * {@code @Join}'s table, so two fields of one nested type never share a join (R-GEN-43, D-214).
 *
 * @param table the join of the outer model the nested model sits under, or the nested one of that join
 * @param source the nested model's {@code @JoinSource}, or {@code null}
 * @param entity the nested model's {@code @JoinEntity}, or {@code null}
 */
record NestedJoins(JoinedTable table, JoinSourceDefinition source, EntityJoinDefinition entity) {

    /** The join's name as declared on the nested model. */
    String declared() {
        return source != null ? source.name() : entity.name();
    }

    /** The outer join's name and, for a source, the parameter of {@code query(...)}: {@code customerTotals}. */
    String name() {
        return name(table, declared());
    }

    /** The outer constant: {@code CUSTOMER_TOTALS}. */
    String constant() {
        return table.prefix() + "_" + QueryModelReader.constantName(declared());
    }

    /** The constant as the nested model's own QModel names it. */
    String inNested() {
        return table.inNested(constant());
    }

    /** The outer join name of the nested model's join {@code declared}: the field path in camel case, then the name. */
    static String name(JoinedTable table, String declared) {
        var name = new StringBuilder();
        for (String segment : table.path().split("\\.")) {
            name.append(name.isEmpty() ? segment : ChangesWriter.capitalized(segment));
        }
        return name.append(ChangesWriter.capitalized(declared)).toString();
    }

    /** The joins the model of {@code table} declares, sources first: the order they are registered and scoped in. */
    static List<NestedJoins> of(JoinedTable table, NestedModels nestedModels) {
        ModelDefinition model = nestedModels.of(table.declaration());
        var joins = new ArrayList<NestedJoins>();
        if (model == null) {
            return joins;
        }
        model.joinSources().forEach(join -> joins.add(new NestedJoins(table, join, null)));
        model.entityJoins().forEach(join -> joins.add(new NestedJoins(table, null, join)));
        return joins;
    }

    /** Every such join of {@code joined}, depth-first as the tables are listed. */
    static List<NestedJoins> all(List<JoinedTable> joined, NestedModels nestedModels) {
        return joined.stream().flatMap(table -> of(table, nestedModels).stream()).toList();
    }

    /** Whether the {@code @Join} {@code field}, or a join below it, reaches a model with joins of its own. */
    static boolean any(ModelField field, List<JoinedTable> joined, NestedModels nestedModels) {
        return joined.stream().filter(table -> table.join().equals(field))
                .anyMatch(table -> !of(table, nestedModels).isEmpty());
    }
}
