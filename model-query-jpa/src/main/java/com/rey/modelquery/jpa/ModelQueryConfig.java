package com.rey.modelquery.jpa;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import java.util.Objects;
import java.util.Optional;

/**
 * The settings of a {@code ModelQueryExecutor}; later milestones add the timeout and the paging defaults here
 * (R-SPR-08). Immutable: each setter returns a new configuration. One explicit vendor applies to every
 * {@code EntityManagerFactory} the configuration is used with (D-34).
 *
 * @implSpec R-QRY-10, R-VND-04
 */
@Incubating
public final class ModelQueryConfig {

    private static final ModelQueryConfig DEFAULTS = new ModelQueryConfig(null);

    private final DatabaseVendor vendor;

    private ModelQueryConfig(DatabaseVendor vendor) {
        this.vendor = vendor;
    }

    /** The configuration with every setting at its default: the vendor is detected. */
    public static ModelQueryConfig defaults() {
        return DEFAULTS;
    }

    /** This configuration with the database vendor set explicitly, which skips detection entirely (R-VND-04). */
    public ModelQueryConfig vendor(DatabaseVendor vendor) {
        return new ModelQueryConfig(Objects.requireNonNull(vendor, "vendor"));
    }

    /** The explicitly configured vendor, or empty when it is detected per {@code EntityManagerFactory}. */
    public Optional<DatabaseVendor> vendor() {
        return Optional.ofNullable(vendor);
    }
}
