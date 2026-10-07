package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The root write assignments write (R-WRT-49): the audit columns of {@link InsAuditedBase}, an embeddable, an assigned
 * id, a {@code @Version}, a lazy to-one and a collection, which no assignment may name, and {@code @PrePersist} and
 * {@code @PreUpdate} callbacks that set {@code callbackBy} and record the row.
 */
@Entity
@Table(name = "ins_audited")
public class InsAuditedEntity extends InsAuditedBase {

    /** Each {@code @PreUpdate} in the order it ran: {@code preUpdate:<id>}. */
    public static final List<String> CALLBACKS = Collections.synchronizedList(new ArrayList<>());

    @Id
    Long id;

    String name;

    @Embedded
    InsAuditStamp stamp;

    @Column(name = "callback_by")
    String callbackBy;

    @Version
    Integer version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    InsSourceEntity source;

    @ElementCollection
    @CollectionTable(name = "ins_audited_tag", joinColumns = @JoinColumn(name = "audited_id"))
    @Column(name = "tag")
    List<String> tags = new ArrayList<>();

    @PrePersist
    void prePersist() {
        callbackBy = "prePersist";
    }

    @PreUpdate
    void preUpdate() {
        CALLBACKS.add("preUpdate:" + id);
        callbackBy = "preUpdate";
    }
}
