package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Optional;

/**
 * An insert-values statement's conflict clause, as the engine hands it to {@link InsertSupport#insertValues}: the key
 * the conflict is detected on, and either nothing to do or the update to apply to the stored row (api/14 R-WRT-34,
 * R-VND-14, D-117).
 *
 * @param <E> the root entity
 * @implSpec R-VND-14, D-117
 */
@Incubating
public interface ConflictClause<E> {

    /**
     * The root's attributes the conflict is detected on, in the order named: dotted through embeddables, a to-one by
     * its own name.
     */
    List<String> keyAttributes();

    /** Whether a conflicting row is skipped; otherwise {@link #update} renders the update. */
    boolean doNothing();

    /**
     * Renders the update of a conflicting row: calls {@code assign} once per assignment, with the stored row's
     * attribute and its new value, which may read {@code excluded}, the incoming row; returns the predicate the stored
     * row must match to be updated, or empty to update every conflicting row. Not called for {@link #doNothing()}.
     *
     * @param target the stored row
     * @param excluded the incoming row
     */
    Optional<Predicate> update(Root<E> target, Root<E> excluded, CriteriaBuilder cb, Assignments assign);

    /**
     * Where {@link #update} writes its assignments, which the implementation adds to the provider's conflict action.
     */
    @Incubating
    interface Assignments {

        /**
         * Assigns {@code value}, which may be {@code null}, to {@code target}, bound as a parameter of
         * {@code target}'s type, never inlined, so a value an {@code AttributeConverter} or {@code @Enumerated} maps
         * is written through that mapping (R-WRT-14).
         */
        void value(Path<?> target, Object value);

        /** Assigns {@code value}, such as the incoming row's attribute or the version's increment. */
        void expression(Path<?> target, Expression<?> value);
    }
}
