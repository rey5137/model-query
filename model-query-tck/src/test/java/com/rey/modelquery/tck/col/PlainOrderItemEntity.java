package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code order_items} with no association to {@link OrderEntity}: its order is a plain
 * {@code order_id} column, so a sub-select over it has no path back to the outer root (spec api/12 R-FLT-17,
 * AC-FLT-14).
 */
@Entity
@Table(name = "order_items")
public class PlainOrderItemEntity {
    @Id
    Long id;

    @Column(name = "order_id")
    Long orderId;

    @Column(name = "product_code")
    String productCode;

    int quantity;
}
