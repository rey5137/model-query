package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;

/**
 * Which write a {@link VendorProfile} states a {@link ReturningShape} for (R-VND-17): a bulk update, and a bulk
 * delete. The insert and upsert kinds arrive with the insert path of R-WRT-50, whose shape a database can state for
 * an insert and a {@code MERGE} apart.
 *
 * @implSpec R-VND-17
 */
@Incubating
public enum WriteStatement {

    /** A bulk update, chunked or not. */
    UPDATE,

    /** A bulk delete, chunked or not. */
    DELETE
}
