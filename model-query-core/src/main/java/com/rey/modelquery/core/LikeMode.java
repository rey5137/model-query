package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * How a {@link Filters#like} value becomes a {@code LIKE} pattern.
 *
 * @implSpec R-FLT-06
 */
@Incubating
public enum LikeMode {
    /** The value is the pattern, passed through as given: its {@code %} and {@code _} are wildcards. */
    EXACT,
    /** Matches the value anywhere; {@code %}, {@code _} and {@code \} in it match literally. */
    CONTAINS,
    /** Matches values starting with the value; {@code %}, {@code _} and {@code \} in it match literally. */
    STARTS_WITH,
    /** Matches values ending with the value; {@code %}, {@code _} and {@code \} in it match literally. */
    ENDS_WITH
}
