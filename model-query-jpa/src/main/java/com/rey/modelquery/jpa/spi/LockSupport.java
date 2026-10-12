package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.RowLock;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

/**
 * What a row lock needs of the persistence provider, which portable JPA has no API for: whether the provider would
 * render a lock exactly, and how to set it on a statement. Built from {@code jakarta.persistence} types only (INV-7),
 * apart from the core {@link RowLock} value it renders. Reached through {@link ProviderSupport#locks()}; a locked read
 * on a factory with none, or with one that does not render this lock, throws {@code MQ4010} before any statement.
 * Implementations are stateless and thread-safe (R-VND-15, D-127).
 *
 * @implSpec R-VND-15, D-127
 */
@Incubating
public interface LockSupport {

    /**
     * Whether {@link #apply} would render exactly {@code lock} for {@code emf}'s database: false when the provider
     * would drop the wait policy, widen a shared lock to exclusive or defer the locking to loaded entities, so the
     * engine refuses the read with {@code MQ4010} instead of running it unlocked (R-VND-15, D-127). Decided per
     * strength × wait, since a provider can render one wait for one strength and not the other.
     */
    boolean rendered(EntityManagerFactory emf, RowLock lock);

    /**
     * Sets {@code lock} on {@code query} before it runs: the provider's lock mode for the strength, the wait policy as
     * a hint where the provider needs one, and the hint that disables follow-on locking (R-VND-15, D-127).
     */
    void apply(TypedQuery<?> query, RowLock lock);
}
