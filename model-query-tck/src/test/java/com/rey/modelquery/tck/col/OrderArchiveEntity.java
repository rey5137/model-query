package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Fixture entity over {@code order_archive}, which the seed leaves empty: the target of the insert-select tests, with
 * an assigned id, a to-one written by id and a version the provider seeds (TCK AC-WRT-21).
 */
@Entity
@Table(name = "order_archive")
public class OrderArchiveEntity {
    @Id
    Long id;

    @Column(name = "order_id")
    Long orderId;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    CustomerEntity customer;

    String status;

    @Column(name = "customer_name")
    String customerName;

    @Column(name = "product_code")
    String productCode;

    @Column(name = "archived_by")
    String archivedBy;

    @Version
    Integer version;
}
