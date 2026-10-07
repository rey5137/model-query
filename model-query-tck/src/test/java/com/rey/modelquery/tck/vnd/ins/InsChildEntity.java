package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A child of {@link InsParentEntity}: of the {@code parent} it belongs to by a cascading {@code REMOVE}, or of the
 * {@code holder} that holds it with {@code orphanRemoval}.
 */
@Entity
@Table(name = "ins_child")
public class InsChildEntity {
    @Id
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    InsParentEntity parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holder_id")
    InsParentEntity holder;
}
