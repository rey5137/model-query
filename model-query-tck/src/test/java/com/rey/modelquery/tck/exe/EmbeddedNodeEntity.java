package com.rey.modelquery.tck.exe;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * A read-only view of {@code composite_nodes} keyed by an {@code @EmbeddedId} of {@code (tenant_id, id)}, whose
 * children are a to-many association over the same table, for a semi-join correlated by an embedded id (TCK AC-EXE-22,
 * R-EXE-16). Mapped only by the test that needs it.
 */
@Entity
@Table(name = "composite_nodes")
public class EmbeddedNodeEntity {
    @EmbeddedId
    Key key;

    String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id", insertable = false, updatable = false),
        @JoinColumn(name = "parent_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    EmbeddedNodeEntity parent;

    @OneToMany(mappedBy = "parent")
    List<EmbeddedNodeEntity> children;

    /** The {@link EmbeddedId}: a tenant and a node id. */
    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "tenant_id")
        Integer tenantId;

        Long id;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(tenantId, other.tenantId) && Objects.equals(id, other.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, id);
        }
    }
}
