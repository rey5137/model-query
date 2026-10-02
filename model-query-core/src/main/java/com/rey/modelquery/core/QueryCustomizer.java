package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;

/**
 * The escape hatch for what the {@code Filters} DSL cannot express: raw access to the {@link CriteriaQuery} of each
 * {@link Phase} the engine runs. It can add predicates, joins and selections. It must not change the ordering or the
 * grouping: paging and export order, dedupe and read cursors by the query's {@code orderBy} and {@code groupBy}, so
 * {@link ModelQuery#buildQuery} throws {@code MQ1205} when the {@code ORDER BY} or {@code GROUP BY} a customizer
 * returns differs from the one it received (R-QRY-11, D-33).
 *
 * <p><b>A selection added here cannot be read back.</b> It has no {@link SelectField} key, so {@link Row#get} never
 * returns it and it never reaches the model. A value the model needs goes through a {@link ColumnField} in the
 * {@code SelectSet}; a value derived from other columns of the row is computed in {@code afterMap} (R-QRY-05). Keep
 * {@code Agg.of(...)} for aggregate expressions: any aggregate makes the query grouped (R-QRY-08, D-27).
 *
 * <pre>{@code
 * // Wrong: the model never sees this.
 * (spec, joins, query, cb, phase) -> query.multiselect(withExtra(query, cb.upper(status)));
 * // Right: select the column through the SelectSet and derive the value per row.
 * .select(SelectSet.of(ID, STATUS))
 * .afterMap((view, row) -> view.setLabel(row.get(STATUS).toUpperCase(Locale.ROOT)))
 * }</pre>
 *
 * <p>Keep the phases consistent: a predicate or an INNER join added in {@code MODEL} but not in {@code PRIMARY_KEY}
 * makes primary-key-first paging return rows the caller filtered out. {@link ModelQuery#checkPhases} warns about it,
 * and throws {@code MQ2206} on a query with {@code primaryKeyFirst(...)} (R-PAG-15).
 * The query's own joins are all made before the customizer runs, and a join resolved here through {@code joins} is
 * shared by join key; but a path the query joined LEFT only because an {@code or(...)} or {@code not(...)} first
 * needed it is cached as LEFT, so resolving it here as INNER adds a second join to that path (R-FLT-10, D-26).
 *
 * @implSpec R-QRY-07, R-QRY-08, R-QRY-09, R-QRY-11
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
