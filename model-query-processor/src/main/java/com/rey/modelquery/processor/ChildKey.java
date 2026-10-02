package com.rey.modelquery.processor;

import com.rey.modelquery.processor.EntityMetamodel.Step;
import com.rey.modelquery.processor.EntityMetamodel.Walk;
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
     * attribute when {@code paths} is empty.
     */
    static ChildKey of(EntityMetamodel metamodel, Types types, ModelDefinition owner, List<String> paths) {
        return of(metamodel, types, owner.root(), paths.isEmpty() ? owner.keys().get(0).attribute() : paths.get(0));
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

    private static ChildKey failed(String path, DiagnosticCode code, String problem) {
        return new ChildKey(path, List.of(), null, null, code, problem);
    }
}
