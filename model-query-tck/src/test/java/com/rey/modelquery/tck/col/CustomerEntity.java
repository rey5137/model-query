package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;

/** Fixture entity over {@code customers}, mapped only as far as the join tests need. */
@Entity
@Table(name = "customers")
public class CustomerEntity {
    @Id
    Long id;

    String name;
    String country;

    boolean vip;

    @Column(name = "created_at")
    LocalDateTime createdAt;

    @OneToMany(mappedBy = "customer")
    List<OrderEntity> orders;
}
