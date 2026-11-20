package com.rey.modelquery.processor;

import com.rey.modelquery.processor.EntityMetamodel.Step;
import com.rey.modelquery.processor.EntityMetamodel.Walk;
import com.rey.modelquery.processor.ModelDefinition.ChildDefinition;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * One side of a {@code @Child} match, a {@code key} or a {@code foreignKey}: an attribute path on a root entity laid
 * out as the joins it crosses and the attribute it reads on the last of them, or why it can't be (api/15 R-FCH-03).
 *
 * @param path the path as written
 * @param joins the associations the path crosses, in order, each from the entity before it
 * @param attribute the attribute read on the last join, or on the root when there is none, dotted through embedded
 *     values; {@code null} when the path fails
 * @param type the attribute's type, boxed; {@code null} when the path fails
 * @param code {@code MQ3402} or {@code MQ3404} when the path fails, else {@code null}
 * @param problem why the path fails, worded to follow {@code "Model.field: "}; {@code null} when it does not
 */
record ChildKey(
        String path, List<Join> joins, String attribute, TypeMirror type, DiagnosticCode code, String problem) {

    ChildKey {
        joins = List.copyOf(joins);
    }

    /**
     * One association a path crosses, joined {@code LEFT} and without alias, so that a {@code @Join} or a filter
     * column on the same association shares the join.
     *
     * @param parent the entity the join starts at
     * @param entity the entity the join reaches
     * @param attribute the association on {@code parent}
     * @param collection whether the association is a collection, which gives a row per element
     */
    record Join(TypeElement parent, TypeElement entity, String attribute, boolean collection) {}

    /**
     * Lays out the one path of {@code paths} on {@code owner}'s root, or {@code owner}'s one {@code @PrimaryKey}
     * attribute when {@code paths} is empty. On a model rooted at a source, the path names one of the source's fields
     * (api/17 R-SRC-10): the CTE aliases every field flat by name, so the key crosses no join, and a name no source
     * field matches is {@code MQ3402}, the code an unknown entity attribute takes.
     */
    static ChildKey of(EntityMetamodel metamodel, Types types, ModelDefinition owner, List<String> paths) {
        String path = paths.isEmpty() ? owner.keys().get(0).attribute() : paths.get(0);
        if (owner.readsSource()) {
            ModelField field = owner.sourceField(path);
            if (field == null) {
                return failed(path, DiagnosticCode.MQ3402, owner.source().name() + " has no attribute '" + path + "'");
            }
            // The key is read as the attribute value, before any converter (R-FCH-05), so a converted source column
            // is typed by the entity attribute it reads, which the child's foreignKey is compared with.
            ChildKey attribute = field.converter() != null && field.column()
                    ? of(metamodel, types, owner.source().root(), field.attribute()) : null;
            return new ChildKey(path, List.of(), path, attribute != null && attribute.code() == null
                    ? attribute.type() : ProcessorTypes.boxed(types, field.type()), null, null);
        }
        return of(metamodel, types, owner.root(), path);
    }

    /** Lays {@code path} out from {@code root}. */
    static ChildKey of(EntityMetamodel metamodel, Types types, TypeElement root, String path) {
        Walk walk = metamodel.walk(root, path);
        if (walk.problem() != null) {
            return failed(path, DiagnosticCode.MQ3402, root.getSimpleName() + " has no attribute '" + path + "'");
        }
        var joins = new ArrayList<Join>();
        var embedded = new ArrayList<String>();
        TypeElement entity = root;
        List<Step> steps = walk.steps();
        for (Step step : steps.subList(0, steps.size() - 1)) {
            EntityAttribute attribute = step.attribute();
            if (attribute.kind() == EntityAttribute.Kind.EMBEDDED) {
                embedded.add(attribute.name());
                continue;
            }
            if (!embedded.isEmpty()) {
                // A join takes one attribute of its parent, never a path into an embedded value.
                return failed(path, DiagnosticCode.MQ3402, "association '" + attribute.name() + "' of '" + path
                        + "' sits inside the embedded value '" + String.join(".", embedded) + "' of "
                        + entity.getSimpleName() + ", which a join can't reach");
            }
            var target = (TypeElement) attribute.target().asElement();
            joins.add(new Join(entity, target, attribute.name(), attribute.kind() == EntityAttribute.Kind.COLLECTION));
            entity = target;
        }
        Step last = steps.get(steps.size() - 1);
        EntityAttribute read = last.attribute();
        switch (read.kind()) {
            case EMBEDDED -> {
                return failed(path, DiagnosticCode.MQ3404, "'" + path + "' is the embedded value "
                        + ModelValidator.display(read.type()));
            }
            case TO_ONE, COLLECTION -> {
                Set<String> ids = read.target() == null
                        ? Set.of() : metamodel.id((TypeElement) read.target().asElement()).attributes();
                return failed(path, DiagnosticCode.MQ3402, "'" + read.name() + "' on " + last.owner()
                        + " is an association, not a key attribute; name an attribute of it"
                        + (ids.size() == 1 ? ", such as '" + path + "." + ids.iterator().next() + "'" : ""));
            }
            default -> {
                if (read.type().getKind() == TypeKind.ARRAY) {
                    return failed(path, DiagnosticCode.MQ3404, "'" + path + "' is an array, "
                            + ModelValidator.display(read.type()));
                }
            }
        }
        embedded.add(read.name());
        return new ChildKey(path, joins, String.join(".", embedded), ProcessorTypes.boxed(types, read.type()),
                null, null);
    }

    /**
     * The associations a {@code through} path joins from {@code root}, one per step, or why it can't: a step that is
     * not an association of a known target, or that sits in an embedded value (api/15 R-FCH-14). A path may end at an
     * {@code @ElementCollection} after to-one associations only, and go no further (R-FCH-19).
     *
     * @param joins the joins, in order, the element collection's excluded; empty when the path fails
     * @param elements the element collection the path ends at, read on the last join or the root, or the one it
     *     fails at; {@code null} when it ends at an entity or fails before reaching one
     * @param problem why the path fails, worded to follow {@code "through 'path' "}; {@code null} when it does not
     */
    record Through(List<Join> joins, EntityAttribute elements, String problem) {

        Through {
            joins = List.copyOf(joins);
        }

        /** Whether a join of the path is a collection, which gives a row per element. */
        boolean crossesCollection() {
            return joins.stream().anyMatch(Join::collection);
        }

        /** The entity the path ends at; the path must not have failed. */
        TypeElement target() {
            return joins.get(joins.size() - 1).entity();
        }
    }

    /**
     * The {@code through} path of {@code field}, a {@code @Child} of {@code model}, when it ends or fails at an
     * element collection, which makes the field a value child (R-FCH-19); {@code null} for any other field.
     */
    static Through elementsOf(EntityMetamodel metamodel, ModelDefinition model, ModelField field) {
        if (field.child() == null || field.child().through().isEmpty() || model.readsSource()) {
            return null;
        }
        Through through = through(metamodel, model.root(), field.child().through());
        return through.elements() == null ? null : through;
    }

    /**
     * The parent-side key of the value child {@code definition} on {@code model}: the path {@code key} names, else
     * the root's single {@code @Id}, read on the root whether or not a {@code @PrimaryKey} names it (R-FCH-19).
     */
    static List<String> valueKey(EntityMetamodel metamodel, ModelDefinition model, ChildDefinition definition) {
        Set<String> ids = metamodel.id(model.root()).attributes();
        return definition.key().isEmpty() && ids.size() == 1 ? List.copyOf(ids) : definition.key();
    }

    /** Lays the {@code through} path {@code path} out from {@code root}. */
    static Through through(EntityMetamodel metamodel, TypeElement root, String path) {
        if (path.isBlank()) {
            return failed("is blank");
        }
        Walk walk = metamodel.walk(root, path);
        List<Step> steps = walk.steps();
        if (walk.problem() != null && steps.stream().allMatch(step -> step.attribute().elements() == null)) {
            return failed("can't be followed: " + walk.problem());
        }
        var joins = new ArrayList<Join>();
        TypeElement entity = root;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            EntityAttribute attribute = step.attribute();
            if (attribute.kind() == EntityAttribute.Kind.EMBEDDED) {
                // An element collection inside the embedded value still makes the field a value child.
                EntityAttribute inside = steps.subList(i, steps.size()).stream().map(Step::attribute)
                        .filter(later -> later.elements() != null).findFirst().orElse(null);
                return new Through(List.of(), inside, "crosses the embedded value '" + attribute.name() + "' of "
                        + step.owner() + ", which a join can't reach into");
            }
            if (attribute.elements() != null) {
                return elements(joins, attribute, step.owner(), walk.problem() != null || i < steps.size() - 1);
            }
            if (!attribute.joinable()) {
                return failed("crosses '" + attribute.name() + "' on " + step.owner()
                        + ", which is not an association to an entity");
            }
            var target = (TypeElement) attribute.target().asElement();
            joins.add(new Join(entity, target, attribute.name(),
                    attribute.kind() == EntityAttribute.Kind.COLLECTION));
            entity = target;
        }
        return new Through(joins, null, null);
    }

    /**
     * A {@code through} path ending at the element collection {@code attribute} of {@code owner}, after
     * {@code joins}: refused when it goes on past it ({@code past}) or crosses a collection before it, whose elements'
     * values would merge into one list (R-FCH-19).
     */
    private static Through elements(List<Join> joins, EntityAttribute attribute, String owner, boolean past) {
        String collection = "the element collection '" + attribute.name() + "' of " + owner;
        if (past) {
            return new Through(List.of(), attribute, "goes on past " + collection + ", whose elements a path can't "
                    + "step into; end it there");
        }
        for (Join join : joins) {
            if (join.collection()) {
                return new Through(List.of(), attribute, "crosses the collection '" + join.attribute() + "' of "
                        + join.parent().getSimpleName() + " before " + collection + ", which would merge the "
                        + "elements of every " + join.entity().getSimpleName() + " into one list; put the child on "
                        + "a model of " + join.entity().getSimpleName() + " instead");
            }
        }
        return new Through(joins, attribute, null);
    }

    private static Through failed(String problem) {
        return new Through(List.of(), null, problem);
    }

    private static ChildKey failed(String path, DiagnosticCode code, String problem) {
        return new ChildKey(path, List.of(), null, null, code, problem);
    }
}
