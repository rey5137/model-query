package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

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
    ONLY_COUNT,
    /**
     * COUNT's exact total, read from the page statement as a window where that statement returns exactly the rows
     * COUNT counts, and from COUNT's statements otherwise (R-EXE-15). The window counts the whole result before the
     * page's LIMIT, so the database can't stop early: on a large, unselective result COUNT can be faster.
     */
    @Incubating
    INLINE_COUNT
}
