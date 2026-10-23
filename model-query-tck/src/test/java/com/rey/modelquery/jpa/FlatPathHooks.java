package com.rey.modelquery.jpa;

import com.rey.modelquery.core.ModelQuery;

/**
 * Test hook providing package-private access to {@link DefaultModelQueryExecutor}'s execution path logic (AC-COL-53).
 */
public final class FlatPathHooks {

    private FlatPathHooks() {}

    /** Tests {@code q}'s eligibility for the flat-row path. */
    public static boolean isFlatEligible(ModelQuery<?, ?, ?> q) {
        return DefaultModelQueryExecutor.isFlatEligible(q);
    }
}
