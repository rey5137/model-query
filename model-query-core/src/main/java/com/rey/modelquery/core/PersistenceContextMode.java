package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * What a bulk write does to the persistence context after its last statement. A bulk write bypasses managed entities,
 * so either way some of them no longer match the table.
 *
 * @implSpec R-WRT-15, D-62
 */
@Incubating
public enum PersistenceContextMode {

    /**
     * {@code EntityManager#clear()}: detaches every managed entity, not only the root type's, so a later change to any
     * of them is silently not written. The default.
     */
    CLEAR,

    /**
     * Nothing: the root's entities stay managed but stale. Flushing a stale versioned one fails, since its version
     * moved; with {@code keepVersion()}, or on a root with no {@code @Version} attribute, the flush silently writes
     * the stale values back over the bulk write.
     */
    KEEP
}
