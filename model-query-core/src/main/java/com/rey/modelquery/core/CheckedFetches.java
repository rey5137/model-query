package com.rey.modelquery.core;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The {@link ModelQuery#withFetch} copies that passed the checks of {@code build()}, by (query, plan) identity, so a
 * constant plan is checked once per query (R-FCH-13) and a repeated {@code withFetch} returns the same copy, which
 * keeps the executor's first-run check cache (keyed on the definition) warm. Static, since a query holds no cache
 * (CC-IMM-01); weak on the query, the plan and the copy, so none built per request stays reachable. The pass
 * outlives a collected copy, so the next one is built without the checks.
 */
final class CheckedFetches {

    private static final Map<ModelQuery<?, ?, ?>, Map<FetchPlan<?>, WeakReference<ModelQuery<?, ?, ?>>>> PASSED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CheckedFetches() {}

    /** Whether {@code query.withFetch(plan)} passed its checks before, though its copy may since be collected. */
    static boolean passed(ModelQuery<?, ?, ?> query, FetchPlan<?> plan) {
        Map<FetchPlan<?>, WeakReference<ModelQuery<?, ?, ?>>> plans = PASSED.get(query);
        return plans != null && plans.containsKey(plan);
    }

    /** The copy {@code query.withFetch(plan)} passed its checks as, or {@code null} when none is held. */
    @SuppressWarnings("unchecked")
    static <E, K, M> ModelQuery<E, K, M> copy(ModelQuery<E, K, M> query, FetchPlan<M> plan) {
        Map<FetchPlan<?>, WeakReference<ModelQuery<?, ?, ?>>> plans = PASSED.get(query);
        WeakReference<ModelQuery<?, ?, ?>> copy = plans == null ? null : plans.get(plan);
        return copy == null ? null : (ModelQuery<E, K, M>) copy.get();
    }

    /** Records that {@code query.withFetch(plan)} passed its checks as {@code copy}. */
    static void pass(ModelQuery<?, ?, ?> query, FetchPlan<?> plan, ModelQuery<?, ?, ?> copy) {
        PASSED.computeIfAbsent(query, q -> Collections.synchronizedMap(new WeakHashMap<>()))
                .put(plan, new WeakReference<>(copy));
    }
}
