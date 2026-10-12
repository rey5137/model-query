package com.rey.modelquery.tck.col;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code constant_rows.active}: a {@code Boolean} over a {@code Y}/{@code N} column (TCK AC-COL-32). */
@Converter
public class YesNoConverter implements AttributeConverter<Boolean, String> {
    @Override
    public String convertToDatabaseColumn(Boolean attribute) {
        return attribute == null ? null : attribute ? "Y" : "N";
    }

    @Override
    public Boolean convertToEntityAttribute(String stored) {
        return stored == null ? null : "Y".equals(stored);
    }
}
