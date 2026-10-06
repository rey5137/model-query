package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Embeddable;

/** A property-access embeddable, which {@code persist} sets through its setters (R-WRT-39). */
@Embeddable
@Access(AccessType.PROPERTY)
public class InsPersistAddress {
    private String city;
    private String zip;

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getZip() {
        return zip;
    }

    public void setZip(String zip) {
        this.zip = zip;
    }
}
