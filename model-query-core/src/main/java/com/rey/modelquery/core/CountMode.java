package com.rey.modelquery.core;

/**
 * Whether {@code page} counts the rows behind the page, and whether it reads them at all.
 *
 * @implSpec R-EXE-02
 */
@Incubating
public enum CountMode {
    /** Runs the count query and reports an exact total. */
    COUNT,
    /** Fetches one row more than the page to learn {@code hasNext}, and reports the total as unknown. */
    NO_COUNT,
    /** Runs the count query only; the content is empty. */
    ONLY_COUNT
}
