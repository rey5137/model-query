package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * A database feature a {@link FilterOperator} can require ({@link FilterOperator#requires}). It names the feature
 * and holds no SQL: whether a vendor and a provider serve it are their facts, checked before any statement, and an
 * operator that requires one is {@code MQ4014} when rendered with no operator gate. Users can't add features: the
 * enum is the library's vendor knowledge, and it may gain constants, so a {@code switch} on it needs a
 * {@code default}.
 *
 * <p>A feature means the catalogue's rendering: a vendor answers {@code true} only where that SQL runs, and a
 * provider only where its functions are served. A user operator that renders its own SQL should require no feature.
 *
 * @implSpec api/12 R-FLT-20, vendor/40 R-VND-23, D-167
 */
@Incubating
public enum OperatorFeature {
    /** A case-insensitive {@code ILIKE}, native rather than emulated as {@code lower(col) like lower(?)}. */
    ILIKE,
    /** A regular-expression match, such as PostgreSQL's {@code ~}. */
    REGEX_MATCH,
    /** Array containment and overlap, such as PostgreSQL's {@code @>} and {@code &&} over arrays. */
    ARRAY_CONTAINMENT,
    /** {@code jsonb} containment, PostgreSQL's {@code @>} over {@code jsonb}. */
    JSONB_CONTAINMENT,
    /** Full-text search, such as PostgreSQL's {@code to_tsvector(...) @@ ...}. */
    FULL_TEXT
}
