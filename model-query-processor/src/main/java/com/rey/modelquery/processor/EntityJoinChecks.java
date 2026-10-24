package com.rey.modelquery.processor;

import com.rey.modelquery.processor.ModelDefinition.EntityJoinDefinition;
import com.rey.modelquery.processor.ModelDefinition.EntityJoinOn;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;

/** Entity joins compare a source field with every basic id attribute (R-PROC-46). */
final class EntityJoinChecks {

    private final Types types;
    private final EntityMetamodel metamodel;
    private final BuiltInConverters builtIns;

    EntityJoinChecks(Types types, EntityMetamodel metamodel, BuiltInConverters builtIns) {
        this.types = types;
        this.metamodel = metamodel;
        this.builtIns = builtIns;
    }

    boolean check(ModelDefinition model, Diagnostics diagnostics) {
        var names = new HashSet<String>();
        for (var source : model.joinSources()) {
            names.add(source.name());
        }
        for (var join : model.entityJoins()) {
            if (!model.queryModel() || model.readsSource()) {
                error(model, join.name(), "@JoinEntity needs a @QueryModel rooted at an entity", diagnostics);
            }
            if (!SourceVersion.isIdentifier(join.name()) || SourceVersion.isKeyword(join.name())) {
                error(model, join.name(), "name must be a Java identifier", diagnostics);
            }
            if (!names.add(join.name())) {
                error(model, join.name(), "duplicate join name", diagnostics);
            }
            checkJoin(model, join, diagnostics);
        }
        for (var field : model.fields()) {
            if (field.entityJoin().isEmpty()) {
                continue;
            }
            if (field.treat() != null) {
                error(model, field.entityJoin(), "treat cannot be combined with join", diagnostics);
            }
            if (field.primaryKey()) {
                error(model, field.entityJoin(), "@PrimaryKey is forbidden on a joined-entity column", diagnostics);
            }
            if (field.join() != null) {
                error(model, field.entityJoin(), "a nested @Join on a joined entity is forbidden", diagnostics);
            }
            checkPath(model, field.entityJoin(), field.attribute(), diagnostics);
        }
        for (var column : model.filterColumns()) {
            if (!column.entityJoin().isEmpty()) {
                if (column.treat() != null) {
                    error(model, column.entityJoin(), "treat cannot be combined with join", diagnostics);
                }
                checkPath(model, column.entityJoin(), column.path(), diagnostics);
            }
        }
        return !diagnostics.hasErrors();
    }

    private void checkJoin(ModelDefinition model, EntityJoinDefinition join, Diagnostics diagnostics) {
        if (!(join.entity() instanceof DeclaredType declared)
                || !QueryModelReader.isEntity((TypeElement) declared.asElement())) {
            error(model, join.name(), "entity must name a JPA @Entity", diagnostics);
            return;
        }
        TypeElement entity = (TypeElement) declared.asElement();
        var id = metamodel.id(entity);
        Set<String> required = id.components().isEmpty() ? id.attributes() : id.components();
        var paired = new HashSet<String>();
        String anchor = join.on().isEmpty() ? "" : join.on().get(0).join();
        for (var pair : join.on()) {
            if (!anchor.equals(pair.join())) {
                error(model, join.name(), "all id pairs must name the same source join", diagnostics);
            }
            String path = pair.id();
            if (path.isEmpty() && id.components().isEmpty() && required.size() == 1) {
                path = required.iterator().next();
            }
            var walk = metamodel.walk(entity, path);
            boolean basic = walk.problem() == null && !walk.steps().isEmpty()
                    && walk.steps().get(walk.steps().size() - 1).attribute().kind() == EntityAttribute.Kind.BASIC
                    && walk.steps().stream().noneMatch(step -> step.attribute().joinable());
            if (!required.contains(path) || !basic) {
                error(model, join.name(), "'" + path + "' must be a basic id attribute", diagnostics);
            }
            if (!paired.add(path)) {
                error(model, join.name(), "pairs id attribute '" + path + "' more than once", diagnostics);
            }
            checkAnchor(model, join, pair, basic
                    ? walk.steps().get(walk.steps().size() - 1).attribute() : null, diagnostics);
        }
        if (required.isEmpty() || !paired.equals(required)) {
            error(model, join.name(), "id pairs must cover every basic id attribute exactly once", diagnostics);
        }
    }

    private void checkAnchor(ModelDefinition model, EntityJoinDefinition join, EntityJoinOn pair,
            EntityAttribute id, Diagnostics diagnostics) {
        var sourceJoin = model.joinSources().stream().filter(source -> source.name().equals(pair.join()))
                .findFirst().orElse(null);
        if (sourceJoin == null || sourceJoin.sourceModel() == null) {
            error(model, join.name(), "anchor '" + pair.join() + "' must name a @JoinSource of this model",
                    diagnostics);
            return;
        }
        var source = sourceJoin.sourceModel();
        var field = source.readable().stream().filter(candidate -> candidate.name().equals(pair.field()))
                .findFirst().orElse(null);
        if (field == null) {
            error(model, join.name(), "unknown anchor field '" + pair.field() + "'", diagnostics);
            return;
        }
        var attribute = metamodel.resolve(source.root(), field.attribute()).attribute();
        if (field.converter() != null || attribute != null
                && builtIns.between(field.type(), attribute.type()) != null) {
            error(model, join.name(), "anchor field '" + pair.field() + "' has a converter", diagnostics);
        } else if (id != null && !types.isSameType(ProcessorTypes.boxed(types, field.type()),
                ProcessorTypes.boxed(types, id.type()))) {
            error(model, join.name(), "anchor field '" + pair.field() + "' and id have different boxed types",
                    diagnostics);
        }
    }

    private void checkPath(ModelDefinition model, String name, String path, Diagnostics diagnostics) {
        var join = model.entityJoins().stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElse(null);
        if (join == null) {
            error(model, name, "unknown entity join", diagnostics);
            return;
        }
        if (!(join.entity() instanceof DeclaredType declared)
                || !QueryModelReader.isEntity((TypeElement) declared.asElement())) {
            return;
        }
        var walk = metamodel.walk(model.entityRoot(name), path);
        if (walk.steps().stream().anyMatch(step -> step.attribute().kind() == EntityAttribute.Kind.COLLECTION)) {
            error(model, name, "a joined-entity path cannot traverse a to-many attribute", diagnostics);
        }
    }

    private static void error(ModelDefinition model, String join, String detail, Diagnostics diagnostics) {
        diagnostics.error(model.type(), DiagnosticCode.MQ3046, model.name() + " entity join '" + join + "': " + detail);
    }
}
