package com.rey.modelquery.tck.col;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Fixture entity over {@code keyset_types}, one column per cursor type the TCK pages by (AC-PAG-18): a scale-keeping
 * decimal, a microsecond timestamp, a UUID stored as text, a byte array and a value class behind a
 * {@link AttributeConverter} that no cursor codec carries.
 */
@Entity
@Table(name = "keyset_types")
public class KeysetTypeEntity {
    @Id
    Long id;

    Integer tie;

    BigDecimal amount;

    Timestamp stamp;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    UUID token;

    @Column(name = "payload")
    byte[] payload;

    // A @Convert value class, the R-PAG-17 example of a key type no cursor codec carries: ordering by it is MQ2210.
    @Convert(converter = ShapeConverter.class)
    Shape shape;

    /** A small immutable value class, converted to and from its name. */
    public static final class Shape {
        private final String name;

        public Shape(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Shape other && name.equals(other.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    /** Converts {@link Shape} to its name and back. */
    @Converter
    public static class ShapeConverter implements AttributeConverter<Shape, String> {
        @Override
        public String convertToDatabaseColumn(Shape shape) {
            return shape == null ? null : shape.name();
        }

        @Override
        public Shape convertToEntityAttribute(String text) {
            return text == null ? null : new Shape(text);
        }
    }
}
