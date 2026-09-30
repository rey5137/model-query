package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.Optional;

/**
 * What a persistence provider can do better than portable JPA: detect the database without a connection, count
 * groups in the database, render null precedence natively and report a configured default null ordering. It varies
 * by provider, not by database, so it is not part of {@link VendorProfile} (D-34). Discovered with
 * {@code ServiceLoader}; the first that {@linkplain #supports supports} a factory serves it. Implementations are
 * stateless and thread-safe.
 *
 * @implSpec R-VND-04, R-EXE-03
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
}
