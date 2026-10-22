package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Condition;
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
     * its own name. Empty when the conflict is detected on {@link #constraintName()} (R-WRT-52).
     */
    List<String> keyAttributes();

    /**
     * The unique constraint or index {@code onConstraint(name)} names, or empty when the conflict is detected on
     * {@link #keyAttributes()} (api/14 R-WRT-52). Where the profile's {@code conflictOnConstraint()} is false the
     * engine refuses the insert with {@code MQ1804} before the statement.
     */
    default Optional<String> constraintName() {
        return Optional.empty();
    }

    /**
     * The arbiter predicate of the conflict target, in order, or empty without one (api/14 R-WRT-52): the rows a
     * partial unique index holds, which the provider renders into the statement as {@code column op literal}
     * comparisons, never as bind parameters. Where the profile's {@code conflictArbiterPredicate()} is false the
     * engine refuses the insert with {@code MQ1804} before the statement.
     */
    default List<Condition> arbiter() {
        return List.of();
    }

    /** Whether a conflicting row is skipped; otherwise {@link #update} renders the update. */
    boolean doNothing();

    /**
     * Renders the update of a conflicting row: calls {@code assign} once per assignment, with the stored row's
     * attribute and its new value, which may read {@code excluded}, the incoming row; returns the predicate the stored
     * row must match to be updated, or empty to update every conflicting row. Not called for {@link #doNothing()}.
     * Under inline value handling ({@code ProviderSupport#inlinesValues}, R-VND-28) the returned predicate may hold
     * criteria parameters the engine binds on the {@code Query} that {@code InsertSupport#insertValues} returns, so an
     * implementation renders that predicate into that same statement, not into native SQL text, and calls
     * {@code update} once per {@code insertValues} call, using that call's predicate.
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

        /**
         * Assigns {@code value}, such as the incoming row's attribute, a conflict {@code setExpression}'s value
         * (R-WRT-51) or the version's increment.
         */
        void expression(Path<?> target, Expression<?> value);
    }
}
