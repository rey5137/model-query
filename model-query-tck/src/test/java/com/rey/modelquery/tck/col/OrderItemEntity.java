package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Fixture entity over {@code order_items}. */
@Entity
@Table(name = "order_items")
public class OrderItemEntity {
    @Id
    Long id;

    @Column(name = "product_code")
    String productCode;

    int quantity;

    @Column(name = "unit_price")
    BigDecimal unitPrice;

    @ManyToOne
    @JoinColumn(name = "order_id")
    OrderEntity order;
}
