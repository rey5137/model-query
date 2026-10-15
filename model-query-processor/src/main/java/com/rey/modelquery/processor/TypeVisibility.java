package com.rey.modelquery.processor;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/** Whether a generated QModel in a given package can name a class and call its no-arg constructor. */
final class TypeVisibility {

    private TypeVisibility() {}

    /** Whether the QModel, generated in {@code fromPackage}, can call {@code type}'s no-arg constructor. */
    static boolean hasVisibleConstructor(TypeElement type, String fromPackage) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return false;
        }
        boolean samePackage = packageOf(type).equals(fromPackage);
        return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty()
                        && (constructor.getModifiers().contains(Modifier.PUBLIC)
                                || samePackage && !constructor.getModifiers().contains(Modifier.PRIVATE)));
    }

    /**
     * Whether the QModel, generated in {@code fromPackage}, can name {@code type}: it and the types enclosing it are
     * public, or it is in that package and not private.
     */
    static boolean isVisibleTo(TypeElement type, String fromPackage) {
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
