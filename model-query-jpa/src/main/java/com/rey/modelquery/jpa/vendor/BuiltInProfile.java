package com.rey.modelquery.jpa.vendor;

import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.Optional;

/**
 * The built-in profiles: a fixed table of the Tier-1 values in vendor/41 §2 and the conservative {@code OTHER}
 * values. A {@code ServiceLoader}-discovered profile for the same vendor takes precedence (R-VND-03).
 *
 * @implSpec R-PRF-11, R-PRF-08, R-PRF-03, R-PRF-07, R-VND-06, R-VND-11
 */
enum BuiltInProfile implements VendorProfile {

    H2(DatabaseVendor.H2, 10_000, 100_000, NullOrdering.NULLS_FIRST) {
        @Override
        public boolean targetTableInSubquery() {
            return true;
        }
    },

    // The driver uses a cursor only with autocommit off, so streaming outside a transaction would buffer (R-PRF-03).
    POSTGRESQL(DatabaseVendor.POSTGRESQL, 10_000, 65_535, NullOrdering.NULLS_LAST) {
        @Override
        public void checkStreamingPreconditions(EntityManager em) {
            if (!em.isJoinedToTransaction()) {
                throw new ModelQueryExecutionException(MqCode.MQ2101, "PostgreSQL streams with a cursor only inside "
                        + "a transaction, and none is active on this EntityManager; wrap the call in a transaction "
                        + "(read-only is enough) or use keyset export (R-PRF-03)");
            }
        }

        @Override
        public boolean targetTableInSubquery() {
            return true;
        }
    },

    /**
     * The default {@link MysqlStreamingMode#ROW_BY_ROW}: Connector/J streams only at {@code Integer.MIN_VALUE}. MySQL
     * refuses a write reading its own table in a sub-query (error 1093), so keeps the default
     * {@link #targetTableInSubquery()}.
     */
    MYSQL(DatabaseVendor.MYSQL, 10_000, 65_535, NullOrdering.NULLS_FIRST) {
        @Override
        public void applyStreaming(Query query, int fetchSize) {
            query.setHint(FETCH_SIZE_HINT, Integer.MIN_VALUE);
        }
    },

    /** {@link MysqlStreamingMode#CURSOR_FETCH}: a positive fetch size, which needs {@code useCursorFetch=true}. */
    MYSQL_CURSOR_FETCH(DatabaseVendor.MYSQL, 10_000, 65_535, NullOrdering.NULLS_FIRST),

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

    /**
     * The built-in profile of {@code vendor}, or empty for a vendor without one. {@code mode} only picks between the
     * two MySQL profiles, fixed here once and never per query (R-PRF-07).
     */
    static Optional<VendorProfile> of(DatabaseVendor vendor, MysqlStreamingMode mode) {
        if (vendor == DatabaseVendor.MYSQL && mode == MysqlStreamingMode.CURSOR_FETCH) {
            return Optional.of(MYSQL_CURSOR_FETCH);
        }
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
        // Whole seconds, rounded up (R-PRF-05): Hibernate rounds the millisecond hint to the nearest second, which
        // turns a sub-second timeout into none. An Integer, because Hibernate rejects a Long value for the hint.
        // From the seconds, not toMillis(): that is zero below a millisecond and overflows for a huge duration.
        long seconds = Math.min(Integer.MAX_VALUE / 1000 - 1, timeout.toSeconds())
                + (timeout.toNanosPart() > 0 ? 1 : 0);
        query.setHint(TIMEOUT_HINT, (int) seconds * 1000);
    }

    @Override
    public NullOrdering defaultAscendingNullOrdering() {
        return defaultAscendingNullOrdering;
    }
}
