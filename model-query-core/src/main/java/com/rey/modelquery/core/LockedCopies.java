package com.rey.modelquery.core;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The {@link ModelQuery#withLock} copies, by (query, lock), so a constant lock returns the same copy: it is built and
 * checked once, and the executor's per-definition first-run check and ignored-order warning stay once (R-QRY-17).
 * Static, since a query holds no cache (CC-IMM-01); weak on the query and on the copy, so none built per request
 * stays reachable. A lock has a few values only, so the inner map is small.
 */
final class LockedCopies {

    private static final Map<ModelQuery<?, ?, ?>, Map<RowLock, WeakReference<ModelQuery<?, ?, ?>>>> COPIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private LockedCopies() {}

    /** The copy {@code query.withLock(lock)} built, or {@code null} when none is held. */
    @SuppressWarnings("unchecked")
    static <E, K, M> ModelQuery<E, K, M> copy(ModelQuery<E, K, M> query, RowLock lock) {
        Map<RowLock, WeakReference<ModelQuery<?, ?, ?>>> locks = COPIES.get(query);
        WeakReference<ModelQuery<?, ?, ?>> copy = locks == null ? null : locks.get(lock);
        return copy == null ? null : (ModelQuery<E, K, M>) copy.get();
    }

    /** Records {@code copy} as {@code query.withLock(lock)}, once it passed the checks of {@code build()}. */
    static void put(ModelQuery<?, ?, ?> query, RowLock lock, ModelQuery<?, ?, ?> copy) {
        COPIES.computeIfAbsent(query, q -> Collections.synchronizedMap(new HashMap<>()))
                .put(lock, new WeakReference<>(copy));
    }
}
