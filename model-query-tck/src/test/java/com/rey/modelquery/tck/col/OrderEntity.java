package com.rey.modelquery.tck.col;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;

/** Fixture entity over {@code orders}. */
@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id
    Long id;

    String status;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    CustomerEntity customer;

    @OneToMany(mappedBy = "order")
    List<OrderItemEntity> items;
}
