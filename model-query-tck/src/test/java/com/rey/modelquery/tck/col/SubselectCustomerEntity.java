package com.rey.modelquery.tck.col;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

/**
 * Fixture entity over a Hibernate {@code @Subselect}: a derived table that is a read-only root like any view
 * (TCK AC-COL-29). {@code @Synchronize} names the table the sub-select reads.
 */
@Entity
@Immutable
@Subselect("select id, name, country from customers")
@Synchronize("customers")
public class SubselectCustomerEntity {
    @Id
    Long id;

    String name;

    String country;
}
