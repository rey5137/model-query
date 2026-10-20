package com.rey.modelquery.processor;

import java.util.LinkedHashMap;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/** The shared treated root nodes of one model, in declaration order (R-GEN-41). */
final class TreatTables {

    private TreatTables() {}

    static List<TypeElement> targets(ModelDefinition model) {
        var targets = new LinkedHashMap<String, TypeElement>();
        model.fields().forEach(field -> {
            add(targets, field.treat());
            if (field.join() != null) {
                add(targets, field.join().treat());
            }
        });
        model.filterColumns().forEach(column -> add(targets, column.treat()));
        return List.copyOf(targets.values());
    }

    static String root(TypeMirror treat) {
        return treat == null ? "ROOT" : constant((TypeElement) ((DeclaredType) treat).asElement());
    }

    static String constant(TypeElement subtype) {
        return QueryModelReader.constantName(subtype.getSimpleName().toString()) + "_TREAT";
    }

    private static void add(LinkedHashMap<String, TypeElement> targets, TypeMirror treat) {
        if (treat instanceof DeclaredType declared) {
            var subtype = (TypeElement) declared.asElement();
            targets.putIfAbsent(subtype.getQualifiedName().toString(), subtype);
        }
    }
}
