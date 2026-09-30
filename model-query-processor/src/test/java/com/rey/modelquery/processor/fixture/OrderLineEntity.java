package com.rey.modelquery.processor.fixture;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;

@Entity
public class OrderLineEntity {

    @EmbeddedId
    protected OrderLineId id;

    protected Integer quantity;
}
