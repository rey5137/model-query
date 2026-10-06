package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** A root whose id is its {@code @MapsId} parent's, for the D-116 {@code @MapsId} probes. */
@Entity
@Table(name = "ins_maps_id")
public class InsMapsIdEntity {
    @Id
    Long id;

    @MapsId
    @OneToOne
    @JoinColumn(name = "id")
    InsAssignedEntity parent;

    String note;
}
