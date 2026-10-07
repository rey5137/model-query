package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * The audit columns write assignments set (R-WRT-49), on a mapped superclass, so an assignment naming it covers each
 * root extending it.
 */
@MappedSuperclass
public abstract class InsAuditedBase {

    @Column(name = "created_by")
    String createdBy;

    @Column(name = "updated_by")
    String updatedBy;
}
