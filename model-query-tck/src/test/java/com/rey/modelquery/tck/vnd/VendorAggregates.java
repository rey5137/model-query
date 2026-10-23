package com.rey.modelquery.tck.vnd;

import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckVendor;

/**
 * The vendor fact {@code Agg.firstBy} needs (spec vendor/41, vendor/40 R-VND-29, api/13 AC-AGG-19): MySQL has no
 * array aggregate, so {@code VendorProfile#aggregates(AggregateFeature.FIRST_BY)} answers {@code false} there and the
 * query is {@code MQ4015} before any statement, while PostgreSQL and H2 render it. It lives here rather than in
 * {@code tck.agg} because AC-QA-02 confines knowing the vendor to the harness and {@code tck.vnd} (R-QA-03), as
 * {@link VendorSources} does for sources.
 */
public final class VendorAggregates {

    private VendorAggregates() {}

    /** Whether this database has an ordered array aggregate: every Tier-1 vendor but MySQL. */
    public static boolean servesFirstBy(TckDatabase db) {
        return db.vendor() != TckVendor.MYSQL;
    }
}
