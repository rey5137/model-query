package com.rey.modelquery.processor.fixture;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Field access, inherited from where {@link BaseEntity} maps its id. */
@Entity
public class OrderEntity extends BaseEntity<Long> {

    static final String NOT_AN_ATTRIBUTE = "static";

    protected String status;

    protected BigDecimal total;

    protected int quantity;

    protected boolean paid;

    @Embedded
    protected Address address;

    @ManyToOne
    protected CustomerEntity customer;

    @OneToOne
    protected CustomerEntity payer;

    @OneToMany
    protected List<ItemEntity> items;

    @ManyToMany
    protected Set<ItemEntity> related;

    @Transient
    protected String scratch;

    /** A getter under field access: not an attribute. */
    public String getLabel() {
        return status;
    }
}
