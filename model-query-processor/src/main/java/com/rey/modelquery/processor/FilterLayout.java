package com.rey.modelquery.processor;

import com.rey.modelquery.processor.EntityMetamodel.Step;
import com.rey.modelquery.processor.EntityMetamodel.Walk;
import com.rey.modelquery.processor.ModelDefinition.FilterColumnDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.TypeElement;

/**
 * What a model's {@code @FilterColumn}s and its root's collections generate: the joins they need beyond the model's
 * {@code @Join}s, and the column each path ends at. {@link ModelValidator} reports its problems and
 * {@link QModelWriter} emits the rest, so both read one layout.
 *
 * @param tables the joins to declare, a parent before what hangs from it
 * @param columns the filter columns whose path resolved, in declaration order
 * @param problems what is wrong with the paths; empty for a model that may be generated
 * @implSpec R-PROC-10, R-PROC-11, R-PROC-12, R-PROC-13
 */
record FilterLayout(List<Table> tables, List<Column> columns, List<Problem> problems) {

    private static final String ROOT = "ROOT";
    private static final String LEFT = "LEFT";
    /** What follows a root collection's name in the alias of the {@code INNER} join a filter column asks of it. */
    private static final String INNER_SUFFIX = "Inner";

    FilterLayout {
        tables = List.copyOf(tables);
        columns = List.copyOf(columns);
        problems = List.copyOf(problems);
    }

    /**
     * A generated join that no {@code @Join} declares.
     *
     * @param constant the name of its {@code TableField} constant
     * @param parent the constant of the table it hangs from, {@code ROOT} included
     * @param parentEntity the entity the join starts at
     * @param entity the entity the join reaches
     * @param attribute the association on {@code parentEntity}
     * @param joinType the JPA join type's name
     * @param alias the alias that sets the join apart; {@code ""} for none
     * @param description the join as a name clash names it
     * @param typedBy the name of the filter column that chose {@code joinType}, or {@code null} for the constant
     *     every collection of the root gets, which is {@code LEFT} whatever a filter column asks for
     */
    record Table(
            String constant, String parent, TypeElement parentEntity, TypeElement entity, String attribute,
            String joinType, String alias, String description, String typedBy) {}

    /**
     * A filter column whose path resolved.
     *
     * @param definition the annotation as written
     * @param table the constant of the table the column sits on
     * @param entity that table's entity
     * @param attribute the attribute path on {@code entity}, dotted through embedded values
     * @param read the entity attribute the path ends at
     */
    record Column(
            FilterColumnDefinition definition, String table, TypeElement entity, String attribute,
            EntityAttribute read) {}

    /**
     * One thing wrong with a filter column's path.
     *
     * @param code {@code MQ3011} or {@code MQ3012}
     * @param detail the message, naming the model and the filter column
     */
    record Problem(DiagnosticCode code, String detail) {}

    /**
     * Lays out the filter columns of {@code model} over {@code joined}, the joins its {@code @Join}s declare: a path
     * without alias reuses the first {@code @Join} on its first association, a path whose alias is a {@code @Join}'s
     * reuses that one, and either goes on through the joins below it (R-PROC-11, R-PROC-12).
     */
    static FilterLayout of(ModelDefinition model, List<JoinedTable> joined, EntityMetamodel metamodel) {
        // Keyed by what makes a join its own: alias and association path, and the @Join it hangs below (R-PROC-12).
        var tables = new LinkedHashMap<List<String>, Table>();
        for (EntityAttribute collection : metamodel.collections(model.root())) {
            tables.put(List.of("", collection.name()), new Table(
                    QueryModelReader.constantName(collection.name()) + "_TABLE", ROOT, model.root(),
                    (TypeElement) collection.target().asElement(), collection.name(), LEFT, "",
                    "the collection '" + collection.name() + "'", null));
        }
        var columns = new ArrayList<Column>();
        var problems = new ArrayList<Problem>();
        for (FilterColumnDefinition definition : model.filterColumns()) {
            Walk walk = metamodel.walk(model.root(), definition.path());
            if (walk.problem() != null) {
                problems.add(new Problem(
                        DiagnosticCode.MQ3011, model.name() + " " + definition.label() + ": " + walk.problem()));
                continue;
            }
            Column column = place(model, definition, walk, joined, tables, problems);
            if (column != null) {
                columns.add(column);
            }
        }
        return new FilterLayout(new ArrayList<>(tables.values()), columns, problems);
    }

    /**
     * Finds or adds the join of each association on the path, and returns the column on the last of them, or
     * {@code null} with a problem added.
     */
    private static Column place(
            ModelDefinition model, FilterColumnDefinition definition, Walk walk, List<JoinedTable> joined,
            Map<List<String>, Table> tables, List<Problem> problems) {
        String where = model.name() + " " + definition.label() + ": ";
        String alias = definition.alias();
        String table = ROOT;
        String prefix = "";
        String crossed = "";
        // The @Join the path is on so far, until it leaves the declared joins for one generated here.
        JoinedTable under = null;
        boolean generated = false;
        TypeElement entity = model.root();
        var embedded = new ArrayList<String>();
        Step last = walk.steps().get(walk.steps().size() - 1);
        for (Step step : walk.steps().subList(0, walk.steps().size() - 1)) {
            EntityAttribute attribute = step.attribute();
            if (attribute.kind() == EntityAttribute.Kind.EMBEDDED) {
                embedded.add(attribute.name());
                continue;
            }
            if (!embedded.isEmpty()) {
                // A join takes one attribute of its parent, never a path into an embedded value.
                problems.add(new Problem(DiagnosticCode.MQ3011, where + "association '" + attribute.name()
                        + "' sits inside the embedded value '" + String.join(".", embedded) + "' of "
                        + entity.getSimpleName()
                        + ", which a generated join can't reach; declare its TableField by hand"));
                return null;
            }
            crossed = crossed.isEmpty() ? attribute.name() : crossed + "." + attribute.name();
            var target = (TypeElement) attribute.target().asElement();
            JoinedTable declared = generated ? null : declared(joined, under, crossed, alias);
            if (declared != null) {
                if (definition.explicitJoinType() && !declared.joinType().equals(definition.joinType())) {
                    problems.add(new Problem(DiagnosticCode.MQ3012, where + "joinType is " + definition.joinType()
                            + ", but the path reuses the join of @Join field '" + declared.path() + "', which is "
                            + declared.joinType() + "; drop joinType, or give the path another alias"));
                    return null;
                }
                under = declared;
                table = declared.table();
                prefix = declared.prefix();
                entity = target;
                continue;
            }
            if (attribute.kind() == EntityAttribute.Kind.COLLECTION && !definition.explicitJoinType()) {
                problems.add(new Problem(DiagnosticCode.MQ3011, where + "'" + attribute.name() + "' on "
                        + step.owner() + " is a collection, which multiplies rows when joined; set joinType, or "
                        + "filter on it with Filters.exists"));
                return null;
            }
            if (alias.isEmpty() && prefix.isEmpty() && attribute.kind() == EntityAttribute.Kind.COLLECTION
                    && !LEFT.equals(definition.joinType())) {
                // The collection's own constant stays LEFT (R-PROC-13): another type is a join of its own.
                alias = attribute.name() + INNER_SUFFIX;
            }
            generated = true;
            // The alias names the first join of its path, and every join below hangs from that one.
            boolean aliased = !alias.isEmpty() && prefix.isEmpty();
            String segment = QueryModelReader.constantName(aliased ? alias : attribute.name());
            prefix = prefix.isEmpty() ? segment : prefix + "_" + segment;
            List<String> key = under == null ? List.of(alias, crossed) : List.of("", crossed, under.table());
            Table existing = tables.get(key);
            if (existing == null) {
                tables.put(key, new Table(
                        prefix + "_TABLE", table, entity, target, attribute.name(), definition.joinType(),
                        aliased ? alias : "", "the join '" + crossed + "' of " + definition.label(),
                        definition.name()));
            } else if (!existing.joinType().equals(definition.joinType())) {
                problems.add(new Problem(DiagnosticCode.MQ3012, where
                        // An alias the user did not write (the collection's INNER one) is not named.
                        + (definition.alias().isEmpty() ? "join '" + crossed + "'" : "alias '" + alias + "'") + " is "
                        + definition.joinType() + " here, " + existing.joinType() + " on " + existing.typedBy()));
                return null;
            }
            table = prefix + "_TABLE";
            entity = target;
        }
        if (last.attribute().kind() == EntityAttribute.Kind.COLLECTION) {
            problems.add(new Problem(DiagnosticCode.MQ3011, where + "'" + last.attribute().name() + "' on "
                    + last.owner() + " is a collection, which a column can't read; filter on it with Filters.exists"));
            return null;
        }
        embedded.add(last.attribute().name());
        return new Column(definition, table, entity, String.join(".", embedded), last.attribute());
    }

    /**
     * The declared join of the association path {@code crossed}: below {@code under} when the path is already on a
     * {@code @Join}, else the {@code @Join} of the model itself that {@code alias} names, the first one for none.
     */
    private static JoinedTable declared(List<JoinedTable> joined, JoinedTable under, String crossed, String alias) {
        for (JoinedTable table : joined) {
            if (!table.attributes().equals(crossed)) {
                continue;
            }
            boolean matches = under == null
                    ? table.parent() == null && (alias.isEmpty() || alias.equals(table.join().join().alias()))
                    : under.prefix().equals(table.parent());
            if (matches) {
                return table;
            }
        }
        return null;
    }
}
