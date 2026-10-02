package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * The {@code WHERE} builder a {@link ModelQuery.Builder#where} operator receives: every filter added to it is ANDed.
 * Each method adds its filter to this builder and returns it, for chaining.
 *
 * <p>A method taking a value has two forms. The value form always applies, and a {@code null} value throws
 * {@code MQ1301}, because "no filter" must be explicit (P-3). The {@code Optional} form skips the filter when the
 * value is empty, and a skipped filter joins nothing (R-FLT-03). Every value is a bind parameter (R-FLT-08).
 *
 * <p>Only {@link ColumnField}s are accepted, so an aggregate in {@code where} does not compile (R-COL-06).
 *
 * @param <M> the model the query maps to
 * <p>A nested {@code Filters} (an {@code or} branch, a {@code not}, {@code when} or {@code apply} group, an
 * {@code exists} inner group) is valid only inside its own operator, and the enclosing one cannot be used while it
 * runs: add the nested filters to the {@code Filters} the operator receives. Either misuse throws {@code MQ1303}.
 *
 * @implSpec api/12 §1, R-FLT-01..08, R-FLT-10, R-FLT-11, R-FLT-13, R-FLT-14
 */
@Incubating
public sealed interface Filters<M> permits FilterGroup {

    /** {@code column = value}. */
    <C> Filters<M> eq(ColumnField<M, ?, C> column, C value);

    /** {@code column = value}, skipped when {@code value} is empty. */
    <C> Filters<M> eq(ColumnField<M, ?, C> column, Optional<? extends C> value);

    /** {@code column <> value OR column IS NULL}: NULL rows match (R-FLT-04). Add {@link #isNotNull} to drop them. */
    <C> Filters<M> ne(ColumnField<M, ?, C> column, C value);

    /** {@link #ne(ColumnField, Object)}, skipped when {@code value} is empty. */
    <C> Filters<M> ne(ColumnField<M, ?, C> column, Optional<? extends C> value);

    /** {@code column > value}. */
    <C extends Comparable<? super C>> Filters<M> gt(ColumnField<M, ?, C> column, C value);

    /** {@code column > value}, skipped when {@code value} is empty. */
    <C extends Comparable<? super C>> Filters<M> gt(ColumnField<M, ?, C> column, Optional<? extends C> value);

    /** {@code column >= value}. */
    <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, C value);

    /** {@code column >= value}, skipped when {@code value} is empty. */
    <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, Optional<? extends C> value);

    /** {@code column < value}. */
    <C extends Comparable<? super C>> Filters<M> lt(ColumnField<M, ?, C> column, C value);

    /** {@code column < value}, skipped when {@code value} is empty. */
    <C extends Comparable<? super C>> Filters<M> lt(ColumnField<M, ?, C> column, Optional<? extends C> value);

    /** {@code column <= value}. */
    <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, C value);

    /** {@code column <= value}, skipped when {@code value} is empty. */
    <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, Optional<? extends C> value);

    /**
     * The half-open range {@code column >= fromInclusive AND column < toExclusive}. Each bound is skipped on its own
     * when empty; with both empty the filter is skipped.
     */
    <C extends Comparable<? super C>> Filters<M> range(
            ColumnField<M, ?, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toExclusive);

    /** {@code column BETWEEN fromInclusive AND toInclusive}. */
    <C extends Comparable<? super C>> Filters<M> between(ColumnField<M, ?, C> column, C fromInclusive, C toInclusive);

    /**
     * {@code column >= fromInclusive AND column <= toInclusive}. Each bound is skipped on its own when empty; with
     * both empty the filter is skipped.
     */
    <C extends Comparable<? super C>> Filters<M> between(
            ColumnField<M, ?, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toInclusive);

    /**
     * {@code column IN (values)}. An empty collection matches no row, because an empty selection means "none of
     * these" (R-FLT-02); pass {@code Optional.empty()} to skip. A {@code null} element throws {@code MQ1301}. A list
     * longer than the database's IN-list limit renders as an OR of {@code IN} chunks; one with more values than a
     * statement binds throws {@code MQ1306} when the query is built (R-FLT-09).
     */
    <C> Filters<M> in(ColumnField<M, ?, C> column, Collection<? extends C> values);

    /** {@link #in(ColumnField, Collection)}, skipped when {@code values} is empty. */
    <C> Filters<M> in(ColumnField<M, ?, C> column, Optional<? extends Collection<? extends C>> values);

    /**
     * {@code column NOT IN (values) OR column IS NULL}: NULL rows match (R-FLT-04). An empty collection matches every
     * row (R-FLT-02). A {@code null} element throws {@code MQ1301}. A long list renders as an AND of {@code NOT IN}
     * chunks, still ORed with {@code IS NULL}, and throws {@code MQ1306} as {@code in} does (R-FLT-09).
     */
    <C> Filters<M> notIn(ColumnField<M, ?, C> column, Collection<? extends C> values);

    /** {@link #notIn(ColumnField, Collection)}, skipped when {@code values} is empty. */
    <C> Filters<M> notIn(ColumnField<M, ?, C> column, Optional<? extends Collection<? extends C>> values);

    /** {@code column LIKE pattern ESCAPE '\'}, the pattern built from {@code value} by {@code mode} (R-FLT-06). */
    Filters<M> like(ColumnField<M, ?, String> column, String value, LikeMode mode);

    /** {@link #like(ColumnField, String, LikeMode)}, skipped when {@code value} is empty. */
    Filters<M> like(ColumnField<M, ?, String> column, Optional<String> value, LikeMode mode);

    /**
     * {@code lower(column) LIKE pattern}, the pattern lower-cased with {@code Locale.ROOT} (R-FLT-07). Fast only with a
     * functional index on {@code lower(column)}; plain {@code like} follows the column's collation instead.
     */
    Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, String value, LikeMode mode);

    /** {@link #likeIgnoreCase(ColumnField, String, LikeMode)}, skipped when {@code value} is empty. */
    Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, Optional<String> value, LikeMode mode);

    /** {@code lower(column) = value}, the value lower-cased with {@code Locale.ROOT}. */
    Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, String value);

    /** {@link #eqIgnoreCase(ColumnField, String)}, skipped when {@code value} is empty. */
    Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, Optional<String> value);

    /** {@code column IS NULL}. */
    Filters<M> isNull(ColumnField<M, ?, ?> column);

    /** {@code column IS NOT NULL}. */
    Filters<M> isNotNull(ColumnField<M, ?, ?> column);

    /**
     * A tri-state request flag: {@code true} is {@link #isNull(ColumnField)}, {@code false} is
     * {@link #isNotNull(ColumnField)}, empty skips the filter.
     */
    Filters<M> isNull(ColumnField<M, ?, ?> column, Optional<Boolean> isNull);

    /**
     * {@code left op right}, column against column. This is plain SQL comparison: a row where either side is NULL
     * never matches, {@link Op#NE} included.
     */
    <C> Filters<M> compare(ColumnField<M, ?, C> left, Op op, ColumnField<M, ?, C> right);

    /**
     * {@code (branch1) OR (branch2) ...}, each branch an AND group. A branch whose filters were all skipped is dropped,
     * and with every branch dropped the {@code or} is skipped rather than matching nothing (R-FLT-01). A join first
     * needed inside a branch is LEFT, so a row without the joined row can still match another branch; a path joined
     * INNER elsewhere in the query keeps that join (R-FLT-10). Two or three branches take this overload or the next;
     * a branch list built at run time takes {@link #or(List)}.
     */
    Filters<M> or(UnaryOperator<Filters<M>> first, UnaryOperator<Filters<M>> second);

    /** {@code (first) OR (second) OR (third)}, as {@link #or(UnaryOperator, UnaryOperator)} renders it. */
    Filters<M> or(UnaryOperator<Filters<M>> first, UnaryOperator<Filters<M>> second, UnaryOperator<Filters<M>> third);

    /**
     * {@code (branch1) OR (branch2) ...} over a list, each branch dropped or kept as
     * {@link #or(UnaryOperator, UnaryOperator)} does. Three cases:
     * <ul>
     *   <li>an <b>empty list</b> renders {@code FALSE} and matches no row, as {@code in(col, List.of())} does
     *       (R-FLT-02, D-92): an empty "allowed tenants" list must return no rows, not every row;</li>
     *   <li>a <b>non-empty list whose branches were all skipped</b> (every filter in them skipped through
     *       {@code Optional.empty()}) is itself skipped (R-FLT-01);</li>
     *   <li>a <b>single branch</b> is that branch's AND group.</li>
     * </ul>
     */
    Filters<M> or(List<? extends UnaryOperator<Filters<M>>> branches);

    /**
     * {@code NOT (group)}, plain SQL negation (R-FLT-05): a row where the group is UNKNOWN, because a column it reads
     * is NULL, matches neither the group nor its negation. Skipped when every filter in the group was skipped
     * (R-FLT-01); its joins resolve as they do inside {@link #or} (R-FLT-10).
     */
    Filters<M> not(UnaryOperator<Filters<M>> group);

    /** The filters {@code group} adds, ANDed into this builder when {@code condition} holds; else nothing. */
    Filters<M> when(boolean condition, UnaryOperator<Filters<M>> group);

    /** The filters {@code fragment} adds, ANDed into this builder: a way to reuse a shared fragment. */
    Filters<M> apply(UnaryOperator<Filters<M>> fragment);

    /**
     * {@code EXISTS} a row of {@code path}, correlated to the query's root (inside another {@code exists}, to that
     * one's path), for which every filter of {@code inner} holds (R-FLT-11). It joins nothing on the outer query, so
     * it multiplies no row (R-FLT-12). Columns in {@code inner} must sit on {@code path} or below it, else
     * {@code MQ1302}; an alias and an {@code on(...)} condition on {@code path} are kept. Skipped when every filter
     * of {@code inner} was skipped (R-FLT-01); use {@link #exists(TableField)} for "has at least one". A nested
     * {@code exists} on exactly the enclosing path tests the same child row, not another row of that path.
     *
     * @throws ModelQueryDefinitionException {@code MQ1302} for a column of {@code inner} outside {@code path}
     * @throws ModelQueryDefinitionException {@code MQ1304} when {@code path} is a root rather than a join
     */
    Filters<M> exists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner);

    /** {@code EXISTS} a row of {@code path}, correlated to the query's root: "has at least one" (R-FLT-11). */
    Filters<M> exists(TableField<?, ?> path);

    /**
     * {@code NOT EXISTS} a row of {@code path} for which every filter of {@code inner} holds; the rules of
     * {@link #exists(TableField, UnaryOperator)} apply, including the skip when every inner filter was skipped.
     */
    Filters<M> notExists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner);

    /**
     * The escape hatch: a predicate built by {@code custom}, ANDed like any other filter. It runs once per query
     * build with that build's {@link JoinContext} and {@code CriteriaBuilder} (D-24), so resolve joins through
     * {@link TableField#resolve} or {@link ColumnField#path} with that context to share the query's joins; inside
     * {@link #or} or {@link #not} they resolve as there (R-FLT-10), and inside {@link #exists} the context is the
     * sub-query's. Values should be bind parameters, never concatenated into SQL (R-FLT-08).
     *
     * <p>{@code custom} must return a predicate: returning {@code null} throws {@code MQ1305} when the query is built,
     * because by then a skip could no longer promise to create no join (R-FLT-03). Skip explicitly with
     * {@link #when}.
     */
    Filters<M> add(BiFunction<JoinContext, CriteriaBuilder, Predicate> custom);
}
