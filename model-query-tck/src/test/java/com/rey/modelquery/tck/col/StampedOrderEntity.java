package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.sql.Timestamp;

/**
 * Fixture entity over {@code orders} whose {@code placed_at} is a {@link Timestamp}, for the built-in converters, and a
 * second entity on the table of {@link OrderEntity}, which a sub-query through its customer reads (R-WRT-11).
 */
@Entity
@Table(name = "orders")
public class StampedOrderEntity {
    @Id
    Long id;

    String status;

    @Column(name = "placed_at")
    Timestamp placedAt;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    CustomerEntity customer;
}
