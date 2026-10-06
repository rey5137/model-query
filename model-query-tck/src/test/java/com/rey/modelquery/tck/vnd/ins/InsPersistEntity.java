package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * The {@code persist} root (R-WRT-39): an {@code IDENTITY} id, a string enum, a JPA-converted flag, a property-access
 * embeddable and a {@code CascadeType.ALL} to-one; {@code region} has a database default, {@code origin} a
 * constructor value, and {@code @PrePersist} fills {@code audit}.
 */
@Entity
@Table(name = "ins_persist")
public class InsPersistEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String code;

    @Enumerated(EnumType.STRING)
    Status status;

    @Convert(converter = YesNo.class)
    @Column(length = 1)
    Boolean flagged;

    Long amount;

    @Column(columnDefinition = "varchar(20) default 'DFLT'")
    String region;

    String origin = "app";

    String audit;

    @Embedded
    InsPersistAddress address;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "source_id")
    InsSourceEntity source;

    @PrePersist
    void stamp() {
        audit = "pre:" + code;
    }

    /** The status, written by name. */
    public enum Status { NEW, PAID }

    /** {@code true} as {@code Y}, {@code false} as {@code N}. */
    public static class YesNo implements AttributeConverter<Boolean, String> {
        @Override
        public String convertToDatabaseColumn(Boolean attribute) {
            return attribute == null ? null : attribute ? "Y" : "N";
        }

        @Override
        public Boolean convertToEntityAttribute(String column) {
            return column == null ? null : column.equals("Y");
        }
    }
}
