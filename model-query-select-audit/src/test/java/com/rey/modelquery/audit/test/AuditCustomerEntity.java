package com.rey.modelquery.audit.test;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_customer")
public class AuditCustomerEntity {
    @Id
    private Long id;
    private String name;
    @ManyToOne
    private AuditCustomerEntity colleague;

    public AuditCustomerEntity() {}

    public AuditCustomerEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public void setColleague(AuditCustomerEntity colleague) {
        this.colleague = colleague;
    }

    public AuditCustomerEntity getColleague() {
        return colleague;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
