package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/** A pooled-sequence root (increment 20), for the D-116 generator probes. */
@Entity
@Table(name = "ins_pooled")
public class InsPooledEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ins_pooled_gen")
    @SequenceGenerator(name = "ins_pooled_gen", sequenceName = "ins_pooled_seq", allocationSize = 20)
    Long id;

    @Column(unique = true)
    String code;

    String name;
}
