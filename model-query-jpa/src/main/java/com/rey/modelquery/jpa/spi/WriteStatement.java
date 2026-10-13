package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;

/**
 * Which write a {@link VendorProfile} states a {@link ReturningShape} for (R-VND-17): a bulk update, a bulk delete and
 * an insert, plain or upserting. An upsert states its shape once for {@link #INSERT}: the vendor reaches it through
 * the same statement a plain insert is, {@code MERGE} on H2 or {@code ON CONFLICT} where a database has both.
 *
 * @implSpec R-VND-17
 */
@Incubating
public enum WriteStatement {

    /** A bulk update, chunked or not. */
    UPDATE,

    /** A bulk delete, chunked or not. */
    DELETE,

    /** An insert, plain or upserting through a conflict clause. */
    INSERT
}
