package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a persistence provider can do better than portable JPA: detect the database without a connection, count
 * groups in the database, render null precedence natively, report a configured default null ordering, stream a
 * query's rows by cursor and name the tables an entity reads. It varies by provider, not by database, so it is not
 * part of {@link VendorProfile} (D-34, D-108). Discovered with {@code ServiceLoader}; the first that
 * {@linkplain #supports supports} a factory serves it. Implementations are stateless and thread-safe.
 *
 * @implSpec R-VND-04, R-EXE-03, R-VND-12, R-VND-13
 */
@Incubating
public interface ProviderSupport {

    /** Whether this implementation serves {@code emf}'s persistence provider. */
    boolean supports(EntityManagerFactory emf);

    /** The database {@code emf} runs on, without opening a connection, or empty when not known (R-VND-05). */
    default Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * A query counting, in the database, the rows {@code groupedQuery} returns, or empty when this implementation
     * cannot build one; the executor then counts client-side (R-EXE-03). The executor runs the returned query itself,
     * so the configured query timeout applies to it (R-EXE-11).
     */
    default Optional<CriteriaQuery<Long>> countQuery(CriteriaQuery<?> groupedQuery) {
        return Optional.empty();
    }

    /** A native null-precedence renderer, or empty to render the portable form (R-COL-12). */
    default Optional<NullPrecedenceRenderer> nullPrecedence() {
        return Optional.empty();
    }

    /**
     * Where {@code emf}'s provider is configured to sort the NULLs of an order it renders without a null precedence,
     * overriding the database's default, such as Hibernate's {@code hibernate.order_by.default_null_ordering}:
     * {@code FIRST} or {@code LAST} in both directions, or empty when not configured, so the database's own default
     * applies. Keyset paging places a {@code DEFAULT}-precedence column's NULLs by it instead of by the profile's
     * {@code defaultAscendingNullOrdering()} (R-PAG-05, D-36).
     */
    default Optional<NullPrecedence> defaultNullPrecedence(EntityManagerFactory emf) {
        return Optional.empty();
    }

    /**
     * Opens {@code query}, a query of this implementation's provider, as a stream that reads its rows from the database
     * as they are pulled, {@code fetchSize} at a time, the size the profile's {@code streamingFetchSize} chose. The
     * implementation opens the stream itself, because a provider may stream only through its own API: a portable
     * {@code getResultStream()} may read the whole result first. Closing the stream releases the cursor (R-VND-12,
     * D-108).
     */
    <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize);

    /**
     * Every table reading {@code entity} in {@code emf} touches, such as a joined supertable, a secondary table or a
     * subclass table, each unquoted and qualified with the configured default catalog and schema when it names none,
     * to be compared ignoring case; empty when it cannot tell, the default. A bulk write intersects them with the
     * root's to tell that a sub-query reads a table the write targets (R-VND-13, R-WRT-11, D-109).
     */
    default Set<String> tablesOf(EntityManagerFactory emf, Class<?> entity) {
        return Set.of();
    }
}
