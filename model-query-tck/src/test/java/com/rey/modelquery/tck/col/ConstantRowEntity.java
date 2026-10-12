package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code constant_rows}: one attribute per mapping a column-typed constant renders through — a
 * STRING enum, an ORDINAL enum and a Boolean over a Y/N {@link YesNoConverter} — plus a plain label (TCK AC-COL-32).
 * Every attribute is nullable, so a {@code notIn} over them has a NULL row to match.
 */
@Entity
@Table(name = "constant_rows")
public class ConstantRowEntity {
    @Id
    Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    ConstantStatus status;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "rank_no")
    ConstantRank rank;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "active")
    Boolean active;

    @Column(name = "label")
    String label;
}
