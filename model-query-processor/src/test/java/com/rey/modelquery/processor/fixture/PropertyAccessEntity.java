package com.rey.modelquery.processor.fixture;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

/** Explicit property access: the attributes are the getters' properties, whatever the fields are called. */
@Entity
@Access(AccessType.PROPERTY)
public class PropertyAccessEntity {

    private Long idValue;

    private String labelValue;

    private boolean activeValue;

    /** Mapped by its field although the class uses property access. */
    @Access(AccessType.FIELD)
    protected Integer rank;

    @Id
    public Long getId() {
        return idValue;
    }

    public void setId(Long id) {
        this.idValue = id;
    }

    public String getLabel() {
        return labelValue;
    }

    public void setLabel(String label) {
        this.labelValue = label;
    }

    public boolean isActive() {
        return activeValue;
    }

    public void setActive(boolean active) {
        this.activeValue = active;
    }

    @Transient
    public String getDerived() {
        return labelValue;
    }
}
