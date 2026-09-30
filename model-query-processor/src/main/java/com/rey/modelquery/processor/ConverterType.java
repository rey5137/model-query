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
 * A class named by {@code @Column(converter)}, read as the {@code ColumnConverter<C, F>} it implements. The interface
 * is a {@code core} type, so it is found by name (D-37).
 *
 * @param type the converter class
 * @param model {@code C}, the type the model holds
 * @param attribute {@code F}, the entity attribute's type
 * @param hasInstance whether the class has a public static {@code INSTANCE} field to take the converter from
 */
record ConverterType(TypeElement type, TypeMirror model, TypeMirror attribute, boolean hasInstance) {

    private static final String COLUMN_CONVERTER = "com.rey.modelquery.core.ColumnConverter";

    /** Reads {@code converter}, or returns {@code null} when it implements no {@code ColumnConverter<C, F>}. */
    static ConverterType of(Types types, TypeMirror converter) {
        DeclaredType implemented = implemented(types, converter);
        if (implemented == null || implemented.getTypeArguments().size() != 2) {
            return null;
        }
        var type = (TypeElement) ((DeclaredType) converter).asElement();
        boolean hasInstance = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .anyMatch(field -> field.getSimpleName().contentEquals("INSTANCE")
                        && field.getModifiers().contains(Modifier.PUBLIC)
                        && field.getModifiers().contains(Modifier.STATIC));
        return new ConverterType(
                type, implemented.getTypeArguments().get(0), implemented.getTypeArguments().get(1), hasInstance);
    }

    private static DeclaredType implemented(Types types, TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        var declared = (DeclaredType) type;
        if (((TypeElement) declared.asElement()).getQualifiedName().contentEquals(COLUMN_CONVERTER)) {
            return declared;
        }
        for (TypeMirror supertype : types.directSupertypes(type)) {
            DeclaredType found = implemented(types, supertype);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Whether code in {@code from}'s package can call the converter's no-arg constructor. */
    boolean hasVisibleConstructor(TypeElement from) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return false;
        }
        boolean samePackage = packageOf(type).equals(packageOf(from));
        return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .anyMatch(constructor -> constructor.getParameters().isEmpty()
                        && (constructor.getModifiers().contains(Modifier.PUBLIC)
                                || samePackage && !constructor.getModifiers().contains(Modifier.PRIVATE)));
    }

    private static String packageOf(TypeElement type) {
        Element element = type;
        while (element.getKind() != ElementKind.PACKAGE) {
            element = element.getEnclosingElement();
        }
        return ((PackageElement) element).getQualifiedName().toString();
    }
}
