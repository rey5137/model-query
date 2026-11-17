package com.rey.modelquery.audit.test;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_line")
public class AuditLineEntity {
    @Id
    private Long id;
    private Long orderId;
    private String sku;

    public AuditLineEntity() {}

    public AuditLineEntity(Long id, Long orderId, String sku) {
        this.id = id;
        this.orderId = orderId;
        this.sku = sku;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getSku() {
        return sku;
    }
}
