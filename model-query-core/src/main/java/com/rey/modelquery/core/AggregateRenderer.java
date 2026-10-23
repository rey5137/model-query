package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.util.List;

/**
 * The provider's native forms of an aggregate, beyond standard Criteria (R-VND-30, INV-7). The executor supplies it
 * through {@code ProviderSupport#aggregates()}; each method has a default that is the engine's own standard-Criteria
 * form, so a provider overrides only what it renders better.
 *
 * @implSpec R-AGG-16, R-AGG-17, R-AGG-18, R-AGG-19, R-VND-29, R-VND-30, D-185
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
        if (kind == AggregateField.Kind.FIRST_BY) {
            // array_agg keeps NULLs, so the CASE form yields a NULL element where FILTER skips the row (R-AGG-17).
            throw new ModelQueryConfigurationException(MqCode.MQ4014, "a filtered Agg.firstBy needs the native FILTER, "
                    + "which the provider's AggregateRenderer does not render");
        }
        return AggregateField.over(cb, kind, cb.selectCase().when(condition, (Expression) argument));
    }

    /**
     * The exact average of {@code argument}, {@code sum(x) / count(x)} with the quotient typed {@code BigDecimal}
     * (R-AGG-18): an integer input is cast to {@code BigDecimal} first, a {@code BigDecimal} input is not. Standard
     * Criteria types {@code avg} as {@code Double}, so there is no portable form and the default refuses.
     *
     * @throws ModelQueryConfigurationException {@code MQ4014} by default: the provider has no renderer
     */
    @Incubating
    default Expression<BigDecimal> exactAverage(CriteriaBuilder cb, Expression<? extends Number> argument) {
        throw new ModelQueryConfigurationException(MqCode.MQ4014, "an exact average (Agg.avgExact) needs the "
                + "provider's AggregateRenderer, which the executor does not supply");
    }

    /**
     * The value of {@code value} on the first row in {@code orders}, the root's id already appended (R-AGG-16): the
     * result is typed as {@code value}, so its converter applies, and is {@code NULL} for a group with no row. Standard
     * Criteria has no array aggregate, so there is no portable form and the default refuses.
     *
     * @throws ModelQueryConfigurationException {@code MQ4014} by default: the provider has no renderer
     */
    @Incubating
    default Expression<?> firstBy(CriteriaBuilder cb, Expression<?> value, List<Order> orders) {
        throw new ModelQueryConfigurationException(MqCode.MQ4014, "Agg.firstBy needs the provider's "
                + "AggregateRenderer, which the executor does not supply");
    }

    /**
     * {@link #firstBy(CriteriaBuilder, Expression, List)} over only the rows {@code filter} matches, as the native
     * {@code FILTER (WHERE ...)}: the CASE form is the wrong form for it (R-AGG-17).
     *
     * @throws ModelQueryConfigurationException {@code MQ4014} by default: the provider renders no filtered form
     */
    @Incubating
    default Expression<?> firstBy(CriteriaBuilder cb, Expression<?> value, List<Order> orders, Predicate filter) {
        throw new ModelQueryConfigurationException(MqCode.MQ4014, "a filtered Agg.firstBy needs the native FILTER, "
                + "which the provider's AggregateRenderer does not render");
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
