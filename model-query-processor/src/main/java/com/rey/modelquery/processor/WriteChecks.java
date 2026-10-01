package com.rey.modelquery.processor;

import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * The checks of a model with a change set: an {@code @UpdateModel}, or a {@code @QueryModel} with
 * {@code generateChanges = true} ({@code MQ3301}..{@code MQ3307}).
 *
 * @implSpec R-GEN-19, R-GEN-22, R-GEN-23, R-DIAG-01, R-DIAG-02
 */
final class WriteChecks {

    /** The members of {@code Changes<M>} that a change set's fluent setter must not overload (R-GEN-23). */
    private static final Set<String> CHANGES_MEMBERS = Set.of("isEmpty", "isSet", "unset", "assignments");

    /** The property {@code Changes.isEmpty()} reads as, which a field's getter and setter must not name too. */
    private static final String EMPTY = "empty";

    private final Types types;
    private final EntityMetamodel metamodel;

    WriteChecks(Types types, EntityMetamodel metamodel) {
        this.types = types;
        this.metamodel = metamodel;
    }

    /**
     * {@code MQ3302} for each {@code @Join}, {@code @Aggregate} or {@code @GroupBy} on an update model, which writes
     * the root's own columns only.
     */
    void checkUpdateOnly(ModelDefinition model, Diagnostics diagnostics) {
        for (ModelField field : model.fields()) {
            String where = model.name() + "." + field.name() + ": ";
            if (field.join() != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3302, where
                        + "@Join isn't allowed on @UpdateModel; write the foreign key with "
                        + foreignKey(model, field));
            }
            if (field.aggregate() != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3302,
                        where + "@Aggregate isn't allowed on @UpdateModel; an update writes columns, not groups");
            }
            if (field.groupBy()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3302,
                        where + "@GroupBy isn't allowed on @UpdateModel; an update writes columns, not groups");
            }
        }
    }

    /** The {@code @Column} that writes a {@code @Join}'s association by id, as {@code MQ3302} suggests it. */
    private String foreignKey(ModelDefinition model, ModelField join) {
        String attribute = join.join().attribute();
        Resolution resolution = metamodel.resolve(model.root(), attribute);
        TypeMirror id = resolution.attribute() == null || resolution.attribute().target() == null ? null
                : metamodel.id((TypeElement) resolution.attribute().target().asElement()).type();
        return "@Column(attribute = \"" + attribute + "\") " + (id == null ? "" : ModelValidator.display(id) + " ")
                + join.name() + "Id";
    }

    /**
     * {@code MQ3306} for a key that is not the root entity's id, which bulk writes key on, and {@code MQ3307} for a
     * field whose change-set members clash with those of {@code Changes<M>}.
     */
    void checkModel(ModelDefinition model, Diagnostics diagnostics) {
        List<ModelField> keys = model.keys();
        EntityMetamodel.Id id = metamodel.id(model.root());
        String root = model.root().getSimpleName().toString();
        String required = "@PrimaryKey must be " + root + "'s id " + id.label() + "; bulk writes key on the entity id";
        if (keys.isEmpty()) {
            // A keyless model with no aggregate is MQ3004 already; a summary model has no row to write.
            if (!model.aggregates().isEmpty()) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3306, model.name() + ": " + required);
            }
        } else if (!id.is(keys.stream().map(ModelField::attribute).collect(Collectors.toSet()))) {
            boolean reported = false;
            for (ModelField key : keys) {
                // A path that does not resolve is MQ3001; one that names part of the id is not the problem.
                if (!id.covers(key.attribute()) && metamodel.resolve(model.root(), key.attribute()).problem() == null) {
                    diagnostics.error(key.element(), DiagnosticCode.MQ3306,
                            model.name() + "." + key.name() + ": " + required);
                    reported = true;
                }
            }
            if (!reported && keys.stream().allMatch(key -> id.covers(key.attribute()))) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3306, model.name() + ": " + required);
            }
        }
        for (ModelField field : model.writable()) {
            String where = model.name() + "." + field.name() + ": ";
            if (CHANGES_MEMBERS.contains(field.name())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3307, where + "generates " + field.name() + "("
                        + ModelValidator.display(boxed(field.type())) + "), which clashes with Changes."
                        + field.name() + "(...); rename the field");
            } else if (field.name().equals(EMPTY)) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3307, where
                        + "generates getEmpty() and setEmpty(...), which clash with Changes.isEmpty() as property "
                        + "'empty'; rename the field");
            }
        }
    }

    /**
     * {@code MQ3301} for a path through an association or to a collection; {@code MQ3303} for the id written
     * without {@code @PrimaryKey} or for the {@code @Version}; {@code MQ3304} for an attribute that can't be
     * written; {@code MQ3305} for a to-one written by id from a field of another type than the target's id.
     *
     * @return whether the column's type still needs the check a query model's column gets
     */
    boolean checkColumn(ModelDefinition model, ModelField field, Resolution resolution, String where,
            Diagnostics diagnostics) {
        String root = model.root().getSimpleName().toString();
        if (resolution.association()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3301, where + "update models can only write "
                    + "attributes of " + root + "; '" + field.attribute() + "' needs a join");
            return false;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute == null) {
            return true;
        }
        if (attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3301, where + "update models can only write "
                    + "attributes of " + root + "; '" + field.attribute() + "' is a collection");
            return false;
        }
        String declared = resolution.owner() + "." + attribute.name();
        if (!field.primaryKey()) {
            if (metamodel.id(model.root()).covers(field.attribute())) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3303, where + "'" + field.attribute() + "' is "
                        + root + "'s id, which an update can't write; mark the field @PrimaryKey to key on it");
            } else if (attribute.version()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3303,
                        where + "the @Version attribute is managed by the engine (keepVersion, expectVersion)");
            } else if (!attribute.updatable()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3304, where + declared + " is "
                        + (attribute.kind() == EntityAttribute.Kind.TO_ONE ? "@JoinColumn" : "@Column")
                        + "(updatable = false)");
            } else if (attribute.mappedBy() != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3304, where + declared
                        + " is the inverse side of a to-one (mappedBy = \"" + attribute.mappedBy()
                        + "\"); write it from the owning side");
            }
        }
        if (attribute.kind() != EntityAttribute.Kind.TO_ONE || field.converter() != null) {
            return true;
        }
        // A to-one is written by id, so the field holds the target's id, not the target (R-GEN-19).
        TypeMirror id = attribute.target() == null ? null
                : metamodel.id((TypeElement) attribute.target().asElement()).type();
        if (id != null && !types.isSameType(boxed(field.type()), boxed(id))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3305,
                    where + attribute.target().asElement().getSimpleName() + "'s id is "
                            + ModelValidator.display(boxed(id)) + ", found " + ModelValidator.display(field.type()));
        }
        return false;
    }

    private TypeMirror boxed(TypeMirror type) {
        return type.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) type).asType() : type;
    }
}
