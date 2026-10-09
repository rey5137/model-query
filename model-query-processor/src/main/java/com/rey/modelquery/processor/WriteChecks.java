package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Transient;
import com.rey.modelquery.processor.EntityMetamodel.Resolution;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * The checks of a model that writes: an {@code @UpdateModel}, a {@code @QueryModel} with
 * {@code generateChanges = true} ({@code MQ3301}..{@code MQ3307}), or an {@code @InsertModel} ({@code MQ3301},
 * {@code MQ3303}..{@code MQ3305} on its columns, {@code MQ3501}, {@code MQ3502} and {@code MQ3504}), or a
 * {@code @QueryModel} with {@code generateInserts = true} ({@code MQ3501}, {@code MQ3504} and {@code MQ3505}).
 *
 * @implSpec R-GEN-19, R-GEN-22, R-GEN-23, R-PROC-23, R-PROC-26, R-DIAG-01, R-DIAG-02
 */
final class WriteChecks {

    /** The members of {@code Changes<M>} that a change set's fluent setter must not overload (R-GEN-23). */
    private static final Set<String> CHANGES_MEMBERS = Set.of("isEmpty", "isSet", "unset", "assignments");

    /** The property {@code Changes.isEmpty()} reads as, which a field's getter and setter must not name too. */
    private static final String EMPTY = "empty";

    /** The tail of an {@code MQ3502} message for an annotation an insert model cannot carry. */
    private static final String READS_NO_ROW = " isn't allowed on @InsertModel; an insert reads no row of its root";

    private final Types types;
    private final EntityMetamodel metamodel;

    WriteChecks(Types types, EntityMetamodel metamodel) {
        this.types = types;
        this.metamodel = metamodel;
    }

    /**
     * {@code MQ3302} for each {@code @Join}, {@code @Aggregate}, {@code @GroupBy}, {@code @Computed} or
     * {@code @Selected} on an update model, which writes the root's own columns only.
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
            if (field.computed()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3302,
                        where + "@Computed isn't allowed on @UpdateModel; an update writes columns, not expressions");
            }
            if (field.selected()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3302, where
                        + "@Selected isn't allowed on @UpdateModel; an update reads no row into the model");
            }
        }
    }

    /**
     * {@code MQ3502} for each {@code @Join}, {@code @FilterColumn}, {@code @Aggregate}, {@code @GroupBy},
     * {@code @Computed}, {@code @Child} or {@code @Selected} on an insert model, which reads no row of its root, and
     * each {@code @Transient}, since every field is a column (R-PROC-23, R-WRT-25).
     */
    void checkInsertOnly(ModelDefinition model, Diagnostics diagnostics) {
        for (ModelField field : model.fields()) {
            String where = model.name() + "." + field.name() + ": ";
            if (field.child() != null) {
                readsRow(diagnostics, field, where, "@Child");
                continue;
            }
            if (field.element().getAnnotation(Transient.class) != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3502, where + "@Transient isn't allowed on "
                        + "@InsertModel; every field is a column of the rows an insert writes");
            }
            if (field.join() != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3502,
                        where + "@Join isn't allowed on @InsertModel; write the foreign key with "
                                + foreignKey(model, field));
            }
            if (field.aggregate() != null) {
                readsRow(diagnostics, field, where, "@Aggregate");
            }
            if (field.groupBy()) {
                readsRow(diagnostics, field, where, "@GroupBy");
            }
            if (field.computed()) {
                readsRow(diagnostics, field, where, "@Computed");
            }
            if (field.selected()) {
                readsRow(diagnostics, field, where, "@Selected");
            }
        }
        for (ModelDefinition.FilterColumnDefinition column : model.filterColumns()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3502,
                    model.name() + " " + column.label() + ": @FilterColumn" + READS_NO_ROW);
        }
    }

    /** {@code MQ3502} for {@code annotation}, which reads a row of the root, on an insert model's field. */
    private static void readsRow(Diagnostics diagnostics, ModelField field, String where, String annotation) {
        diagnostics.error(field.element(), DiagnosticCode.MQ3502, where + annotation + READS_NO_ROW);
    }

    /**
     * {@code MQ3501} unless an insert model names its root's id with {@code @PrimaryKey} exactly when the id has no
     * {@code @GeneratedValue}, and {@code MQ3504} when the processor sees no id type, which the generated
     * {@code insert} and {@code persist} then type as {@code Object} (R-PROC-23, D-117).
     */
    void checkInsertKey(ModelDefinition model, Diagnostics diagnostics) {
        EntityMetamodel.Id id = metamodel.id(model.root());
        String root = model.root().getSimpleName().toString();
        warnUntypedId(model, id, diagnostics);
        if (id.attributes().isEmpty()) {
            return;
        }
        boolean reported = false;
        for (ModelField field : model.columns()) {
            String where = model.name() + "." + field.name() + ": ";
            if (id.covers(field.attribute())) {
                if (id.generated()) {
                    diagnostics.error(field.element(), DiagnosticCode.MQ3501, where + root + "'s id " + id.label()
                            + " is generated (@GeneratedValue or a generator annotation); leave it out of the model");
                    reported = true;
                } else if (!field.primaryKey()) {
                    diagnostics.error(field.element(), DiagnosticCode.MQ3501, where + "'" + field.attribute()
                            + "' is " + root + "'s id; mark the field @PrimaryKey");
                    reported = true;
                }
            } else if (field.primaryKey() && metamodel.resolve(model.root(), field.attribute()).problem() == null) {
                // A path that does not resolve is MQ3001.
                diagnostics.error(field.element(), DiagnosticCode.MQ3501, where + "@PrimaryKey must be " + root
                        + "'s id " + id.label() + (id.generated()
                                ? ", which is generated (@GeneratedValue or a generator annotation); remove it"
                                : "; an insert writes the id"));
                reported = true;
            }
        }
        Set<String> keys = model.keys().stream().map(ModelField::attribute).collect(Collectors.toSet());
        if (!reported && !id.generated() && !id.is(keys)) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3501, model.name() + ": " + root + "'s id " + id.label()
                    + " has no @GeneratedValue or generator annotation; name it with @PrimaryKey");
        }
    }

    /**
     * {@code MQ3505} for a {@code generateInserts} query model that is grouped or writes no root column; otherwise
     * {@code MQ3501} only when the root's id is assigned and the model's {@code @PrimaryKey} fields don't name all of
     * it, since {@code addKey} follows the id and another {@code @PrimaryKey} is written with {@code add}; and
     * {@code MQ3504} as on an insert model (R-PROC-26, D-123).
     */
    void checkGeneratedInserts(ModelDefinition model, Diagnostics diagnostics) {
        List<InsertedColumns.Written> written = InsertedColumns.of(model, metamodel).written();
        if (model.grouped() || written.isEmpty()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3505, model.name()
                    + ": generateInserts needs an ungrouped model with at least one root column it can write");
            // No insert member can be generated, so what would key it is beside the point.
            return;
        }
        EntityMetamodel.Id id = metamodel.id(model.root());
        warnUntypedId(model, id, diagnostics);
        // Only a @PrimaryKey the insert writes names the id: a to-one @MapsId or insertable = false one is left out.
        Set<String> keys = written.stream().map(InsertedColumns.Written::field).filter(ModelField::primaryKey)
                .map(ModelField::attribute).collect(Collectors.toSet());
        boolean named = keys.containsAll(id.attributes())
                || !id.components().isEmpty() && keys.containsAll(id.components());
        if (!id.generated() && !named) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3501, model.name() + ": "
                    + model.root().getSimpleName() + "'s id " + id.label() + " has no @GeneratedValue or generator "
                    + "annotation; generateInserts writes it, so name it with @PrimaryKey");
        }
    }

    /** {@code MQ3504} when the processor sees no id type, so {@code insert} and {@code persist} type it Object. */
    private static void warnUntypedId(ModelDefinition model, EntityMetamodel.Id id, Diagnostics diagnostics) {
        if (id.type() == null) {
            diagnostics.warning(model.type(), DiagnosticCode.MQ3504, model.name() + ": "
                    + model.root().getSimpleName() + " has no id type the processor can see; insert(rows) and "
                    + "persist(row) return its keys as Object");
        }
    }

    /** The {@code @Column} that writes a {@code @Join}'s association by id, as {@code MQ3302} suggests it. */
    private String foreignKey(ModelDefinition model, ModelField join) {
        String attribute = join.join().attribute();
        Resolution resolution = metamodel.resolve(model.root(), attribute);
        TypeMirror id = resolution.attribute() == null ? null : targetIdType(resolution.attribute());
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
     * written; {@code MQ3305} for a to-one written by id from a field of another type than the target's id. On an
     * insert model the id is {@link #checkInsertKey}'s, and {@code insertable = false} stands in for
     * {@code updatable = false}.
     *
     * @return whether the column's type still needs the check a query model's column gets
     */
    boolean checkColumn(ModelDefinition model, ModelField field, Resolution resolution, String where,
            Diagnostics diagnostics) {
        String root = model.root().getSimpleName().toString();
        String models = model.insertModel() ? "insert models" : "update models";
        if (resolution.association()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3301, where + models + " can only write "
                    + "attributes of " + root + "; '" + field.attribute() + "' needs a join");
            return false;
        }
        EntityAttribute attribute = resolution.attribute();
        if (attribute == null) {
            return true;
        }
        if (attribute.kind() == EntityAttribute.Kind.COLLECTION) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3301, where + models + " can only write "
                    + "attributes of " + root + "; '" + field.attribute() + "' is a collection");
            return false;
        }
        String declared = resolution.owner() + "." + attribute.name();
        if (model.insertModel()) {
            if (attribute.version()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3303,
                        where + "the @Version attribute is written by the provider, with its seed value");
            } else if (!attribute.insertable()) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3304, where + declared + " is "
                        + (attribute.kind() == EntityAttribute.Kind.TO_ONE ? "@JoinColumn" : "@Column")
                        + "(insertable = false)");
            } else if (attribute.mappedBy() != null) {
                diagnostics.error(field.element(), DiagnosticCode.MQ3304, where + declared
                        + " is the inverse side of a to-one (mappedBy = \"" + attribute.mappedBy()
                        + "\"); write it from the owning side");
            }
        } else if (!field.primaryKey()) {
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
        TypeMirror id = targetIdType(attribute);
        if (id != null && !types.isSameType(boxed(field.type()), boxed(id))) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3305,
                    where + attribute.target().asElement().getSimpleName() + "'s id is "
                            + ModelValidator.display(boxed(id)) + ", found " + ModelValidator.display(field.type()));
        }
        return false;
    }

    /** The id type of the entity a to-one or collection {@code attribute} targets, or {@code null} without one. */
    private TypeMirror targetIdType(EntityAttribute attribute) {
        return attribute.target() == null ? null
                : metamodel.id((TypeElement) attribute.target().asElement()).type();
    }

    private TypeMirror boxed(TypeMirror type) {
        return ProcessorTypes.boxed(types, type);
    }
}
