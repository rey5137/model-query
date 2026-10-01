package com.rey.modelquery.core;


/**
 * The statement a {@link QueryCustomizer} is being applied to. A query runs one to three statements: {@code MODEL}
 * reads the rows, {@code PRIMARY_KEY} reads only the primary keys of a page, and {@code MODEL_BY_KEYS} reads the rows
 * of those keys (primary-key-first paging).
 *
 * @implSpec R-QRY-07
 */
public enum Phase {
    /** The statement that selects the model's columns. */
    MODEL,
    /** The statement that selects only the primary-key columns. */
    PRIMARY_KEY,
    /** The statement that selects the model's columns for a set of primary keys. */
    MODEL_BY_KEYS
}
