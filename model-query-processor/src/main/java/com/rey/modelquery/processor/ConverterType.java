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
 * @param hasInstance whether the class has a public static {@code INSTANCE} field to take the converter from, typed
 *     as an {@code OrderedColumnConverter} when the class is one
 */
record ConverterType(TypeElement type, TypeMirror model, TypeMirror attribute, boolean hasInstance) {

    private static final String COLUMN_CONVERTER = "com.rey.modelquery.core.ColumnConverter";
    private static final String ORDERED_COLUMN_CONVERTER = "com.rey.modelquery.core.OrderedColumnConverter";

    /** Reads {@code converter}, or returns {@code null} when it implements no {@code ColumnConverter<C, F>}. */
    static ConverterType of(Types types, TypeMirror converter) {
        DeclaredType implemented = implemented(types, converter, COLUMN_CONVERTER);
        if (implemented == null || implemented.getTypeArguments().size() != 2) {
            return null;
        }
        var type = (TypeElement) ((DeclaredType) converter).asElement();
        // An ordered converter's INSTANCE must be typed as one, or ColumnField.of would pick the overload returning a
        // plain ColumnField for a constant declared an OrderedColumnField (D-93); otherwise the constructor is used.
        String instanceType = ordered(types, converter) ? ORDERED_COLUMN_CONVERTER : COLUMN_CONVERTER;
        boolean hasInstance = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .anyMatch(field -> field.getSimpleName().contentEquals("INSTANCE")
                        && field.getModifiers().contains(Modifier.PUBLIC)
                        && field.getModifiers().contains(Modifier.STATIC)
                        && implemented(types, field.asType(), instanceType) != null);
        return new ConverterType(
                type, implemented.getTypeArguments().get(0), implemented.getTypeArguments().get(1), hasInstance);
    }

    /**
     * Whether a column with {@code converter}, or none ({@code null}), is an {@code OrderedColumnField}: the converter
     * implements the {@code core} interface {@code OrderedColumnConverter}, found by name (D-93).
     */
    static boolean ordered(Types types, TypeMirror converter) {
        return converter == null || implemented(types, converter, ORDERED_COLUMN_CONVERTER) != null;
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
