package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/** The statement enclosing a treated read; unknown future shapes must be refused (R-VND-26). */
@EngineFacing
@Incubating
public enum StatementShape {
    /** A list, page, keyset page or export page. */
    @Incubating CONTENT,
    /** A total or count call. */
    @Incubating COUNT,
    /** A grouped or aggregate selection. */
    @Incubating GROUPED,
    /** The count of a grouped selection's groups, built by the persistence provider over the grouped statement. */
    @Incubating GROUPED_COUNT,
    /** A source's CTE body. */
    @Incubating SOURCE_BODY,
    /** A fetch-plan child, join plan or enricher. */
    @Incubating CHILD,
    /** A bulk update or delete predicate. */
    @Incubating WRITE
}
