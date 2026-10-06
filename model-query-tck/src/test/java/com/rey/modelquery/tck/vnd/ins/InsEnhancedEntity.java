package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The D-116 {@code persist} probe's root. {@link EnhancingClassLoader} loads a bytecode-enhanced copy of it; this
 * class itself is never mapped. {@code status} shows what the no-arg constructor leaves in an unnamed attribute.
 */
@Entity
@Table(name = "ins_enhanced")
public class InsEnhancedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String code;

    @Basic(fetch = FetchType.LAZY)
    String note;

    String status = "NEW";

    String name;

    @Version
    Integer version;
}
