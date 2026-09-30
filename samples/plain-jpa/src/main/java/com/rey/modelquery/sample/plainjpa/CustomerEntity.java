package com.rey.modelquery.sample.plainjpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A customer of the shop. */
@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    Long id;
    String name;
    String country;

    protected CustomerEntity() {
    }

    CustomerEntity(Long id, String name, String country) {
        this.id = id;
        this.name = name;
        this.country = country;
    }
}
