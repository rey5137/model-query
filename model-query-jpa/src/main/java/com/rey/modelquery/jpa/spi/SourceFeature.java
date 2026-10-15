package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;

/**
 * A source-model feature a database may lack: a plain CTE, a recursive one, window functions, and the three set
 * operations. A {@link VendorProfile} answers {@link VendorProfile#sources(SourceFeature)} for each, so the engine can
 * refuse a query whose source uses a feature the target's vendor has none of, before any statement (R-VND-21,
 * R-SRC-11, D-143).
 *
 * <p>A profile written before a constant was added must answer {@code false} for it: a constant's Javadoc names the
 * database fact it stands for, and an implementation claims only the constants it knows, never a later one.
 *
 * @implSpec R-VND-21, R-SRC-11, D-143
 */
@Incubating
public enum SourceFeature {

    /** A common table expression: {@code WITH name AS (SELECT ...) SELECT ... FROM name}. */
    CTE,

    /** A recursive common table expression: {@code WITH RECURSIVE name AS (... UNION ALL ...)}. */
    RECURSIVE,

    /** A window function: an aggregate or ranking function with an {@code OVER (...)} clause. */
    WINDOW,

    /** The {@code UNION} of two or more branches, duplicate rows removed. */
    UNION,

    /** The {@code INTERSECT} of two or more branches. */
    INTERSECT,

    /** The {@code EXCEPT} of two branches. */
    EXCEPT
}
