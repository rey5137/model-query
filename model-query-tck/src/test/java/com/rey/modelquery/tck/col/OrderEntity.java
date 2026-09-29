package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Fixture entity over {@code orders}. */
@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id
    Long id;

    String status;

    BigDecimal total;

    @Column(name = "placed_at")
    LocalDateTime placedAt;

    // The same column read through a converter (R-COL-08).
    @Convert(converter = OrderStatus.TextConverter.class)
    @Column(name = "status", insertable = false, updatable = false)
    OrderStatus statusCode;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    CustomerEntity customer;

    @OneToMany(mappedBy = "order")
    List<OrderItemEntity> items;
}
