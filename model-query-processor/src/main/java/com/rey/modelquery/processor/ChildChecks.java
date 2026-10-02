package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.processor.ModelDefinition.ChildDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import javax.lang.model.util.Types;

/**
 * The checks of a {@code @Child} field, {@code MQ3401} to {@code MQ3406} (processor/32 §1).
 *
 * @implSpec R-FCH-03, R-FCH-14, R-PROC-20
 */
final class ChildChecks {

    private static final List<Class<? extends Annotation>> EXCLUSIVE =
            List.of(Join.class, Transient.class, Aggregate.class, GroupBy.class);

    private final Types types;
    private final EntityMetamodel metamodel;
    private final NestedModels nestedModels;

    ChildChecks(Types types, EntityMetamodel metamodel, NestedModels nestedModels) {
        this.types = types;
        this.metamodel = metamodel;
        this.nestedModels = nestedModels;
    }

    /**
     * {@code MQ3401} for the field's type, the annotations beside {@code @Child}, an update model or {@code through}
     * with {@code foreignKey}, {@code MQ3405} for a {@code List} child without {@code foreignKey} or {@code through},
     * or without {@code @PrimaryKey}, {@code MQ3402} and {@code MQ3404} for each side's path, {@code MQ3403} for two
     * sides of different types, and {@code MQ3406} for a {@code through} child (R-FCH-14).
     */
    void check(ModelDefinition model, ModelField field, Diagnostics diagnostics) {
        String where = model.name() + "." + field.name() + ": ";
        if (model.updateModel()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3401,
                    where + "@Child isn't allowed on @UpdateModel; an update writes columns and loads no children");
            return;
        }
        for (Class<? extends Annotation> annotation : EXCLUSIVE) {
            if (field.element().getAnnotation(annotation) != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3401,
                        where + "@Child can't be combined with @" + annotation.getSimpleName());
            }
        }
        ModelDefinition child = nestedModels.child(field);
        if (child == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3401, where + "@Child needs a List or Optional of a "
                    + "@QueryModel, found " + ModelValidator.display(field.type()));
            return;
        }
        ChildDefinition definition = field.child();
        boolean through = !definition.through().isEmpty();
        if (through && !definition.foreignKey().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3401, where + "@Child takes foreignKey or through, "
                    + "not both; through matches the child on the parent's @Id read along the path");
            return;
        }
        if (definition.toMany() && !through && definition.foreignKey().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3405, where + "a List @Child needs foreignKey, the "
                    + "attribute of " + child.root().getSimpleName() + " that holds the parent's key");
        }
        if (definition.toMany() && child.keys().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3405, where + "a List @Child needs a @PrimaryKey on "
                    + child.name() + ", which orders and deduplicates its rows");
        }
        ChildKey key = side(model, definition.key(), "key", where, field, diagnostics);
        if (through) {
            checkThrough(model, child, field, key, where, diagnostics);
            return;
        }
        ChildKey foreignKey = definition.toMany() && definition.foreignKey().isEmpty()
                ? null : side(child, definition.foreignKey(), "foreignKey", where, field, diagnostics);
        if (key != null && foreignKey != null && !types.isSameType(key.type(), foreignKey.type())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3403, where
                    + "key " + ModelValidator.display(key.type()) + " " + key.path() + " and foreignKey "
                    + ModelValidator.display(foreignKey.type()) + " " + foreignKey.path() + " differ");
        }
    }

    /**
     * {@code MQ3406} for a {@code through} path that fails or ends at another type than the child's root, a key that
     * is not the parent root's one {@code @Id}, or a grouped child model; {@code MQ3405} for an {@code Optional}
     * child whose path crosses a collection, without the {@code @PrimaryKey} that deduplicates its rows.
     */
    private void checkThrough(ModelDefinition model, ModelDefinition child, ModelField field, ChildKey key,
            String where, Diagnostics diagnostics) {
        String path = field.child().through();
        ChildKey.Through through = ChildKey.through(metamodel, model.root(), path);
        if (through.problem() != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3406, where + "through '" + path + "' "
                    + through.problem());
        } else if (!types.isSameType(through.target().asType(), child.root().asType())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3406, where + "through '" + path + "' ends at "
                    + through.target().getSimpleName() + ", not at " + child.root().getSimpleName() + ", the root of "
                    + child.name());
        } else if (!field.child().toMany() && through.crossesCollection() && child.keys().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3405, where + "an Optional @Child through a "
                    + "collection needs a @PrimaryKey on " + child.name() + ", which deduplicates its rows");
        }
        Set<String> ids = metamodel.id(model.root()).attributes();
        if (key != null && (!key.joins().isEmpty() || ids.size() != 1 || !ids.contains(key.attribute()))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3406, where + "key '" + key.path() + "' is not the "
                    + "single @Id of " + model.root().getSimpleName() + ", which a through child is matched on");
        }
        if (child.fields().stream().anyMatch(column -> column.groupBy() || column.aggregate() != null)) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3406, where + child.name() + " is grouped, and a "
                    + "through child can't be: its rows would have to be grouped by the parent's key too");
        }
    }

    /**
     * One side of the match laid out on {@code owner}'s root, or {@code null} once reported: the path written, else
     * {@code owner}'s {@code @PrimaryKey} attribute.
     */
    private ChildKey side(ModelDefinition owner, List<String> paths, String side, String where, ModelField field,
            Diagnostics diagnostics) {
        String oneEach = "@Child takes one key attribute each side; ";
        if (paths.size() > 1) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3404,
                    where + oneEach + side + " names " + paths.size());
            return null;
        }
        List<ModelField> keys = owner.keys();
        if (paths.isEmpty() && keys.isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3402,
                    where + owner.name() + " has no @PrimaryKey for " + side + " to default to; set " + side);
            return null;
        }
        if (paths.isEmpty() && keys.size() > 1) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3404, where + oneEach + side + " defaults to "
                    + owner.name() + "'s @PrimaryKey, which has " + keys.size());
            return null;
        }
        ChildKey laid = ChildKey.of(metamodel, types, owner, paths);
        // A @PrimaryKey column whose path does not resolve is reported on its own field (MQ3001).
        if (laid.code() == DiagnosticCode.MQ3402 && !paths.isEmpty()) {
            diagnostics.error(field.element(), laid.code(), where + laid.problem());
        } else if (laid.code() == DiagnosticCode.MQ3404) {
            diagnostics.error(field.element(), laid.code(), where + oneEach + side + " " + laid.problem());
        }
        if (laid.code() == null && side.equals("key")
                && laid.joins().stream().anyMatch(ChildKey.Join::collection)) {
            // A parent row per element: on the child side the rows are matched per (key, child) instead.
            diagnostics.error(field.element(), DiagnosticCode.MQ3402, where + "key '" + laid.path()
                    + "' crosses a collection, which gives the parent a row per element; name a single-valued "
                    + "attribute, or put the collection in the child's foreignKey");
            return null;
        }
        return laid.code() == null ? laid : null;
    }
}
