package com.rey.modelquery.processor;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * A class named by {@code @Computed} or {@code @Aggregate(expression)}, read as the
 * {@code ExpressionDefinition<M, C>} it implements. The interface is a {@code core} type, so it is found by name
 * (D-37, R-PROC-01).
 *
 * @param type the definition class
 * @param model {@code M}, the model the expression belongs to
 * @param value {@code C}, the expression's type
 * @param hasInstance whether the class has a public static {@code INSTANCE} field to take the definition from
 */
record ExpressionDefinitionType(TypeElement type, TypeMirror model, TypeMirror value, boolean hasInstance) {

    private static final String EXPRESSION_DEFINITION = "com.rey.modelquery.core.ExpressionDefinition";

    /**
     * Reads {@code definition}, or returns {@code null} when it implements no
     * {@code ExpressionDefinition<M, C>}.
     */
    static ExpressionDefinitionType of(Types types, TypeMirror definition) {
        if (definition == null) {
            return null;
        }
        DeclaredType implemented = implemented(types, definition, EXPRESSION_DEFINITION);
        if (implemented == null || implemented.getTypeArguments().size() != 2) {
            return null;
        }
        var type = (TypeElement) ((DeclaredType) definition).asElement();
        // The generated code calls INSTANCE.expression() only when the field is typed as the class, not a supertype.
        boolean hasInstance = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .anyMatch(field -> field.getSimpleName().contentEquals("INSTANCE")
                        && field.getModifiers().contains(Modifier.PUBLIC)
                        && field.getModifiers().contains(Modifier.STATIC)
                        && types.isAssignable(field.asType(), definition));
        return new ExpressionDefinitionType(
                type, implemented.getTypeArguments().get(0), implemented.getTypeArguments().get(1), hasInstance);
    }

    /** Whether the QModel, generated in {@code fromPackage}, can call the definition's no-arg constructor. */
    boolean hasVisibleConstructor(String fromPackage) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return false;
        }
        boolean samePackage = packageOf(type).equals(fromPackage);
        return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty()
                        && (constructor.getModifiers().contains(Modifier.PUBLIC)
                                || samePackage && !constructor.getModifiers().contains(Modifier.PRIVATE)));
    }

    private static DeclaredType implemented(Types types, TypeMirror type, String name) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        var declared = (DeclaredType) type;
        if (((TypeElement) declared.asElement()).getQualifiedName().contentEquals(name)) {
            return declared;
        }
        for (TypeMirror supertype : types.directSupertypes(type)) {
            DeclaredType found = implemented(types, supertype, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Whether the QModel, generated in {@code fromPackage}, can name the definition's class: it and the types enclosing it
     * are public, or it is in that package and not private.
     */
    boolean isVisibleTo(String fromPackage) {
        boolean samePackage = packageOf(type).equals(fromPackage);
        for (Element element = type; element instanceof TypeElement; element = element.getEnclosingElement()) {
            var modifiers = element.getModifiers();
            if (!modifiers.contains(Modifier.PUBLIC) && !(samePackage && !modifiers.contains(Modifier.PRIVATE))) {
                return false;
            }
        }
        return true;
    }

    private static String packageOf(TypeElement type) {
        Element element = type;
        while (element.getKind() != ElementKind.PACKAGE) {
            element = element.getEnclosingElement();
        }
        return ((PackageElement) element).getQualifiedName().toString();
    }
}
