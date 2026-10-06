package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** An assigned-id root with a unique code and a version: the D-116 generator and conflict probes' target. */
@Entity
@Table(name = "ins_assigned")
public class InsAssignedEntity {
    @Id
    Long id;

    @Column(unique = true)
    String code;

    String name;

    @Version
    Integer version;
}
