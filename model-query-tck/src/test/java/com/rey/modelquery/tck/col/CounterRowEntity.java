package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Fixture entity over {@code counter_rows}: the counter a conflict update increments (TCK AC-WRT-44). */
@Entity
@Table(name = "counter_rows")
public class CounterRowEntity {
    @Id
    Long id;

    @Column(name = "next_value")
    long nextValue;
}
