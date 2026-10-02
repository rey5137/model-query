package com.rey.modelquery.core;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The (query, plan) pairs whose {@link ModelQuery#withFetch} copy passed the checks of {@code build()}, by identity,
 * so a constant plan is checked once per query (R-FCH-13). Static, since a query holds no cache (CC-IMM-01); weak on
 * both, so neither a query nor a plan built per request stays reachable.
 */
final class CheckedFetches {

    private static final Map<ModelQuery<?, ?, ?>, Set<FetchPlan<?>>> PASSED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CheckedFetches() {}

    /** Whether {@code query.withFetch(plan)} passed its checks before. */
    static boolean passed(ModelQuery<?, ?, ?> query, FetchPlan<?> plan) {
        Set<FetchPlan<?>> plans = PASSED.get(query);
        return plans != null && plans.contains(plan);
    }

    /** Records that {@code query.withFetch(plan)} passed its checks. */
    static void pass(ModelQuery<?, ?, ?> query, FetchPlan<?> plan) {
        PASSED.computeIfAbsent(query, q -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>())))
                .add(plan);
    }
}
