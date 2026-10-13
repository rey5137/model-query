package com.rey.modelquery.processor;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * The JSpecify annotations the writers add when {@code modelquery.jspecify} is on, and none when it is off, so that
 * the output of an unconfigured build stays byte-identical (R-GEN-36). The annotations are named by string: the
 * processor has no dependency on JSpecify.
 *
 * @implSpec R-PROC-32, R-GEN-36, D-135
 */
record Nullness(boolean on) {

    static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";
    private static final ClassName MARKED = ClassName.get("org.jspecify.annotations", "NullMarked");
    private static final ClassName UNMARKED = ClassName.get("org.jspecify.annotations", "NullUnmarked");
    private static final ClassName NULLABLE = ClassName.get("org.jspecify.annotations", "Nullable");

    /** The all-off instance, which changes nothing. */
    static final Nullness OFF = new Nullness(false);

    /** {@code @NullMarked} on a generated class. */
    void marked(TypeSpec.Builder type) {
        if (on) {
            type.addAnnotation(MARKED);
        }
    }

    /** {@code @NullUnmarked} on a generated method whose generic code the checkers can't follow. */
    MethodSpec.Builder unmarked(MethodSpec.Builder method) {
        return on ? method.addAnnotation(UNMARKED) : method;
    }

    /** {@code type} as a type-use {@code @Nullable}, so {@code java.lang.@Nullable String}, or {@code type} itself. */
    TypeName nullable(TypeName type) {
        return on ? type.annotated(AnnotationSpec.builder(NULLABLE).build()) : type;
    }
}
