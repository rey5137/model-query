package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;

/**
 * The row lock a read takes: a strength and a wait policy, set by {@link ModelQuery.Builder#lock(RowLock)} or
 * {@link ModelQuery#withLock(RowLock)}. Immutable, and equal when both parts are.
 *
 * <p>The strengths are named for what they guarantee, not for a SQL keyword: {@link Strength#EXCLUSIVE} means no
 * other transaction updates, deletes or exclusively locks the row until this one ends, and {@link Strength#SHARED}
 * means no other transaction updates or deletes it. A provider renders each with the closest clause it has, which may
 * be a shared lock widened to exclusive, such as H2's {@code FOR UPDATE} for both (R-VND-15).
 *
 * @implSpec R-QRY-17, D-127
 */
@Incubating
public final class RowLock {

    /** What the lock guarantees, not the clause a provider renders for it. */
    @Incubating
    public enum Strength {

        /** No other transaction updates, deletes or exclusively locks the row until this one ends. */
        EXCLUSIVE,

        /** No other transaction updates or deletes the row until this one ends. */
        SHARED
    }

    /** What a read does when the row is already locked by another transaction. */
    @Incubating
    public enum Wait {

        /** Waits for the other transaction to release the row. The default. */
        WAIT,

        /** Fails at once, leaving the caller free to retry. */
        NO_WAIT,

        /** Returns the next unlocked row instead, for a queue's claim-the-next-job read. */
        SKIP_LOCKED
    }

    private final Strength strength;
    private final Wait wait;

    private RowLock(Strength strength, Wait wait) {
        this.strength = strength;
        this.wait = wait;
    }

    /** An exclusive lock that waits: {@code exclusive(Wait.WAIT)}. */
    public static RowLock exclusive() {
        return exclusive(Wait.WAIT);
    }

    /**
     * An exclusive lock with {@code wait}'s policy: no other transaction updates, deletes or exclusively locks the
     * row until this one ends.
     *
     * @throws NullPointerException when {@code wait} is {@code null}
     */
    public static RowLock exclusive(Wait wait) {
        return new RowLock(Strength.EXCLUSIVE, Objects.requireNonNull(wait, "wait"));
    }

    /** A shared lock that waits: {@code shared(Wait.WAIT)}. */
    public static RowLock shared() {
        return shared(Wait.WAIT);
    }

    /**
     * A shared lock with {@code wait}'s policy: no other transaction updates or deletes the row until this one ends.
     *
     * @throws NullPointerException when {@code wait} is {@code null}
     */
    public static RowLock shared(Wait wait) {
        return new RowLock(Strength.SHARED, Objects.requireNonNull(wait, "wait"));
    }

    /** What the lock guarantees. */
    public Strength strength() {
        return strength;
    }

    /** What a read does when the row is already locked; not {@code wait()}, which {@link Object} declares. */
    public Wait waitPolicy() {
        return wait;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RowLock other && strength == other.strength && wait == other.wait;
    }

    @Override
    public int hashCode() {
        return Objects.hash(strength, wait);
    }

    @Override
    public String toString() {
        return strength == Strength.EXCLUSIVE ? "RowLock.exclusive(" + wait + ")" : "RowLock.shared(" + wait + ")";
    }
}
