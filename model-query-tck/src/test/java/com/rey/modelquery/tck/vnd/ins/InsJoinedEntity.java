package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

/** The root of a {@code JOINED} hierarchy, which no bulk insert writes (R-WRT-26, D-116). */
@Entity
@Table(name = "ins_joined")
@Inheritance(strategy = InheritanceType.JOINED)
public class InsJoinedEntity {
    @Id
    Long id;
    String name;
}
