package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** An {@code IDENTITY} root with a unique code and a version, for the D-116 generator probes. */
@Entity
@Table(name = "ins_identity")
public class InsIdentityEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(unique = true)
    String code;

    String name;

    @Version
    Integer version;
}
