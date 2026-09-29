package com.rey.modelquery.core;

import java.util.Collection;
import java.util.Optional;

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
 * @implSpec api/12 §1, R-FLT-01..08, R-FLT-13, R-FLT-14
 */
@Incubating
public interface Filters<M> {

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
     * these" (R-FLT-02); pass {@code Optional.empty()} to skip. A {@code null} element throws {@code MQ1301}.
     */
    <C> Filters<M> in(ColumnField<M, ?, C> column, Collection<? extends C> values);

    /** {@link #in(ColumnField, Collection)}, skipped when {@code values} is empty. */
    <C> Filters<M> in(ColumnField<M, ?, C> column, Optional<? extends Collection<? extends C>> values);

    /**
     * {@code column NOT IN (values) OR column IS NULL}: NULL rows match (R-FLT-04). An empty collection matches every
     * row (R-FLT-02). A {@code null} element throws {@code MQ1301}.
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
}
