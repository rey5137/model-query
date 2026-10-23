package com.rey.modelquery.spike.rfc0021;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * RFC 0021's closure-table shape (§Motivation): hierarchies, their nodes, the closure edges, profiles and profile
 * types, plus two composite-id entities (one {@code @IdClass}, one {@code @EmbeddedId}) as entity-join targets (A.3).
 * Public fields, field access; the spike reads them through Criteria paths only.
 */
public final class Rfc0021Entities {

    private Rfc0021Entities() {}

    public static final Class<?>[] ALL = {
        Hierarchy.class, ProfileType.class, Profile.class, HierarchyNode.class, HierarchyEdge.class,
        MemberIc.class, MemberEmb.class
    };

    @Entity
    @Table(name = "hierarchy")
    public static class Hierarchy {
        @Id public Long id;
        public String name;
    }

    @Entity
    @Table(name = "profile_type")
    public static class ProfileType {
        @Id public Long id;
        @Column(nullable = false) public String name;
    }

    @Entity
    @Table(name = "profile")
    public static class Profile {
        @Id public Long id;
        @Column(name = "first_name") public String firstName;
        @Column(name = "last_name") public String lastName;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "type_id")
        public ProfileType type;
        @Column(name = "is_deleted", nullable = false) public boolean deleted;
    }

    /** {@code node_id} is a basic column, with a read-only association over it (A.1, "outer plain column"). */
    @Entity
    @Table(name = "hierarchy_node", uniqueConstraints = @UniqueConstraint(columnNames = {"hierarchy_id", "node_id"}))
    public static class HierarchyNode {
        @Id public Long id;
        @Column(name = "hierarchy_id", nullable = false) public Long hierarchyId;
        @Column(name = "node_id", nullable = false) public Long nodeId;
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "node_id", insertable = false, updatable = false)
        public Profile node;
        @Column(name = "is_deleted", nullable = false) public boolean deleted;
    }

    /** The unique index is the motivating case's assumption: one edge per (hierarchy, end vertex, hops). */
    @Entity
    @Table(name = "hierarchy_edge",
            uniqueConstraints = @UniqueConstraint(columnNames = {"hierarchy_id", "end_vertex", "hops"}))
    public static class HierarchyEdge {
        @Id public Long id;
        @Column(name = "hierarchy_id", nullable = false) public Long hierarchyId;
        @Column(name = "start_vertex", nullable = false) public Long startVertex;
        @Column(name = "end_vertex", nullable = false) public Long endVertex;
        @Column(nullable = false) public Integer hops;
        @Column(name = "is_deleted", nullable = false) public boolean deleted;
        @Column(name = "created_at", nullable = false) public LocalDateTime createdAt;
    }

    public static class MemberKey implements Serializable {
        public Long hierarchyId;
        public Long profileId;

        @Override
        public boolean equals(Object o) {
            return o instanceof MemberKey k && Objects.equals(hierarchyId, k.hierarchyId)
                    && Objects.equals(profileId, k.profileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hierarchyId, profileId);
        }
    }

    @Entity
    @Table(name = "member_ic")
    @IdClass(MemberKey.class)
    public static class MemberIc {
        @Id @Column(name = "hierarchy_id") public Long hierarchyId;
        @Id @Column(name = "profile_id") public Long profileId;
        public String role;
    }

    @Embeddable
    public static class MemberEmbKey implements Serializable {
        @Column(name = "hierarchy_id") public Long hierarchyId;
        @Column(name = "profile_id") public Long profileId;

        @Override
        public boolean equals(Object o) {
            return o instanceof MemberEmbKey k && Objects.equals(hierarchyId, k.hierarchyId)
                    && Objects.equals(profileId, k.profileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hierarchyId, profileId);
        }
    }

    @Entity
    @Table(name = "member_emb")
    public static class MemberEmb {
        @EmbeddedId public MemberEmbKey key;
        public String role;
    }
}
