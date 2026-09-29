package com.rey.modelquery.core;

/**
 * The settings of a {@code ModelQueryExecutor}. No setting exists yet; later milestones add the vendor, the timeout
 * and the paging defaults here (R-SPR-08). Immutable.
 *
 * @implSpec R-QRY-10
 */
@Incubating
public final class ModelQueryConfig {

    private static final ModelQueryConfig DEFAULTS = new ModelQueryConfig();

    private ModelQueryConfig() {
    }

    /** The configuration with every setting at its default. */
    public static ModelQueryConfig defaults() {
        return DEFAULTS;
    }
}
