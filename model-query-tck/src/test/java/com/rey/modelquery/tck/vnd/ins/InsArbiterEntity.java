package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * An assigned-id root whose {@code sku} is unique only while the row is open: the conflict arbiter's probe
 * (R-WRT-52). The partial unique index on {@code (sku) where status <> 'CLOSED'} is no JPA mapping, so a test creates
 * it itself on the vendor that has one. {@code code} carries a named unique constraint, the target an
 * {@code onConstraint} names.
 */
@Entity
@Table(name = "ins_arbiter", uniqueConstraints = @UniqueConstraint(name = "ins_arbiter_code_key",
        columnNames = "code"))
public class InsArbiterEntity {
    @Id
    Long id;

    String code;

    String sku;

    String status;

    String name;
}
