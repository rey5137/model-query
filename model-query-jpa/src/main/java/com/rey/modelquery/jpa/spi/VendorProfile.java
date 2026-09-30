package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.NullOrdering;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;

/**
 * Every database-specific behaviour the engine needs (INV-6). Implementations are stateless and thread-safe, and are
 * discovered with {@code ServiceLoader}; a discovered profile takes precedence over the built-in one for its vendor
 * (R-VND-03). A new behaviour is a new method with a default.
 *
 * @implSpec R-VND-01, R-VND-02, R-VND-03, R-VND-11
 */
@Incubating
public interface VendorProfile {

    /** The database this profile serves. */
    DatabaseVendor vendor();

    /** The soft limit on the values of one {@code IN (...)} list. */
    int maxInListSize();

    /** The hard limit on bind parameters per statement. */
    int maxBindParameters();

    /** Sets {@code query} up for forward-only streaming of a large result. */
    void applyStreaming(Query query, int fetchSize);

    /** Fails fast when {@code em} cannot stream, such as outside a transaction where the driver needs one. */
    default void checkStreamingPreconditions(EntityManager em) {
    }

    /** Applies {@code timeout} to {@code query}. */
    void applyTimeout(Query query, Duration timeout);

    /** Where the database puts NULLs in ascending order when the query says nothing (R-PRF-08). */
    NullOrdering defaultAscendingNullOrdering();

    /**
     * Whether an {@code UPDATE} or {@code DELETE} may read its own table in a sub-query ({@code Future}, M8).
     * {@code false} is always correct and only slower.
     */
    default boolean targetTableInSubquery() {
        return false;
    }
}
