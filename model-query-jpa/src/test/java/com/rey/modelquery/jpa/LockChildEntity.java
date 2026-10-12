package com.rey.modelquery.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The target of {@link LockParentEntity}'s to-one association, so a query can build a statement with a join. */
@Entity
@Table(name = "lock_children")
public class LockChildEntity {

    @Id
    Long id;

    @Column(name = "name")
    String name;

    protected LockChildEntity() {}

    LockChildEntity(long id, String name) {
        this.id = id;
        this.name = name;
    }
}
