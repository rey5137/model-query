package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Fixture entity over {@code first_by_rows}: ties and NULLs in an order key, and a converted value (TCK AC-AGG-18). */
@Entity
@Table(name = "first_by_rows")
public class FirstByRowEntity {
    @Id
    Long id;

    @Column(name = "grp")
    Integer grp;

    @Column(name = "k")
    Integer key;

    @Column(name = "v")
    String value;

    /** The status stored as its first letter, so a read that skips the converter fails (AC-AGG-18). */
    @Convert(converter = StatusCodeConverter.class)
    @Column(name = "state")
    ConstantStatus state;
}
