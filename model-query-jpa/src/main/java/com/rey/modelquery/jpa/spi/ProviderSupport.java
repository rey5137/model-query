package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.core.Incubating;
import com.rey.modelquery.core.NullPrecedenceRenderer;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a persistence provider can do better than portable JPA: detect the database without a connection, count
 * groups in the database and render null precedence natively. It varies by provider, not by database, so it is not
 * part of {@link VendorProfile} (D-34). Discovered with {@code ServiceLoader}; the first that
 * {@linkplain #supports supports} a factory serves it. Implementations are stateless and thread-safe.
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
     * The number of rows {@code groupedQuery} returns, counted in the database, or empty when this implementation
     * cannot count it; the executor then counts client-side (R-EXE-03).
     */
    default OptionalLong countGroups(TypedQuery<?> groupedQuery) {
        return OptionalLong.empty();
    }

    /** A native null-precedence renderer, or empty to render the portable form (R-COL-12). */
    default Optional<NullPrecedenceRenderer> nullPrecedence() {
        return Optional.empty();
    }
}
