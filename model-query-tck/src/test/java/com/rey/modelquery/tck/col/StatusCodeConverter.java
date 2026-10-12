package com.rey.modelquery.tck.col;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code constant_rows.state}: a {@link ConstantStatus} over its first letter, not its name (TCK AC-COL-32). */
@Converter
public class StatusCodeConverter implements AttributeConverter<ConstantStatus, String> {
    @Override
    public String convertToDatabaseColumn(ConstantStatus attribute) {
        return attribute == null ? null : attribute.name().substring(0, 1);
    }

    @Override
    public ConstantStatus convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        for (ConstantStatus status : ConstantStatus.values()) {
            if (status.name().startsWith(stored)) {
                return status;
            }
        }
        throw new IllegalArgumentException("No status stored as " + stored);
    }
}
