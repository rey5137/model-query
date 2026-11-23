package com.rey.modelquery.audit.test;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @ElementCollection
    @CollectionTable(name = "audit_order_tag")
    private List<String> tags = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "audit_order_spot")
    private List<AuditSpot> spots = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "audit_order_label")
    private Map<String, String> labels = new HashMap<>();

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

    public List<String> getTags() {
        return tags;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public List<AuditSpot> getSpots() {
        return spots;
    }
}
