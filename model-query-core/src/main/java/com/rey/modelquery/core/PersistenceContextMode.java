package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * What a bulk write does to the persistence context after its last statement. A bulk write bypasses managed entities,
 * so either way some of them no longer match the table. An update or delete {@code throughEntities()} writes through
 * the context instead, and applies the mode after each chunk's flush.
 *
 * @implSpec R-WRT-15, R-WRT-45, D-62
 */
@Incubating
public enum PersistenceContextMode {

    /**
     * {@code EntityManager#clear()}: detaches every managed entity, not only the root type's, so a later change to any
     * of them is silently not written. The default. An entity-mode write clears after each chunk, so it holds at most
     * one chunk of entities.
     */
    CLEAR,

    /**
     * Nothing: the root's entities stay managed but stale. Flushing a stale versioned one fails, since its version
     * moved; with {@code keepVersion()}, or on a root with no {@code @Version} attribute, the flush silently writes
     * the stale values back over the bulk write. An entity-mode update leaves every entity it loaded managed and
     * current, since it wrote them through the context, so the context then grows with every matched row. An
     * entity-mode delete leaves no removed entity managed, since the flush detaches it; only what a cascade or a
     * listener loaded alongside stays.
     */
    KEEP
}
