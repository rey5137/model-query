package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The entity that overrides the getter of {@link InsPropertyBase#getLabel}, whose setter is above it (R-WRT-39). */
@Entity
@Table(name = "ins_property_child")
public class InsPropertyChildEntity extends InsPropertyBase {
    private Long id;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public String getLabel() {
        return super.getLabel();
    }
}
