package com.rey.modelquery.processor;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.processor.ModelDefinition.ModelField;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;

/** Declaration checks that must precede reading a subtype's attributes (R-PROC-41, R-PROC-43). */
final class TreatChecks {

    private static final String CLASS = "java.lang.Class";

    private final Types types;
    private final EntityMetamodel metamodel;

    TreatChecks(Types types, EntityMetamodel metamodel) {
        this.types = types;
        this.metamodel = metamodel;
    }

    boolean check(ModelDefinition model, Diagnostics diagnostics) {
        boolean valid = true;
        ModelField firstSubtype = null;
        for (ModelField field : model.fields()) {
            String where = model.name() + "." + field.name() + ": ";
            if (field.treat() != null) {
                valid &= checkTreat(model, field.element(), field.treat(), field.attribute(), field,
                        where, diagnostics);
            }
            // Read even a @Join suppressed by @Transient or @Child: a write must never silently drop a treat.
            TypeMirror joinTreat = QueryModelReader.treat(field.element(), Join.class);
            if (joinTreat != null) {
                String attribute = field.element().getAnnotation(Join.class).attribute();
                valid &= checkTreat(model, field.element(), joinTreat,
                        attribute.isEmpty() ? field.name() : attribute, field, where, diagnostics);
            }
            if (field.subtype()) {
                valid &= checkSubtype(model, field, firstSubtype, where, diagnostics);
                if (firstSubtype == null) {
                    firstSubtype = field;
                }
            }
        }
        for (var column : model.filterColumns()) {
            if (column.treat() != null) {
                valid &= checkTreat(model, model.type(), column.treat(), column.path(), null,
                        model.name() + " " + column.label() + ": ", diagnostics);
            }
        }
        return valid;
    }

    private boolean checkTreat(ModelDefinition model, Element element, TypeMirror treat, String path,
            ModelField field, String where, Diagnostics diagnostics) {
        String problem = null;
        if (model.readsSource() || !model.readModel()) {
            problem = "treat needs an entity-rooted read model";
        } else if (model.generateChanges() || model.generateInserts()) {
            problem = "treat cannot be combined with generateChanges or generateInserts";
        } else if (field != null && (field.primaryKey() || field.discriminated()
                || model.children().stream().anyMatch(child -> child.child().key().contains(field.attribute())))) {
            problem = "treat is not allowed on a key or @Discriminator; read the root's attribute";
        } else if (!metamodel.strictEntitySubtype(treat, model.root())) {
            problem = "treat " + ModelValidator.display(treat) + " must be a strict @Entity subtype of "
                    + model.root().getSimpleName();
        } else {
            String first = path.split("\\.", -1)[0];
            if (metamodel.resolve(model.root(), first).attribute() != null) {
                problem = "'" + first + "' is already declared by " + model.root().getSimpleName()
                        + "'s hierarchy; drop treat";
            }
        }
        if (problem != null) {
            diagnostics.error(element, DiagnosticCode.MQ3042, where + problem);
            return false;
        }
        return true;
    }

    private boolean checkSubtype(ModelDefinition model, ModelField field, ModelField first, String where,
            Diagnostics diagnostics) {
        String problem = null;
        if (model.readsSource() || !model.readModel()) {
            problem = "@Subtype needs an entity-rooted read model";
        } else if (model.generateChanges() || model.generateInserts()) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3042,
                    where + "@Subtype cannot be combined with generateChanges or generateInserts");
            return false;
        } else if (first != null) {
            problem = "a second @Subtype; the first is " + first.name();
        } else if (field.element().getAnnotation(Column.class) != null
                || field.element().getAnnotation(Computed.class) != null
                || field.element().getAnnotation(Aggregate.class) != null || field.primaryKey()) {
            problem = "@Subtype cannot be combined with @Column, @Computed, @Aggregate or @PrimaryKey";
        } else if (!(field.type() instanceof DeclaredType declared)
                || !((TypeElement) declared.asElement()).getQualifiedName().contentEquals(CLASS)
                || declared.getTypeArguments().size() != 1
                || !(declared.getTypeArguments().get(0) instanceof WildcardType wildcard)
                || wildcard.getExtendsBound() == null
                || !types.isSameType(wildcard.getExtendsBound(), model.root().asType())) {
            problem = "@Subtype must be Class<? extends " + model.root().getSimpleName() + ">, found "
                    + ModelValidator.display(field.type());
        }
        if (problem != null) {
            diagnostics.error(field.element(), DiagnosticCode.MQ3043, where + problem);
            return false;
        }
        return true;
    }
}
