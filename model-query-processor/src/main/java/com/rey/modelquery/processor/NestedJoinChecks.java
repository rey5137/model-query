package com.rey.modelquery.processor;

import com.rey.modelquery.processor.ModelDefinition.EntityJoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The checks of a nested model's own {@code @JoinSource} and {@code @JoinEntity} under a {@code @Join} of the model
 * that nests it (R-PROC-44, R-GEN-43): the outer model re-registers each under the {@code @Join}'s prefix, so an
 * anchor it can't re-root, a chain the core refuses, and a constant or join name two prefixes would both produce are
 * {@code MQ3044} here, never a class that fails when it loads.
 */
final class NestedJoinChecks {

    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;
    private final BuiltInConverters builtIns;

    NestedJoinChecks(EntityMetamodel metamodel, NestedModels nestedModels, BuiltInConverters builtIns) {
        this.metamodel = metamodel;
        this.nestedModels = nestedModels;
        this.builtIns = builtIns;
    }

    /**
     * Whether the joins of the models {@code tables} read, under {@code join}, can be generated; reports why not.
     *
     * @param constants what each constant of the outer model is generated for, which the joins' constants join
     * @param names the join names of the outer model, which the nested joins' names join
     */
    boolean check(ModelDefinition model, ModelField join, List<JoinedTable> tables, Map<String, String> constants,
            Set<String> reserved, Set<String> names, String where, Diagnostics diagnostics) {
        boolean valid = true;
        boolean joins = false;
        for (JoinedTable table : tables) {
            ModelDefinition nested = nestedModels.of(table.declaration());
            List<NestedJoins> declared = NestedJoins.of(table, nestedModels);
            joins |= !declared.isEmpty();
            if (declared.isEmpty()) {
                continue;
            }
            if (!join.join().entityJoin().isEmpty() && !chains(model, join, nested, declared, where, diagnostics)) {
                valid = false;
                continue;
            }
            for (NestedJoins each : declared) {
                String kind = each.source() != null ? each.source().kind() : each.entity().kind();
                if (kind.equals("INNER") && optional(table, tables)) {
                    valid = error(join, where + nested.name() + " join '" + each.declared() + "' is INNER below the "
                            + "LEFT @Join '" + join.name() + "', which would drop the outer row when it misses; "
                            + "make it LEFT, or the @Join INNER", diagnostics);
                }
                valid &= claim(join, each, constants, reserved, names, where, diagnostics);
                if (each.entity() != null) {
                    valid &= anchors(join, nested, each.entity(), where, diagnostics);
                }
            }
            valid &= columns(join, nested, where, diagnostics);
        }
        if (joins) {
            String scope = join.join().prefix() + "_SCOPE";
            String earlier = reserved.contains(scope) ? "the generated class"
                    : constants.putIfAbsent(scope, "the scope of " + join.name());
            if (earlier != null && !earlier.equals("the scope of " + join.name())) {
                valid = error(join, where + "constant " + scope + " is also generated for " + earlier
                        + "; rename the field or set @Join(prefix)", diagnostics);
            }
        }
        return valid;
    }

    /**
     * Whether the joins {@code declared} of a model below the {@code @Join(entityJoin)} field {@code join} can be
     * anchored on that entity join, which the outer model re-roots them onto (D-215, D-217, D-218): an entity join or
     * a source join can when the entity join is itself anchored on the root's columns, as each is created after it.
     */
    private static boolean chains(ModelDefinition model, ModelField join, ModelDefinition nested,
            List<NestedJoins> declared, String where, Diagnostics diagnostics) {
        String name = join.join().entityJoin();
        var outer = model.entityJoin(name);
        if (outer != null && !outer.source().isEmpty()) {
            return error(join, where + nested.name() + " joins " + quoted(declared) + " below the entity join '"
                    + name + "', which is anchored on a joined source; only a join anchored on the root's columns "
                    + "may anchor another", diagnostics);
        }
        return true;
    }

    /** Whether {@code table}, or a @Join above it in {@code tables}, is a LEFT join: its row may be absent. */
    private static boolean optional(JoinedTable table, List<JoinedTable> tables) {
        for (JoinedTable node = table; node != null;) {
            if (node.joinType().equals("LEFT")) {
                return true;
            }
            String parent = node.parent();
            node = parent == null ? null
                    : tables.stream().filter(each -> each.prefix().equals(parent)).findFirst().orElse(null);
        }
        return false;
    }

    private static boolean claim(ModelField join, NestedJoins each, Map<String, String> constants,
            Set<String> reserved, Set<String> names, String where, Diagnostics diagnostics) {
        String what = "join '" + each.declared() + "' of " + each.table().path();
        String earlier = reserved.contains(each.constant()) ? "the generated class"
                : constants.putIfAbsent(each.constant(), what);
        if (earlier != null && !earlier.equals(what)) {
            return error(join, where + what + " is generated as " + each.constant() + ", also generated for "
                    + earlier + "; rename the field or set @Join(prefix)", diagnostics);
        }
        if (!names.add(each.name())) {
            return error(join, where + what + " is registered as '" + each.name() + "', the name of another join; "
                    + "rename the field", diagnostics);
        }
        return true;
    }

    /** An entity join anchored on a filter column has no outer column to re-root onto. */
    private boolean anchors(
            ModelField join, ModelDefinition nested, EntityJoinDefinition entityJoin, String where,
            Diagnostics diagnostics) {
        boolean valid = true;
        var terms = new ArrayList<String>();
        if (entityJoin.source().isEmpty()) {
            entityJoin.on().forEach(pair -> terms.add(pair.column()));
        }
        entityJoin.when().forEach(term -> terms.add(term.column()));
        for (String column : terms) {
            var anchor = EntityJoinAnchors.resolve(nested, metamodel, builtIns, column).anchor();
            if (anchor != null && anchor.field() == null) {
                valid = error(join, where + nested.name() + " join '" + entityJoin.name() + "' is anchored on the "
                        + "@FilterColumn '" + column + "'; a join under a @Join reads a column of the model",
                        diagnostics);
            }
        }
        return valid;
    }

    /** A column on an entity join reads the join's root, which the outer model re-registers; a path below it is not. */
    private boolean columns(ModelField join, ModelDefinition nested, String where, Diagnostics diagnostics) {
        boolean valid = true;
        var layout = FilterLayout.of(nested, nestedModels.tables(nested), metamodel);
        for (FilterLayout.Column column : layout.columns()) {
            if (column.definition().synthetic() && !column.definition().entityJoin().isEmpty()
                    && (!column.table().endsWith(".root()") || column.definition().treat() != null)) {
                valid = error(join, where + nested.name() + "." + column.definition().name() + " reads '"
                        + column.definition().path() + "' of the entity join '" + column.definition().entityJoin()
                        + "' through " + (column.definition().treat() != null ? "a treat" : "an association")
                        + "; a join under a @Join reads the join's own attributes", diagnostics);
            }
        }
        return valid;
    }

    private static String quoted(List<NestedJoins> joins) {
        return joins.stream().map(join -> "'" + join.declared() + "'")
                .collect(Collectors.joining(", "));
    }

    private static boolean error(ModelField join, String detail, Diagnostics diagnostics) {
        diagnostics.error(join.element(), DiagnosticCode.MQ3044, detail);
        return false;
    }
}
