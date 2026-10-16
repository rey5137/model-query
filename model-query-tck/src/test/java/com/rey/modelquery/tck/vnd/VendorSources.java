package com.rey.modelquery.tck.vnd;

import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckVendor;

/**
 * The vendor fact a recursive source over a composite node key needs (spec vendor/41 §2, vendor/40 R-VND-21,
 * api/17 AC-SRC-21): H2's dialect rejects the multi-column {@code cycle} clause Hibernate renders for a key of more
 * than one column, so {@code VendorProfile#sources(SourceFeature.RECURSIVE_COMPOSITE_KEY)} answers {@code false}
 * there and the query is {@code MQ4015} before any statement, while PostgreSQL and MySQL render it and read the
 * tree. It lives here rather than in {@code tck.src} because AC-QA-02 confines knowing the vendor to the harness and
 * {@code tck.vnd} (R-QA-03), as {@code VendorLocks} does for locks.
 */
public final class VendorSources {

    private VendorSources() {}

    /** Whether this database refuses a composite node key: H2 alone among the Tier-1 vendors (RFC 0014 §7). */
    public static boolean refusesCompositeNodeKey(TckDatabase db) {
        return db.vendor() == TckVendor.H2;
    }
}
