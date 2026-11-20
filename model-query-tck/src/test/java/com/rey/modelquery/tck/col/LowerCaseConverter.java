package com.rey.modelquery.tck.col;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** Converts map keys to lower-case on read, creating realistic duplicate Java keys for AC-FCH-41 / MQ2609. */
@Converter
public class LowerCaseConverter implements AttributeConverter<String, String> {
    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : dbData.toLowerCase(Locale.ROOT);
    }
}
