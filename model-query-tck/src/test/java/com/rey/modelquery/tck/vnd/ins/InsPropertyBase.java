package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.MappedSuperclass;

/** A mapped superclass, read through property access, that declares a property's getter and setter. */
@MappedSuperclass
public abstract class InsPropertyBase {
    protected String label;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
