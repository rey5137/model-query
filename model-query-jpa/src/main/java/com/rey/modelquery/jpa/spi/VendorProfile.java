package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.OperatorFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.Optional;

/**
 * Every database-specific behaviour the engine needs (INV-6). Implementations are stateless and thread-safe, and are
 * discovered with {@code ServiceLoader}; a discovered profile takes precedence over the built-in one for its vendor
 * (R-VND-03). A new behaviour is a new method with a default.
 *
 * @implSpec R-VND-01, R-VND-02, R-VND-03, R-VND-11, R-VND-12, R-VND-14, R-VND-17, R-VND-21, R-VND-22,
 *     R-VND-23
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

    /**
     * How the database returns the rows {@code kind} changed, or empty, the default, where it cannot: a returning
     * write then throws {@code MQ4011} at method entry, before any statement (R-WRT-50, R-VND-17). A database fact of
     * the shape alone; the provider renders the SQL (R-VND-11). A profile written before it returns empty and stays
     * safe.
     */
    default Optional<ReturningShape> returning(WriteStatement kind) {
        return Optional.empty();
    }

    /**
     * Whether the database accepts {@code ON CONFLICT ON CONSTRAINT name}, so a conflict can be detected on a unique
     * constraint or index no set of columns names, a partial one included (api/14 R-WRT-52). {@code false}, the
     * default, fails safe (R-VND-14): an insert with {@code onConstraint(...)} is refused with {@code MQ1804} on first
     * execution. A database fact, no SQL (R-VND-11, INV-6).
     */
    default boolean conflictOnConstraint() {
        return false;
    }

    /**
     * Whether the database accepts an arbiter predicate on a conflict target, so a conflict is detected only on the
     * rows a partial unique index holds (api/14 R-WRT-52). {@code false}, the default, fails safe (R-VND-14): an
     * insert with {@code onConflict(...).where(...)} is refused with {@code MQ1804} on first execution. A database
     * fact, no SQL (R-VND-11, INV-6).
     */
    default boolean conflictArbiterPredicate() {
        return false;
    }

    /**
     * Whether the database has sequences, so {@code Expr.nextval} can read one's next value (api/10 R-COL-28).
     * {@code false}, the default, fails safe: a {@code nextval} is then refused with {@code MQ1509}. A database fact,
     * no SQL (R-VND-11, INV-6).
     */
    default boolean sequences() {
        return false;
    }

    /**
     * Whether the database has {@code feature}, so a query whose source uses it runs (api/17 R-SRC-11, R-VND-21,
     * D-143). {@code false}, the default, fails safe: the query is then refused with {@code MQ4015} before any
     * statement. A database fact, no SQL (R-VND-11, INV-6). A profile written before a {@link SourceFeature} was
     * added must answer {@code false} for it.
     */
    default boolean sources(SourceFeature feature) {
        return false;
    }

    /**
     * Whether the database has {@code feature}, so a filter operator that requires it runs (api/12 R-FLT-20,
     * R-VND-23, D-167). {@code false}, the default, fails safe: the filter is then refused with {@code MQ4015} before
     * any statement, checked before the provider's {@code ProviderSupport#operators}. A database fact, no SQL
     * (R-VND-11, INV-6). A profile written before an {@link OperatorFeature} was added must answer {@code false} for
     * it.
     */
    @Incubating
    default boolean operators(OperatorFeature feature) {
        return false;
    }

    /**
     * The deepest level a recursive source may reach on this database, the vendor's own recursion ceiling: a source
     * whose {@code maxDepth + 2} exceeds it is {@code MQ4015} before any statement (api/17 R-SRC-11, R-VND-22,
     * D-143). {@code Integer.MAX_VALUE}, the default, means the database sets no ceiling. A database fact, no SQL
     * (R-VND-11, INV-6).
     */
    default int maxRecursionDepth() {
        return Integer.MAX_VALUE;
    }
}
