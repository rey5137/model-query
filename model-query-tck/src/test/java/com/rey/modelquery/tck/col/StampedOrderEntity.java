package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Timestamp;

/** Fixture entity over {@code orders} whose {@code placed_at} is a {@link Timestamp}, for the built-in converters. */
@Entity
@Table(name = "orders")
public class StampedOrderEntity {
    @Id
    Long id;

    String status;

    @Column(name = "placed_at")
    Timestamp placedAt;
}
