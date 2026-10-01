package com.rey.modelquery.processor;

import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/** Type helpers the processor's validators and writers share. */
final class ProcessorTypes {

    private ProcessorTypes() {}

    /** {@code type}, boxed when it is a primitive. */
    static TypeMirror boxed(Types types, TypeMirror type) {
        return type.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) type).asType() : type;
    }
}
