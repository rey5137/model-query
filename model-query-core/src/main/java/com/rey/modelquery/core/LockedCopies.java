package com.rey.modelquery.core;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The {@link ModelQuery#withLock} copies, by (query, lock), so a constant lock returns the same copy, and the keys of
 * the copies that passed the checks of {@code build()}, so a copy is checked once per definition and lock, whatever
 * its bindings (R-QRY-17, D-201). Static, since a query holds no cache (CC-IMM-01); weak on the query, the copy and
 * the key, so none built per request stays reachable. A lock has a few values only, so the inner map is small.
 */
final class LockedCopies {

    private static final Map<ModelQuery<?, ?, ?>, Map<RowLock, WeakReference<ModelQuery<?, ?, ?>>>> COPIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The copy keys that passed the checks of {@code build()}, weak as {@link DefinitionKey}'s map is (D-201). */
    private static final Set<DefinitionKey> PASSED = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<>()));

    private LockedCopies() {}

    /** The copy {@code query.withLock(lock)} built, or {@code null} when none is held. */
    @SuppressWarnings("unchecked")
    static <E, K, M> ModelQuery<E, K, M> copy(ModelQuery<E, K, M> query, RowLock lock) {
        Map<RowLock, WeakReference<ModelQuery<?, ?, ?>>> locks = COPIES.get(query);
        WeakReference<ModelQuery<?, ?, ?>> copy = locks == null ? null : locks.get(lock);
        return copy == null ? null : (ModelQuery<E, K, M>) copy.get();
    }

    /** Whether a copy keyed {@code key} passed the checks of {@code build()}, of this query or another binding. */
    static boolean passed(DefinitionKey key) {
        return PASSED.contains(key);
    }

    /** Records {@code copy}, keyed {@code key}, as {@code query.withLock(lock)}, once it passed the checks. */
    static void put(ModelQuery<?, ?, ?> query, RowLock lock, ModelQuery<?, ?, ?> copy, DefinitionKey key) {
        COPIES.computeIfAbsent(query, q -> Collections.synchronizedMap(new HashMap<>()))
                .put(lock, new WeakReference<>(copy));
        PASSED.add(key);
    }
}
