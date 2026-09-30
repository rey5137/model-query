package com.rey.modelquery.jpa.vendor;

import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.Optional;

/**
 * The built-in profiles: a fixed table of the Tier-1 values in vendor/41 §2 and the conservative {@code OTHER}
 * values. A {@code ServiceLoader}-discovered profile for the same vendor takes precedence (R-VND-03).
 *
 * @implSpec R-PRF-11, R-PRF-08, R-VND-06
 */
enum BuiltInProfile implements VendorProfile {

    H2(DatabaseVendor.H2, 10_000, 100_000, NullOrdering.NULLS_FIRST),

    // The driver uses a cursor only with autocommit off; checkStreamingPreconditions says so from M3.4 (R-PRF-03).
    POSTGRESQL(DatabaseVendor.POSTGRESQL, 10_000, 65_535, NullOrdering.NULLS_LAST),

    MYSQL(DatabaseVendor.MYSQL, 10_000, 65_535, NullOrdering.NULLS_FIRST) {
        @Override
        public void applyStreaming(Query query, int fetchSize) {
            // Connector/J streams row by row only at Integer.MIN_VALUE; cursor-fetch mode is R-PRF-07's setting.
            query.setHint(FETCH_SIZE_HINT, Integer.MIN_VALUE);
        }
    },

    OTHER(DatabaseVendor.OTHER, 1_000, 2_000, NullOrdering.UNKNOWN);

    /** The fetch-size hint vendor/41 §2 names; a provider that does not know it ignores it. */
    static final String FETCH_SIZE_HINT = "org.hibernate.fetchSize";

    /** The portable query timeout hint, in milliseconds (R-PRF-05). */
    static final String TIMEOUT_HINT = "jakarta.persistence.query.timeout";

    private final DatabaseVendor vendor;
    private final int maxInListSize;
    private final int maxBindParameters;
    private final NullOrdering defaultAscendingNullOrdering;

    BuiltInProfile(DatabaseVendor vendor, int maxInListSize, int maxBindParameters, NullOrdering nullOrdering) {
        this.vendor = vendor;
        this.maxInListSize = maxInListSize;
        this.maxBindParameters = maxBindParameters;
        this.defaultAscendingNullOrdering = nullOrdering;
    }

    /** The built-in profile of {@code vendor}, or empty for a vendor without one. */
    static Optional<VendorProfile> of(DatabaseVendor vendor) {
        for (BuiltInProfile profile : values()) {
            if (profile.vendor == vendor) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    @Override
    public DatabaseVendor vendor() {
        return vendor;
    }

    @Override
    public int maxInListSize() {
        return maxInListSize;
    }

    @Override
    public int maxBindParameters() {
        return maxBindParameters;
    }

    @Override
    public void applyStreaming(Query query, int fetchSize) {
        query.setHint(FETCH_SIZE_HINT, fetchSize);
    }

    @Override
    public void applyTimeout(Query query, Duration timeout) {
        query.setHint(TIMEOUT_HINT, timeout.toMillis());
    }

    @Override
    public NullOrdering defaultAscendingNullOrdering() {
        return defaultAscendingNullOrdering;
    }
}
