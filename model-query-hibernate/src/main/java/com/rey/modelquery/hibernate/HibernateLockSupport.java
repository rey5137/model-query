package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.jpa.spi.LockSupport;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import org.hibernate.LockOptions;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.sqm.tree.from.SqmFrom;

/**
 * Hibernate's {@link LockSupport}, compiled against Hibernate 6.6 and run on 7.x as well: it uses only API both have.
 * {@link #rendered} asks the JDBC {@link Dialect} what it would render for the lock's strength and wait policy, and
 * {@link #apply} sets the statement's lock mode, Hibernate's lock-timeout hint for the wait, and
 * {@code hibernate.query.followOnLocking=false} (R-VND-15, D-127).
 *
 * @implSpec R-VND-15, D-127
 */
final class HibernateLockSupport implements LockSupport {

    /** Hibernate's lock-timeout hint: {@code 0} does not wait, {@code -2} skips a locked row, {@code -1} waits. */
    private static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";

    /** Hibernate's hint that turns a locking clause that locks no loaded entity into a failure instead of a no-op. */
    private static final String FOLLOW_ON_LOCKING = "hibernate.query.followOnLocking";

    /**
     * Whether {@code dialect} renders {@code lock}'s wait policy for its strength. It is decided per strength × wait,
     * because Hibernate takes two paths to the clause: for a write lock it asks the dialect whether it supports the
     * wait at all, while for a read lock it appends the dialect's read clause and keeps the wait only where that clause
     * carries the token. So H2 6.6, which supports neither wait, renders neither for either strength; H2 7.x renders
     * both for {@code exclusive()} and neither for {@code shared()}, which it widens to a plain {@code FOR UPDATE};
     * PostgreSQL and MySQL render every combination (R-VND-15, D-127, spec vendor/41 §6).
     */
    @Override
    public boolean rendered(EntityManagerFactory emf, RowLock lock) {
        return isRendered(emf.unwrap(SessionFactoryImplementor.class).getJdbcServices().getDialect(), lock);
    }

    /** {@link #rendered}, on the {@code dialect} alone, so a unit test needs no factory. */
    static boolean isRendered(Dialect dialect, RowLock lock) {
        boolean exclusive = switch (lock.strength()) {
            case EXCLUSIVE -> true;
            case SHARED -> false;
        };
        return switch (lock.waitPolicy()) {
            case WAIT -> true;
            case NO_WAIT -> exclusive ? dialect.supportsNoWait() : readClause(dialect, lock).contains("nowait");
            case SKIP_LOCKED -> exclusive ? dialect.supportsSkipLocked()
                    : readClause(dialect, lock).contains("skip locked");
        };
    }

    /** The clause {@code dialect} appends to a read lock at {@code lock}'s wait timeout. */
    private static String readClause(Dialect dialect, RowLock lock) {
        return dialect.getReadLockString(timeout(lock));
    }

    /** Joins in Hibernate's own tree, where entity joins and fetches live though {@code getJoins()} omits them. */
    @Override
    public boolean hasUnreportedJoins(CriteriaQuery<?> query) {
        return query.getRoots().stream()
                .anyMatch(root -> root instanceof SqmFrom<?, ?> from && !from.getSqmJoins().isEmpty());
    }

    /** A pessimistic write lock for {@code exclusive()}, a pessimistic read lock for {@code shared()}. */
    @Override
    public void apply(TypedQuery<?> query, RowLock lock) {
        query.setLockMode(switch (lock.strength()) {
            case EXCLUSIVE -> LockModeType.PESSIMISTIC_WRITE;
            case SHARED -> LockModeType.PESSIMISTIC_READ;
        });
        query.setHint(LOCK_TIMEOUT, timeout(lock));
        query.setHint(FOLLOW_ON_LOCKING, false);
    }

    /**
     * Hibernate's lock timeout for {@code lock}'s wait policy. WAIT maps to {@code WAIT_FOREVER} rather than no
     * timeout: Hibernate copies a factory or session lock timeout onto every query, which would otherwise turn a plain
     * wait into NOWAIT (0) or SKIP LOCKED (-2) while rendered() said it waits.
     */
    private static int timeout(RowLock lock) {
        return switch (lock.waitPolicy()) {
            case NO_WAIT -> LockOptions.NO_WAIT;
            case SKIP_LOCKED -> LockOptions.SKIP_LOCKED;
            case WAIT -> LockOptions.WAIT_FOREVER;
        };
    }
}
