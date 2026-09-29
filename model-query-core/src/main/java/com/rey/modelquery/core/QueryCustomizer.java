package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;

/**
 * The escape hatch for what the {@code Filters} DSL cannot express: raw access to the {@link CriteriaQuery} of each
 * {@link Phase} the engine runs. It can add predicates, selections and group-by expressions.
 *
 * <p><b>A selection added here cannot be read back.</b> It has no {@link SelectField} key, so {@link Row#get} never
 * returns it and it never reaches the model. A value the model needs goes through a {@link ColumnField} in the
 * {@code ColumnSet}, or through an aggregate built with {@code Agg.of(...)} for an arbitrary expression (spec api/13):
 *
 * <pre>{@code
 * // Wrong: the model never sees this.
 * (spec, joins, query, cb, phase) -> query.multiselect(withExtra(query, cb.upper(status)));
 * // Right: declare the value as a field and select it through the ColumnSet.
 * static final AggregateField<OrderView, String> LABEL = Agg.of(String.class, "label", ctx -> cb.upper(...));
 * }</pre>
 *
 * <p>Keep the phases consistent: a predicate added in {@code MODEL} but not in {@code PRIMARY_KEY} makes
 * primary-key-first paging return rows the caller filtered out. {@link ModelQuery#checkPhases} warns about it.
 *
 * @implSpec R-QRY-07, R-QRY-08, R-QRY-09
 */
@Incubating
@FunctionalInterface
public interface QueryCustomizer {

    /**
     * Adjusts {@code query} for {@code phase}. {@code joins} is the context of this one query build; reuse it so a
     * join the query already made is shared rather than repeated.
     */
    void customize(QuerySpec spec, JoinContext joins, CriteriaQuery<?> query, CriteriaBuilder cb, Phase phase);
}
