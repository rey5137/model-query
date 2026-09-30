package com.rey.modelquery.processor.fixture;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

/** A mapped superclass with a generic id, so an inherited attribute's type depends on the subclass. */
@MappedSuperclass
public abstract class BaseEntity<I> {

    @Id
    protected I id;

    protected Instant createdAt;
}
