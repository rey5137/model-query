package com.rey.modelquery.jpa;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.KeysetNullKeys;
import com.rey.modelquery.jpa.spi.MysqlStreamingMode;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The settings of a {@code ModelQueryExecutor}; later milestones add the remaining paging defaults here (R-SPR-08).
 * Immutable: each setter returns a new configuration. One explicit vendor applies to every
 * {@code EntityManagerFactory} the configuration is used with (D-34).
 *
 * @implSpec R-QRY-10, R-VND-04, R-PAG-07, R-EXE-11, R-PRF-07, R-PAG-05
 */
@Incubating
public final class ModelQueryConfig {

    /** No batch size set: step 2 reads the whole page, within the profile's clamp (D-32). */
    private static final int WHOLE_PAGE = 0;

    private static final ModelQueryConfig DEFAULTS =
            new ModelQueryConfig(null, WHOLE_PAGE, null, MysqlStreamingMode.ROW_BY_ROW, KeysetNullKeys.FAIL);

    private final DatabaseVendor vendor;
    private final int primaryKeyFirstBatchSize;
    private final Duration queryTimeout;
    private final MysqlStreamingMode mysqlStreamingMode;
    private final KeysetNullKeys keysetNullKeys;

    private ModelQueryConfig(DatabaseVendor vendor, int primaryKeyFirstBatchSize, Duration queryTimeout,
            MysqlStreamingMode mysqlStreamingMode, KeysetNullKeys keysetNullKeys) {
        this.vendor = vendor;
        this.primaryKeyFirstBatchSize = primaryKeyFirstBatchSize;
        this.queryTimeout = queryTimeout;
        this.mysqlStreamingMode = mysqlStreamingMode;
        this.keysetNullKeys = keysetNullKeys;
    }

    /**
     * The configuration with every setting at its default: the vendor is detected, step 2 reads the whole page, no
     * query timeout, MySQL streams row by row, a NULL keyset key without explicit precedence fails.
     */
    public static ModelQueryConfig defaults() {
        return DEFAULTS;
    }

    /** This configuration with the database vendor set explicitly, which skips detection entirely (R-VND-04). */
    public ModelQueryConfig vendor(DatabaseVendor vendor) {
        return new ModelQueryConfig(Objects.requireNonNull(vendor, "vendor"), primaryKeyFirstBatchSize, queryTimeout,
                mysqlStreamingMode, keysetNullKeys);
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
        return new ModelQueryConfig(vendor, batchSize, queryTimeout, mysqlStreamingMode, keysetNullKeys);
    }

    /** The configured step-2 batch size, or empty when step 2 reads the whole page within the profile's clamp. */
    public OptionalInt primaryKeyFirstBatchSize() {
        return primaryKeyFirstBatchSize == WHOLE_PAGE ? OptionalInt.empty() : OptionalInt.of(primaryKeyFirstBatchSize);
    }

    /**
     * This configuration with a timeout applied to every statement the executor runs, through
     * {@code VendorProfile.applyTimeout}. The JDBC granularity is one second, rounded up (R-EXE-11, R-PRF-05).
     *
     * @throws ModelQueryConfigurationException {@code MQ4003} when {@code timeout} is not positive
     */
    public ModelQueryConfig queryTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new ModelQueryConfigurationException(MqCode.MQ4003, "queryTimeout " + timeout + " is not positive");
        }
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, timeout, mysqlStreamingMode, keysetNullKeys);
    }

    /** The configured query timeout, or empty when statements run without one. */
    public Optional<Duration> queryTimeout() {
        return Optional.ofNullable(queryTimeout);
    }

    /**
     * This configuration with the MySQL streaming mode set. {@link MysqlStreamingMode#CURSOR_FETCH} only takes effect
     * when the JDBC URL carries {@code useCursorFetch=true}, which the library cannot set (R-PRF-07).
     */
    public ModelQueryConfig mysqlStreamingMode(MysqlStreamingMode mode) {
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout,
                Objects.requireNonNull(mode, "mode"), keysetNullKeys);
    }

    /** The configured MySQL streaming mode, {@link MysqlStreamingMode#ROW_BY_ROW} unless set. */
    public MysqlStreamingMode mysqlStreamingMode() {
        return mysqlStreamingMode;
    }

    /**
     * This configuration with what keyset paging does with a NULL in a column ordered with {@code DEFAULT} null
     * precedence ({@code modelquery.keyset.null-keys}, R-PAG-05).
     */
    public ModelQueryConfig keysetNullKeys(KeysetNullKeys nullKeys) {
        return new ModelQueryConfig(vendor, primaryKeyFirstBatchSize, queryTimeout, mysqlStreamingMode,
                Objects.requireNonNull(nullKeys, "nullKeys"));
    }

    /** The configured keyset NULL handling, {@link KeysetNullKeys#FAIL} unless set. */
    public KeysetNullKeys keysetNullKeys() {
        return keysetNullKeys;
    }
}
