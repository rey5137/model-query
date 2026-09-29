package com.rey.modelquery.tck.col;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** The fixture's order statuses, read through a JPA {@link AttributeConverter} from {@code orders.status}. */
public enum OrderStatus {
    NEW,
    PAID,
    SHIPPED,
    CANCELLED;

    /** Converts the stored status text to the enum and back. */
    @Converter
    public static class TextConverter implements AttributeConverter<OrderStatus, String> {
        @Override
        public String convertToDatabaseColumn(OrderStatus status) {
            return status == null ? null : status.name();
        }

        @Override
        public OrderStatus convertToEntityAttribute(String text) {
            return text == null ? null : OrderStatus.valueOf(text);
        }
    }
}
