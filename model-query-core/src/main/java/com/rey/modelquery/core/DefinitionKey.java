package com.rey.modelquery.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The identity of a query definition, which an executor keys its run-once checks and warn-once logs on (D-201). Each
 * builder change makes a new one but {@code bind}, so the queries one builder builds through {@code bind} alone share
 * it: they differ in their bound values only (R-COL-40). An {@code orderedBy} copy's builder changes its order, so it
 * has a new one. It holds no value and no state (INV-9).
 *
 * <p>A {@code withLock} or {@code withFetch} copy's key is derived from its query's and the lock or plan, one key per
 * pair, so the copies of two bindings of one definition share one too. Static, since a query holds no cache
 * (CC-IMM-01); weak on the query's key, so none built per request stays reachable, and on a plan, which may be built
 * per request. A lock has a few values only, so it is held by value.
 */
final class DefinitionKey {

    private static final Map<DefinitionKey, Derived> DERIVED = Collections.synchronizedMap(new WeakHashMap<>());

    /** A key's derived keys: by lock value, and by plan identity, weakly. */
    private static final class Derived {
        final Map<RowLock, DefinitionKey> byLock = new HashMap<>();
        final Map<FetchPlan<?>, DefinitionKey> byPlan = new WeakHashMap<>();
    }

    /** The key of the copy of this key's query that takes {@code lock}, the same for every equal lock. */
    DefinitionKey withLock(RowLock lock) {
        Derived derived = DERIVED.computeIfAbsent(this, key -> new Derived());
        synchronized (derived) {
            return derived.byLock.computeIfAbsent(lock, l -> new DefinitionKey());
        }
    }

    /** The key of the copy of this key's query that fetches with {@code plan}, the same for the same plan. */
    DefinitionKey withFetch(FetchPlan<?> plan) {
        Derived derived = DERIVED.computeIfAbsent(this, key -> new Derived());
        synchronized (derived) {
            return derived.byPlan.computeIfAbsent(plan, p -> new DefinitionKey());
        }
    }

    @Override
    public String toString() {
        return "definition@" + Integer.toHexString(System.identityHashCode(this));
    }
}
