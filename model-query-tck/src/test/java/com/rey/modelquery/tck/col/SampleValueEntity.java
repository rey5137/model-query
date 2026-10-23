package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Fixture entity over {@code sample_values}: each integer width and half-way decimals (TCK AC-AGG-23 to 25). */
@Entity
@Table(name = "sample_values")
public class SampleValueEntity {
    @Id
    Long id;

    @Column(name = "small_value")
    short smallValue;

    @Column(name = "int_value")
    int intValue;

    @Column(name = "big_value")
    long bigValue;

    @Column(name = "dec_value")
    BigDecimal decValue;
}
