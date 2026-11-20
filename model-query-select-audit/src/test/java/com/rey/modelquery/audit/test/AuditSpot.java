package com.rey.modelquery.audit.test;

import jakarta.persistence.Embeddable;

@Embeddable
public class AuditSpot {
    private String city;

    public AuditSpot() {}

    public AuditSpot(String city) {
        this.city = city;
    }
}
