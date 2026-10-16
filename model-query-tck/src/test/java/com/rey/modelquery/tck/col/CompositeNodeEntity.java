package com.rey.modelquery.tck.col;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * Fixture entity over {@code composite_nodes}: a tree whose node key is two columns, {@code (tenant_id, id)}, so a
 * recursive source over it has a composite node key (TCK AC-SRC-21). The parent link is the single {@code parent_id}
 * column, as {@code folder_nodes}' is (R-PROC-37; RFC 0014 §7).
 */
@Entity
@Table(name = "composite_nodes")
@IdClass(CompositeNodeEntity.Key.class)
public class CompositeNodeEntity {
    @Id
    @Column(name = "tenant_id")
    Integer tenantId;

    @Id
    Long id;

    @Column(name = "parent_id")
    Long parentId;

    String name;

    /** The {@link IdClass}. */
    public static class Key implements Serializable {
        Integer tenantId;
        Long id;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(tenantId, other.tenantId)
                    && Objects.equals(id, other.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, id);
        }
    }
}
