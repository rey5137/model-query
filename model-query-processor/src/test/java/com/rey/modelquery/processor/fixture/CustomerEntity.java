package com.rey.modelquery.processor.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class CustomerEntity {

    @Id
    protected Long id;

    protected String name;
}
