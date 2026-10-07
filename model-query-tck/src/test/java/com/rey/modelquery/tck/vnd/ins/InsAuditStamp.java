package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** An embeddable on {@link InsAuditedEntity}, whose attribute a write assignment names through it (R-WRT-49). */
@Embeddable
public class InsAuditStamp {

    @Column(name = "touched_by")
    String touchedBy;
}
