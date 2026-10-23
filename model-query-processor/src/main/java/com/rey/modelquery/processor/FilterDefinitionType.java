package com.rey.modelquery.processor;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * A class named by {@code @Aggregate(filter)}, read as the {@code FilterDefinition<M>} it implements. The interface is
 * a {@code core} type, so it is found by name, as {@link ExpressionDefinitionType} does (R-PROC-45).
 *
 * @param type the definition class
 * @param model {@code M}, the model the condition belongs to
 * @param hasInstance whether the class has a public static {@code INSTANCE} field to take the definition from
 */
record FilterDefinitionType(TypeElement type, TypeMirror model, boolean hasInstance) {

    private static final String FILTER_DEFINITION = "com.rey.modelquery.core.FilterDefinition";

    /** Reads {@code definition}, or returns {@code null} when it implements no {@code FilterDefinition<M>}. */
    static FilterDefinitionType of(Types types, TypeMirror definition) {
        if (definition == null) {
            return null;
        }
        DeclaredType implemented = ExpressionDefinitionType.implemented(types, definition, FILTER_DEFINITION);
        if (implemented == null || implemented.getTypeArguments().size() != 1) {
            return null;
        }
        var type = (TypeElement) ((DeclaredType) definition).asElement();
        boolean hasInstance = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .anyMatch(field -> field.getSimpleName().contentEquals("INSTANCE")
                        && field.getModifiers().contains(Modifier.PUBLIC)
                        && field.getModifiers().contains(Modifier.STATIC)
                        && types.isAssignable(field.asType(), definition));
        return new FilterDefinitionType(type, implemented.getTypeArguments().get(0), hasInstance);
    }

    /** Whether the QModel, generated in {@code fromPackage}, can call the definition's no-arg constructor. */
    boolean hasVisibleConstructor(String fromPackage) {
        return TypeVisibility.hasVisibleConstructor(type, fromPackage);
    }

    /** Whether the QModel, generated in {@code fromPackage}, can name the definition's class. */
    boolean isVisibleTo(String fromPackage) {
        return TypeVisibility.isVisibleTo(type, fromPackage);
    }
}
