package com.rey.modelquery.processor;

import java.util.Map;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * The ordered converters {@code core} ships, which a column takes when its model type and its {@code Timestamp}
 * attribute form one of their pairs and no {@code converter} is named (R-PROC-07, D-84). They are {@code core} types,
 * so they are found by name.
 */
final class BuiltInConverters {

    private static final String TIMESTAMP = "java.sql.Timestamp";
    private static final Map<String, String> BY_MODEL_TYPE = Map.of(
            "java.time.Instant", "com.rey.modelquery.core.InstantTimestampConverter",
            "java.util.Date", "com.rey.modelquery.core.DateTimestampConverter");

    private final Elements elements;

    BuiltInConverters(Elements elements) {
        this.elements = elements;
    }

    /**
     * The built-in converter class between {@code model} and {@code attribute}, or {@code null} when they form no pair
     * or the compile classpath has no such class.
     */
    TypeMirror between(TypeMirror model, TypeMirror attribute) {
        String converter = BY_MODEL_TYPE.get(ModelValidator.qualified(model));
        if (converter == null || !TIMESTAMP.equals(ModelValidator.qualified(attribute))) {
            return null;
        }
        TypeElement type = elements.getTypeElement(converter);
        return type == null ? null : type.asType();
    }
}
