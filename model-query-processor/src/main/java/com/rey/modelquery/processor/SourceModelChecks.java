package com.rey.modelquery.processor;

import com.rey.modelquery.processor.ModelDefinition.ModelField;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The source-over-source restrictions and inherited key metadata (R-SRC-16, R-PROC-40). */
final class SourceModelChecks {

    private static final Set<String> NON_NULL_WINDOWS = Set.of("ROW_NUMBER", "RANK", "DENSE_RANK", "COUNT");

    private final EntityMetamodel metamodel;
    private final List<ModelDefinition> sources;

    SourceModelChecks(EntityMetamodel metamodel, List<ModelDefinition> sources) {
        this.metamodel = metamodel;
        this.sources = List.copyOf(sources);
    }

    boolean kindOk(ModelDefinition model, Diagnostics diagnostics) {
        if (model.sourceModel() && model.readsSource()) {
            if (model.source().branch()) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3042, model.name() + ": root "
                        + model.source().name() + " is a branch; root at the shape "
                        + model.source().shape().name() + " instead");
                return false;
            }
            String kind = model.recursive() ? "recursive" : model.branch() ? "a branch"
                    : branches(model).isEmpty() ? null : "a shape";
            if (kind != null) {
                diagnostics.error(model.type(), DiagnosticCode.MQ3042, model.name() + ": a source over "
                        + model.source().name() + " cannot be " + kind);
                return false;
            }
        }
        if (model.branch() && model.shape().readsSource()) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3042, model.name() + ": the shape "
                    + model.shape().name() + " is a source over " + model.shape().source().name()
                    + "; a source over a source cannot be a shape");
            return false;
        }
        return true;
    }

    void checkKey(ModelDefinition model, Diagnostics diagnostics) {
        List<String> expected = model.source().keys().stream().map(ModelField::name).toList();
        List<String> actual = model.keys().stream().map(ModelField::attribute).toList();
        if (!actual.equals(expected)) {
            diagnostics.error(model.type(), DiagnosticCode.MQ3031, model.name() + ": @PrimaryKey must be "
                    + model.source().name() + "'s key fields "
                    + expected.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "))
                    + "; an ungrouped source reads one row per key of the inner source " + model.source().name()
                    + "; group the source to key it by its @GroupBy columns");
        }
    }

    boolean nullable(ModelDefinition model, ModelField field) {
        if (nullableField(model, field)) {
            return true;
        }
        return branches(model).stream().anyMatch(branch -> branch.readable().stream()
                .filter(each -> each.name().equals(field.name())).anyMatch(each -> nullableField(branch, each)));
    }

    private boolean nullableField(ModelDefinition model, ModelField field) {
        if (field.depth() || field.discriminated()) {
            return false;
        }
        if (field.windowed()) {
            return !NON_NULL_WINDOWS.contains(field.window().function());
        }
        if (field.aggregate() != null) {
            return !field.aggregate().fn().equals("COUNT");
        }
        // A computed expression's nullability is checked when it resolves (MQ2201/MQ2202, R-SRC-02).
        if (field.computed()) {
            return false;
        }
        if (model.readsSource()) {
            ModelField inner = model.sourceField(field.attribute());
            return inner != null && nullable(model.source(), inner);
        }
        EntityAttribute attribute = metamodel.resolve(model.root(), field.attribute()).attribute();
        return attribute != null && attribute.nullable();
    }

    private List<ModelDefinition> branches(ModelDefinition shape) {
        return sources.stream().filter(model -> model.branch()
                && model.shape().type().equals(shape.type())).toList();
    }
}
