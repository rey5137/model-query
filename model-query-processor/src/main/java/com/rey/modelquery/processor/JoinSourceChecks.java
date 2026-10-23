package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.rey.modelquery.processor.ModelDefinition.JoinOn;
import com.rey.modelquery.processor.ModelDefinition.JoinSourceDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * The checks of {@code @JoinSource} and {@code @SourceColumn} (R-PROC-44): {@code MQ3044} for a join that misses or
 * exceeds the source's key, mismatches a type, names an unknown field or a class that is no source model, and for a column
 * naming an unknown join or field; {@code MQ3002} for a column of another type than the source field's;
 * {@code MQ3014} for a converter of its own.
 *
 * <p>Whether a source is unique when the query is built is the caller's: a {@code SourceQuery} over a set operation
 * or a plain recursive walk is {@code MQ1910} at build time, since the generated {@code query(...)} receives it
 * (D-190).
 */
final class JoinSourceChecks {

    private final Types types;
    private final EntityMetamodel metamodel;

    JoinSourceChecks(Types types, EntityMetamodel metamodel) {
        this.types = types;
        this.metamodel = metamodel;
    }

    void check(ModelDefinition model, Diagnostics diagnostics) {
        boolean joins = !model.joinSources().isEmpty();
        boolean columns = model.fields().stream().anyMatch(field -> field.sourceColumn() != null);
        if (!joins && !columns) {
            return;
        }
        if (!model.queryModel() || model.readsSource()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3044, model.name() + ": "
                    + (model.queryModel() ? "a model rooted at a source cannot join another source"
                            : "@JoinSource and @SourceColumn belong to a @QueryModel rooted at an entity"));
            return;
        }
        Set<String> names = new HashSet<>();
        for (JoinSourceDefinition join : model.joinSources()) {
            checkJoin(model, join, names, diagnostics);
        }
        for (ModelField field : model.fields()) {
            if (field.sourceColumn() != null) {
                checkColumn(model, field, diagnostics);
            }
        }
    }

    /** The error {@code detail} on the model, worded after {@code Model join 'name': }. */
    private static void error(ModelDefinition model, JoinSourceDefinition join, String detail,
            Diagnostics diagnostics) {
        diagnostics.error(model.type(), DiagnosticCode.MQ3044,
                model.name() + " join '" + join.name() + "': " + detail);
    }

    private void checkJoin(
            ModelDefinition model, JoinSourceDefinition join, Set<String> names, Diagnostics diagnostics) {
        if (!SourceVersion.isIdentifier(join.name()) || SourceVersion.isKeyword(join.name())) {
            error(model, join, "the name must be a Java identifier, the parameter of query(...)", diagnostics);
            return;
        }
        if (!names.add(join.name())) {
            error(model, join, "the name is used by another @JoinSource of this model", diagnostics);
        }
        ModelDefinition source = join.sourceModel();
        if (source == null) {
            error(model, join, ModelValidator.display(join.source()) + " is not a @SourceModel", diagnostics);
            return;
        }
        if (source.branch()) {
            error(model, join, source.name() + " is a branch and builds a fragment of its shape; join the shape "
                    + source.shape().name() + " instead", diagnostics);
            return;
        }
        Set<String> key = new LinkedHashSet<>();
        source.keys().forEach(field -> key.add(field.name()));
        if (join.on().isEmpty()) {
            error(model, join, "on names no pair; join the key " + key, diagnostics);
            return;
        }
        Set<String> paired = new HashSet<>();
        for (JoinOn pair : join.on()) {
            ModelField keyField = source.readable().stream()
                    .filter(field -> field.name().equals(pair.key())).findFirst().orElse(null);
            if (keyField == null) {
                error(model, join, "on(key = \"" + pair.key() + "\") is no field of " + source.name(), diagnostics);
            } else if (!key.contains(pair.key())) {
                error(model, join, "on(key = \"" + pair.key() + "\") is not a key field of " + source.name()
                        + "; join on its key " + key, diagnostics);
            } else if (!paired.add(pair.key())) {
                error(model, join, "on pairs the key field '" + pair.key() + "' twice", diagnostics);
            }
            checkOuter(model, join, pair, keyField, diagnostics);
        }
        Set<String> missing = new LinkedHashSet<>(key);
        missing.removeAll(paired);
        if (!missing.isEmpty() && paired.stream().allMatch(key::contains)) {
            error(model, join, "on misses the key field(s) " + missing + " of " + source.name()
                    + "; a partial key could repeat an outer row", diagnostics);
        }
    }

    /** The outer {@code column} of {@code pair} is a basic attribute of the root, of the key field's type (INV-3). */
    private void checkOuter(ModelDefinition model, JoinSourceDefinition join, JoinOn pair, ModelField keyField,
            Diagnostics diagnostics) {
        Resolution resolution = metamodel.resolve(model.root(), pair.column());
        if (resolution.attribute() == null) {
            error(model, join, "on(column = \"" + pair.column() + "\"): " + resolution.problem(), diagnostics);
            return;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute.kind() == EntityAttribute.Kind.TO_ONE || attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            error(model, join, "on(column = \"" + pair.column() + "\") is an association on "
                    + model.root().getSimpleName() + "; join a basic attribute", diagnostics);
        } else if (keyField != null && keyField.converter() != null) {
            error(model, join, "on(key = \"" + pair.key() + "\") is converted by "
                    + ModelValidator.display(keyField.converter()) + "; the join would compare the database value, "
                    + "so join on a key field with no converter", diagnostics);
        } else if (keyField != null && !types.isSameType(ProcessorTypes.boxed(types, attribute.type()),
                ProcessorTypes.boxed(types, keyField.type()))) {
            error(model, join, "on(column = \"" + pair.column() + "\") is "
                    + ModelValidator.display(attribute.type()) + ", the key field " + keyField.name() + " is "
                    + ModelValidator.display(keyField.type()), diagnostics);
        }
    }

    private void checkColumn(ModelDefinition model, ModelField field, Diagnostics diagnostics) {
        String where = model.name() + "." + field.name() + ": ";
        List<JoinSourceDefinition> matches = model.joinSources().stream()
                .filter(join -> join.name().equals(field.sourceColumn().join())).toList();
        if (field.element().getAnnotation(Column.class) != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3014, where + "a @SourceColumn reads its source "
                    + "field's converter; drop @Column");
            return;
        }
        if (!field.column()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3044, where + "@SourceColumn can't be combined "
                    + "with @Aggregate, @Computed, @Window, @Join, @Transient, @Child or @Selected");
            return;
        }
        if (field.primaryKey()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3044, where + "@SourceColumn can't be the "
                    + "@PrimaryKey; a model's key is the root's, and a joined value can be NULL or shared");
            return;
        }
        if (matches.isEmpty()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3044, where + "no @JoinSource named '"
                    + field.sourceColumn().join() + "' on " + model.name());
            return;
        }
        ModelDefinition source = matches.get(0).sourceModel();
        if (source == null) {
            return;
        }
        ModelField read = source.readable().stream()
                .filter(each -> each.name().equals(field.sourceColumn().field())).findFirst().orElse(null);
        if (read == null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3044, where + "'" + field.sourceColumn().field()
                    + "' is no field of " + source.name() + ", the source of join '" + field.sourceColumn().join()
                    + "'");
        } else if (!types.isSameType(ProcessorTypes.boxed(types, field.type()),
                ProcessorTypes.boxed(types, read.type()))) {
            TypeMirror type = field.type();
            diagnostics.error(field.element(), DiagnosticCode.MQ3002, where + "model type "
                    + ModelValidator.display(type) + ", the source's " + read.name() + " is "
                    + ModelValidator.display(read.type()));
        }
    }
}
