package com.rey.modelquery.jpa;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The settings of a {@code ModelQueryExecutor}; later milestones add the timeout and the remaining paging defaults
 * here (R-SPR-08). Immutable: each setter returns a new configuration. One explicit vendor applies to every
 * {@code EntityManagerFactory} the configuration is used with (D-34).
 *
 * @implSpec R-QRY-10, R-VND-04, R-PAG-07
 */
@Incubating
public final class ModelQueryConfig {

    /** No batch size set: step 2 reads the whole page, within the profile's clamp (D-32). */
    private static final int WHOLE_PAGE = 0;

    private static final ModelQueryConfig DEFAULTS = new ModelQueryConfig(null, WHOLE_PAGE);

    private final DatabaseVendor vendor;
    private final int primaryKeyFirstBatchSize;

    private ModelQueryConfig(DatabaseVendor vendor, int primaryKeyFirstBatchSize) {
        this.vendor = vendor;
        this.primaryKeyFirstBatchSize = primaryKeyFirstBatchSize;
    }

    /** The configuration with every setting at its default: the vendor is detected, step 2 reads the whole page. */
    public static ModelQueryConfig defaults() {
        return DEFAULTS;
    }

    /** This configuration with the database vendor set explicitly, which skips detection entirely (R-VND-04). */
    public ModelQueryConfig vendor(DatabaseVendor vendor) {
        return new ModelQueryConfig(Objects.requireNonNull(vendor, "vendor"), primaryKeyFirstBatchSize);
    }

    /** The explicitly configured vendor, or empty when it is detected per {@code EntityManagerFactory}. */
    public Optional<DatabaseVendor> vendor() {
        return Optional.ofNullable(vendor);
    }

    /**
     * This configuration with at most {@code batchSize} keys per primary-key-first step-2 statement. The profile's
     * IN-list and bind-parameter clamp still applies, so a statement takes the smaller of the two (R-PAG-07, D-32).
     *
     * @throws ModelQueryConfigurationException {@code MQ4003} when {@code batchSize} is below one
     */
    public ModelQueryConfig primaryKeyFirstBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new ModelQueryConfigurationException(MqCode.MQ4003,
                    "primaryKeyFirstBatchSize " + batchSize + " is below one");
        }
        return new ModelQueryConfig(vendor, batchSize);
    }

    /** The configured step-2 batch size, or empty when step 2 reads the whole page within the profile's clamp. */
    public OptionalInt primaryKeyFirstBatchSize() {
        return primaryKeyFirstBatchSize == WHOLE_PAGE ? OptionalInt.empty() : OptionalInt.of(primaryKeyFirstBatchSize);
    }
}
