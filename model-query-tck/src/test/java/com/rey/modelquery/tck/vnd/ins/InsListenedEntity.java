package com.rey.modelquery.tck.vnd.ins;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The root an entity-mode write loads and writes (R-WRT-42): an assigned id, a {@code @Version}, a lazy to-one, and
 * {@code @PreUpdate}/{@code @PostUpdate} callbacks that record what they saw.
 */
@Entity
@Table(name = "ins_listened")
public class InsListenedEntity {

    /** Each callback in the order it ran: {@code pre:<id>:<status>} and {@code post:<id>:<version>}. */
    public static final List<String> CALLBACKS = Collections.synchronizedList(new ArrayList<>());

    /** Runs in {@code @PreUpdate}, after it is recorded; a test sets it to act between the load and the write. */
    public static Consumer<InsListenedEntity> beforeUpdate = entity -> {};

    @Id
    Long id;

    String status;

    Long amount;

    String note;

    @Version
    Integer version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    InsSourceEntity source;

    public Long id() {
        return id;
    }

    public String status() {
        return status;
    }

    public Integer version() {
        return version;
    }

    /** Forgets the recorded callbacks and the {@link #beforeUpdate} action. */
    public static void reset() {
        CALLBACKS.clear();
        beforeUpdate = entity -> {};
    }

    @PreUpdate
    void preUpdate() {
        CALLBACKS.add("pre:" + id + ":" + status);
        beforeUpdate.accept(this);
    }

    @PostUpdate
    void postUpdate() {
        CALLBACKS.add("post:" + id + ":" + version);
    }
}
