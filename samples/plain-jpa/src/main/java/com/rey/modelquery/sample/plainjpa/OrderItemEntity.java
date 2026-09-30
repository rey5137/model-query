package com.rey.modelquery.sample.plainjpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One line of an order. */
@Entity
@Table(name = "order_items")
public class OrderItemEntity {

    @Id
    Long id;
    @ManyToOne
    @JoinColumn(name = "order_id")
    OrderEntity order;
    String sku;
    Integer quantity;

    protected OrderItemEntity() {
    }

    OrderItemEntity(Long id, OrderEntity order, String sku, Integer quantity) {
        this.id = id;
        this.order = order;
        this.sku = sku;
        this.quantity = quantity;
    }
}
