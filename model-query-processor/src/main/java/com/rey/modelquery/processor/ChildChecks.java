package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.processor.ModelDefinition.ChildDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.lang.annotation.Annotation;
import java.util.List;
import javax.lang.model.util.Types;

/**
 * The checks of a {@code @Child} field, {@code MQ3401} to {@code MQ3405} (processor/32 §1).
 *
 * @implSpec R-FCH-03, R-PROC-20
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
     * {@code MQ3401} for the field's type, the annotations beside {@code @Child} or an update model, {@code MQ3405}
     * for a {@code List} child without {@code foreignKey} or {@code @PrimaryKey}, {@code MQ3402} and {@code MQ3404}
     * for each side's path, and {@code MQ3403} for two sides of different types.
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
        if (definition.toMany() && definition.foreignKey().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3405, where + "a List @Child needs foreignKey, the "
                    + "attribute of " + child.root().getSimpleName() + " that holds the parent's key");
        }
        if (definition.toMany() && child.keys().isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3405, where + "a List @Child needs a @PrimaryKey on "
                    + child.name() + ", which orders and deduplicates its rows");
        }
        ChildKey key = side(model, definition.key(), "key", where, field, diagnostics);
        ChildKey foreignKey = definition.toMany() && definition.foreignKey().isEmpty()
                ? null : side(child, definition.foreignKey(), "foreignKey", where, field, diagnostics);
        if (key != null && foreignKey != null && !types.isSameType(key.type(), foreignKey.type())) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3403, where
                    + "key " + ModelValidator.display(key.type()) + " " + key.path() + " and foreignKey "
                    + ModelValidator.display(foreignKey.type()) + " " + foreignKey.path() + " differ");
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
