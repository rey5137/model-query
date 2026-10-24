package com.rey.modelquery.core;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The {@link ModelQuery#withFetch} copies that passed the checks of {@code build()}, by (query, plan) identity, so a
 * repeated {@code withFetch} returns the same copy, and the keys of those copies, so a constant plan is checked once
 * per definition, whatever its bindings (R-FCH-13, D-201). Static, since a query holds no cache (CC-IMM-01); weak on
 * the query, the plan, the copy and the key, so none built per request stays reachable. The pass outlives a collected
 * copy, so the next one is built without the checks.
 */
final class CheckedFetches {

    private static final Map<ModelQuery<?, ?, ?>, Map<FetchPlan<?>, WeakReference<ModelQuery<?, ?, ?>>>> PASSED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The copy keys that passed the checks, weak as {@link DefinitionKey}'s map is (D-201). */
    private static final Set<DefinitionKey> PASSED_KEYS = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<>()));

    private CheckedFetches() {}

    /** The copy {@code query.withFetch(plan)} passed its checks as, or {@code null} when none is held. */
    @SuppressWarnings("unchecked")
    static <E, K, M> ModelQuery<E, K, M> copy(ModelQuery<E, K, M> query, FetchPlan<M> plan) {
        Map<FetchPlan<?>, WeakReference<ModelQuery<?, ?, ?>>> plans = PASSED.get(query);
        WeakReference<ModelQuery<?, ?, ?>> copy = plans == null ? null : plans.get(plan);
        return copy == null ? null : (ModelQuery<E, K, M>) copy.get();
    }

    /**
     * Whether a copy keyed {@code key} passed its checks, of this query or another binding of it, though the copy may
     * since be collected (D-201).
     */
    static boolean passed(DefinitionKey key) {
        return PASSED_KEYS.contains(key);
    }

    /** Records that {@code query.withFetch(plan)} passed its checks as {@code copy}, keyed {@code key}. */
    static void pass(ModelQuery<?, ?, ?> query, FetchPlan<?> plan, ModelQuery<?, ?, ?> copy, DefinitionKey key) {
        PASSED.computeIfAbsent(query, q -> Collections.synchronizedMap(new WeakHashMap<>()))
                .put(plan, new WeakReference<>(copy));
        PASSED_KEYS.add(key);
    }
}
