package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;

/**
 * How a database returns the rows a write changed: a {@code RETURNING} suffix on the write's own statement, or a
 * wrapper that reads the write's delta table. A database fact, not a rendering hook: a
 * {@link VendorProfile#returning(WriteStatement)} states only which of these the database supports, and renders no
 * SQL (R-VND-11, R-VND-17). The provider's {@link ReturningSupport} renders the shape, where the provider's own
 * translation did not.
 *
 * @implSpec R-VND-17, R-WRT-50
 */
@Incubating
public enum ReturningShape {

    /** The write's own SQL, with {@code RETURNING <columns>} appended. */
    SUFFIX,

    /**
     * The write's SQL as the source of a select over its delta table: the rows it wrote, as
     * {@code SELECT <columns> FROM FINAL TABLE (<write>)} on an update and {@code OLD TABLE} on a delete.
     */
    DELTA_TABLE
}
