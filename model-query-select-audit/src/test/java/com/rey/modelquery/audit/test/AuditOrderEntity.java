package com.rey.modelquery.audit.test;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_order")
public class AuditOrderEntity {
    @Id
    private Long id;
    private Long total;
    private String notes;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    private AuditCustomerEntity customer;

    public AuditOrderEntity() {}

    public AuditOrderEntity(Long id, Long total, String notes, AuditCustomerEntity customer) {
        this.id = id;
        this.total = total;
        this.notes = notes;
        this.customer = customer;
    }

    public Long getId() {
        return id;
    }

    public Long getTotal() {
        return total;
    }

    public String getNotes() {
        return notes;
    }

    public AuditCustomerEntity getCustomer() {
        return customer;
    }
}
