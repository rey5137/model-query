package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Fixture entity over {@code composite_key_copies}, which the seed leaves empty: the target of a chunked insert-select
 * over {@link CompositeKeyItemEntity}'s two-column id, with the same assigned id (TCK AC-WRT-21, AC-WRT-26).
 */
@Entity
@Table(name = "composite_key_copies")
@IdClass(CompositeKeyItemEntity.Key.class)
public class CompositeKeyCopyEntity {
    @Id
    @Column(name = "tenant_id")
    Integer tenantId;

    @Id
    @Column(name = "item_no")
    Integer itemNo;

    String label;
}
