package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/** Fixture entity over {@code composite_key_items}: a two-column primary key. */
@Entity
@Table(name = "composite_key_items")
@IdClass(CompositeKeyItemEntity.Key.class)
public class CompositeKeyItemEntity {
    @Id
    @Column(name = "tenant_id")
    Integer tenantId;

    @Id
    @Column(name = "item_no")
    Integer itemNo;

    String label;

    BigDecimal amount;

    /** The {@link IdClass}. */
    public static class Key implements Serializable {
        Integer tenantId;
        Integer itemNo;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(tenantId, other.tenantId)
                    && Objects.equals(itemNo, other.itemNo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, itemNo);
        }
    }
}
