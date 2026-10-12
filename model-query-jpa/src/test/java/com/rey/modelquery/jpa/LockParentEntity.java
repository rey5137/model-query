package com.rey.modelquery.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A root with a to-one association, so a query over it can build a statement that joins a second table. */
@Entity
@Table(name = "lock_parents")
public class LockParentEntity {

    @Id
    Long id;

    @ManyToOne
    @JoinColumn(name = "child_id")
    LockChildEntity child;

    protected LockParentEntity() {}

    LockParentEntity(long id, LockChildEntity child) {
        this.id = id;
        this.child = child;
    }
}
