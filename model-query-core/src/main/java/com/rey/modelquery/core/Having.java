package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The {@code HAVING} builder a {@link ModelQuery.Builder#having} operator receives: the {@link Filters} DSL over
 * {@link AggregateField}s, with the same skip and {@code Optional} semantics. Every filter added to it is ANDed; each
 * method adds its filter and returns this builder, for chaining.
 *
 * <p>Only {@code AggregateField}s are accepted, so a plain column in {@code having} does not compile, as an aggregate
 * in {@code where} does not (R-AGG-06). A nested {@code Having} ({@code or} branch, {@code not}, {@code when},
 * {@code apply}) is valid only inside its own operator, else {@code MQ1303}.
 *
 * @param <M> the model the query maps to
 * @implSpec R-AGG-06
 */
@Incubating
public sealed interface Having<M> permits HavingGroup {

    /** {@code aggregate = value}. */
    <C> Having<M> eq(AggregateField<M, C> aggregate, C value);

    /** {@code aggregate = value}, skipped when {@code value} is empty. */
    <C> Having<M> eq(AggregateField<M, C> aggregate, Optional<? extends C> value);

    /** {@code aggregate <> value OR aggregate IS NULL}, as {@link Filters#ne(ScalarField, Object)}. */
    <C> Having<M> ne(AggregateField<M, C> aggregate, C value);

    /** {@link #ne(AggregateField, Object)}, skipped when {@code value} is empty. */
    <C> Having<M> ne(AggregateField<M, C> aggregate, Optional<? extends C> value);

    /** {@code aggregate > value}. */
    <C extends Comparable<? super C>> Having<M> gt(AggregateField<M, C> aggregate, C value);

    /** {@code aggregate > value}, skipped when {@code value} is empty. */
    <C extends Comparable<? super C>> Having<M> gt(AggregateField<M, C> aggregate, Optional<? extends C> value);

    /** {@code aggregate >= value}. */
    <C extends Comparable<? super C>> Having<M> gte(AggregateField<M, C> aggregate, C value);

    /** {@code aggregate >= value}, skipped when {@code value} is empty. */
    <C extends Comparable<? super C>> Having<M> gte(AggregateField<M, C> aggregate, Optional<? extends C> value);

    /** {@code aggregate < value}. */
    <C extends Comparable<? super C>> Having<M> lt(AggregateField<M, C> aggregate, C value);

    /** {@code aggregate < value}, skipped when {@code value} is empty. */
    <C extends Comparable<? super C>> Having<M> lt(AggregateField<M, C> aggregate, Optional<? extends C> value);

    /** {@code aggregate <= value}. */
    <C extends Comparable<? super C>> Having<M> lte(AggregateField<M, C> aggregate, C value);

    /** {@code aggregate <= value}, skipped when {@code value} is empty. */
    <C extends Comparable<? super C>> Having<M> lte(AggregateField<M, C> aggregate, Optional<? extends C> value);

    /** The half-open range, as {@link Filters#range}; each bound is skipped on its own when empty. */
    <C extends Comparable<? super C>> Having<M> range(
            AggregateField<M, C> aggregate, Optional<? extends C> fromInclusive, Optional<? extends C> toExclusive);

    /** {@code aggregate BETWEEN fromInclusive AND toInclusive}. */
    <C extends Comparable<? super C>> Having<M> between(
            AggregateField<M, C> aggregate, C fromInclusive, C toInclusive);

    /** The closed range, as {@link Filters#between(ScalarField, Optional, Optional)}; each bound skipped when empty. */
    <C extends Comparable<? super C>> Having<M> between(
            AggregateField<M, C> aggregate, Optional<? extends C> fromInclusive, Optional<? extends C> toInclusive);

    /** {@code aggregate IN (values)}; an empty collection matches no group (R-FLT-02). */
    <C> Having<M> in(AggregateField<M, C> aggregate, Collection<? extends C> values);

    /** {@link #in(AggregateField, Collection)}, skipped when {@code values} is empty. */
    <C> Having<M> in(AggregateField<M, C> aggregate, Optional<? extends Collection<? extends C>> values);

    /** {@code aggregate NOT IN (values) OR aggregate IS NULL}; an empty collection matches every group (R-FLT-02). */
    <C> Having<M> notIn(AggregateField<M, C> aggregate, Collection<? extends C> values);

    /** {@link #notIn(AggregateField, Collection)}, skipped when {@code values} is empty. */
    <C> Having<M> notIn(AggregateField<M, C> aggregate, Optional<? extends Collection<? extends C>> values);

    /** {@code aggregate LIKE pattern}, as {@link Filters#like(ScalarField, String, LikeMode)} (R-FLT-06). */
    Having<M> like(AggregateField<M, String> aggregate, String value, LikeMode mode);

    /** {@link #like(AggregateField, String, LikeMode)}, skipped when {@code value} is empty. */
    Having<M> like(AggregateField<M, String> aggregate, Optional<String> value, LikeMode mode);

    /** {@code lower(aggregate) LIKE pattern}, as {@link Filters#likeIgnoreCase(ScalarField, String, LikeMode)}. */
    Having<M> likeIgnoreCase(AggregateField<M, String> aggregate, String value, LikeMode mode);

    /** {@link #likeIgnoreCase(AggregateField, String, LikeMode)}, skipped when {@code value} is empty. */
    Having<M> likeIgnoreCase(AggregateField<M, String> aggregate, Optional<String> value, LikeMode mode);

    /** {@code lower(aggregate) = value}, the value lower-cased with {@code Locale.ROOT}. */
    Having<M> eqIgnoreCase(AggregateField<M, String> aggregate, String value);

    /** {@link #eqIgnoreCase(AggregateField, String)}, skipped when {@code value} is empty. */
    Having<M> eqIgnoreCase(AggregateField<M, String> aggregate, Optional<String> value);

    /** {@code aggregate IS NULL}, such as a {@code sum} over no rows (R-AGG-04). */
    Having<M> isNull(AggregateField<M, ?> aggregate);

    /** {@code aggregate IS NOT NULL}. */
    Having<M> isNotNull(AggregateField<M, ?> aggregate);

    /** A tri-state flag: {@code true} is {@link #isNull}, {@code false} is {@link #isNotNull}, empty skips. */
    Having<M> isNull(AggregateField<M, ?> aggregate, Optional<Boolean> isNull);

    /** {@code left op right}, aggregate against aggregate, with plain SQL comparison semantics. */
    <C> Having<M> compare(AggregateField<M, C> left, Op op, AggregateField<M, C> right);

    /** {@code (first) OR (second)}, skipped as {@link Filters#or(UnaryOperator, UnaryOperator)} is (R-FLT-01). */
    Having<M> or(UnaryOperator<Having<M>> first, UnaryOperator<Having<M>> second);

    /** {@code (first) OR (second) OR (third)}, skipped as the two-branch {@code or} is (R-FLT-01). */
    Having<M> or(UnaryOperator<Having<M>> first, UnaryOperator<Having<M>> second, UnaryOperator<Having<M>> third);

    /**
     * {@code (branch1) OR (branch2) ...} over a list, as {@link Filters#or(List)} renders it. An <b>empty list</b>
     * renders {@code FALSE} and matches no group (D-92): an empty "allowed tenants" list must return no rows, not
     * every row. A non-empty list whose branches were all skipped is skipped (R-FLT-01); a single branch is that
     * branch.
     */
    Having<M> or(List<? extends UnaryOperator<Having<M>>> branches);

    /** {@code NOT (group)}, plain SQL negation, skipped when every filter in the group was skipped (R-FLT-01). */
    Having<M> not(UnaryOperator<Having<M>> group);

    /** The filters {@code group} adds, ANDed into this builder when {@code condition} holds; else nothing. */
    Having<M> when(boolean condition, UnaryOperator<Having<M>> group);

    /** The filters {@code fragment} adds, ANDed into this builder: a way to reuse a shared fragment. */
    Having<M> apply(UnaryOperator<Having<M>> fragment);
}
