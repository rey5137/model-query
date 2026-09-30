package com.rey.modelquery.core;

/**
 * Where a database puts NULLs in ascending order when the query says nothing. Vendor-neutral: it is what a profile
 * reports, not which vendor reports it (R-VND-08, R-PRF-08).
 *
 * @implSpec R-PRF-08
 */
@Incubating
public enum NullOrdering {
    /** NULLs sort before every value in ascending order. */
    NULLS_FIRST,
    /** NULLs sort after every value in ascending order. */
    NULLS_LAST,
    /** Not known, so no rendering or keyset decision may rely on it (R-VND-06). */
    UNKNOWN
}
