package com.rey.modelquery.sample.plainjpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** An order, placed by a customer or, for a walk-in sale, by nobody. */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    Long id;
    String status;
    BigDecimal total;
    @ManyToOne
    @JoinColumn(name = "customer_id")
    CustomerEntity customer;
    @OneToMany(mappedBy = "order")
    List<OrderItemEntity> items = new ArrayList<>();

    protected OrderEntity() {
    }

    OrderEntity(Long id, String status, String total, CustomerEntity customer) {
        this.id = id;
        this.status = status;
        this.total = new BigDecimal(total);
        this.customer = customer;
    }
}
