package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.NullOrdering;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;

/**
 * Every database-specific behaviour the engine needs (INV-6). Implementations are stateless and thread-safe, and are
 * discovered with {@code ServiceLoader}; a discovered profile takes precedence over the built-in one for its vendor
 * (R-VND-03). A new behaviour is a new method with a default.
 *
 * @implSpec R-VND-01, R-VND-02, R-VND-03, R-VND-11, R-VND-12, R-VND-14
 */
@Incubating
public interface VendorProfile {

    /** The database this profile serves. */
    DatabaseVendor vendor();

    /** The soft limit on the values of one {@code IN (...)} list. */
    int maxInListSize();

    /** The hard limit on bind parameters per statement. */
    int maxBindParameters();

    /**
     * The fetch size a forward-only stream of a large result runs with, given the configured {@code requested} size:
     * {@code requested} where the driver reads by cursor, or the value the driver streams at. A database fact only:
     * the factory's {@link ProviderSupport#resultStream} streams the query with it (R-VND-12, D-108). Defaults to
     * {@code requested}.
     */
    default int streamingFetchSize(int requested) {
        return requested;
    }

    /** Fails fast when {@code em} cannot stream, such as outside a transaction where the driver needs one. */
    default void checkStreamingPreconditions(EntityManager em) {
    }

    /** Applies {@code timeout} to {@code query}. */
    void applyTimeout(Query query, Duration timeout);

    /** Where the database puts NULLs in ascending order when the query says nothing (R-PRF-08). */
    NullOrdering defaultAscendingNullOrdering();

    /**
     * Whether an {@code UPDATE} or {@code DELETE} may read its own table in a sub-query. Where it may not, a bulk write
     * whose rendering reads it runs key-first: it selects the matching keys, then writes them with the root
     * predicates re-applied (api/14 R-WRT-11). {@code false}, the default, is always correct and only slower, so a
     * profile written before it stays safe. A capability, not a rendering hook: the engine renders every predicate
     * itself (R-VND-11).
     */
    default boolean targetTableInSubquery() {
        return false;
    }

    /**
     * The most rows one multi-row {@code VALUES} insert may hold, beyond the bind-parameter limit that already bounds
     * it (api/14 R-WRT-29). {@code 1_000}, the default, is a limit some databases set; a lower one is only slower
     * (R-VND-14, D-117).
     */
    default int maxValuesRows() {
        return 1_000;
    }

    /**
     * Whether a conflict clause detects a conflict only on the key it names. Where the database detects it on any
     * unique key (MySQL, MariaDB), an insert with a conflict clause is refused with {@code MQ1804} unless it accepts
     * that with {@code anyUniqueKey()} (api/14 R-WRT-36). {@code false}, the default, fails safe (R-VND-14, D-117).
     */
    default boolean conflictTargetHonoured() {
        return false;
    }

    /**
     * Whether a conflict update's {@code where} reads the values the update's earlier assignments wrote, rather than
     * the stored row: MySQL renders it as a {@code CASE} in each assignment and assigns left to right. Where it does,
     * an insert whose {@code where} reads two or more of the columns its update assigns is refused with
     * {@code MQ1804}, whatever {@code ModelQueryConfig.conflictUpdateWhereOnAssignedColumns} says (api/14 R-WRT-34).
     * {@code true}, the default, fails safe (R-VND-14, D-117).
     */
    default boolean conflictWhereSeesEarlierAssignments() {
        return true;
    }
}
