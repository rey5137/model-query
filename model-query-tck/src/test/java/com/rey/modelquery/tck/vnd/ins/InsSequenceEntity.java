package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/** A sequence root with increment 1 (no optimizer), for the D-116 generator probes. */
@Entity
@Table(name = "ins_sequence")
public class InsSequenceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ins_sequence_gen")
    @SequenceGenerator(name = "ins_sequence_gen", sequenceName = "ins_sequence_seq", allocationSize = 1)
    Long id;

    @Column(unique = true)
    String code;

    String name;
}
