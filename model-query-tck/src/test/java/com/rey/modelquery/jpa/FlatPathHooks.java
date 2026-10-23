package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import jakarta.persistence.criteria.CriteriaBuilder;

/**
 * Test hook providing package-private access to {@link DefaultModelQueryExecutor}'s execution path logic (AC-COL-53).
 */
public final class FlatPathHooks {

    private FlatPathHooks() {}

    /** Builds {@code q} against {@code cb} and tests eligibility for the flat-row path. */
    public static boolean isFlatEligible(ModelQuery<?, ?, ?> q, CriteriaBuilder cb) {
        BuiltQuery<?> built = q.buildQuery(cb, Phase.MODEL, RenderOptions.portable());
        return DefaultModelQueryExecutor.isFlatEligible(q, built);
    }
}
