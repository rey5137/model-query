package com.rey.modelquery.tck.arch.fixture.test;

import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.FetchPlan;

/**
 * Violates: test reads a fetch plan's child loads and nothing else engine-facing (D-104). {@code childLoads()} and
 * {@code maxPerParent()} are allowed; {@code joinPlans()} and {@code ChildLoad.build} are not.
 */
public class BadTestChildLoadSeam {
    void call(FetchPlan<Object> plan) {
        for (ChildLoad<Object, ?> load : plan.childLoads()) {
            load.maxPerParent();
            load.build(null, null);
        }
        plan.joinPlans();
    }
}
