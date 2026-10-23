package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * The provider's native forms of an aggregate, beyond standard Criteria (R-VND-30, INV-7). The executor supplies it
 * through {@code ProviderSupport#aggregates()}; each method has a default that is the engine's own standard-Criteria
 * form, so a provider overrides only what it renders better.
 *
 * @implSpec R-AGG-17, R-AGG-19, R-VND-29, R-VND-30, D-185
 */
@EngineFacing
@Incubating
public interface AggregateRenderer {

    /** The renderer a statement uses when the provider has none: every method is its default. */
    AggregateRenderer DEFAULT = new AggregateRenderer() {};

    /**
     * {@code kind} over {@code argument} for the rows {@code condition} matches, an {@code AggregateField#filter}
     * (R-AGG-17). The default is the CASE form, {@code fn(case when condition then argument end)}, which equals
     * {@code fn(argument) filter (where condition)} for every function here, as each ignores NULL inputs; a count
     * counts the {@code argument} its unfiltered form counts. The result's Java type is the unfiltered aggregate's.
     */
    @Incubating
    @SuppressWarnings({"unchecked", "rawtypes"})
    default Expression<?> filtered(CriteriaBuilder cb, AggregateField.Kind kind, Expression<?> argument,
            Predicate condition) {
        return AggregateField.over(cb, kind, cb.selectCase().when(condition, (Expression) argument));
    }

    /**
     * The count of the statement's rows, in place of {@code count(current)}, the count of an identity that no row the
     * statement reads has null (R-AGG-19). The default is {@code cb.count(current)}, the SQL a count renders without
     * this renderer; standard Criteria has no {@code count(*)}.
     */
    @Incubating
    default Expression<Long> rowCount(CriteriaBuilder cb, Expression<?> current) {
        return cb.count(current);
    }
}
