package com.rey.modelquery.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The {@link Having} implementation: a {@link ConditionGroup} over {@link AggregateField}s. It lives only while a
 * {@code having} operator runs, and notes every aggregate it is given, for the build-time checks of R-AGG-02.
 */
final class HavingGroup<M> extends ConditionGroup<M, Having<M>> implements Having<M> {

    /** What a {@code having} operator recorded: its filters, and every aggregate they name. Immutable (INV-9). */
    record Clause(List<Filter> filters, List<AggregateField<?, ?>> aggregates) {}

    /** Shared with every nested group of one {@code having} operator. */
    private final List<AggregateField<?, ?>> aggregates;

    private HavingGroup(List<AggregateField<?, ?>> aggregates) {
        super("Having", "having");
        this.aggregates = aggregates;
    }

    /** Runs {@code operator} on an empty group and returns what it recorded. */
    static <M> Clause collect(UnaryOperator<Having<M>> operator) {
        var aggregates = new ArrayList<AggregateField<?, ?>>();
        List<Filter> filters = collect(new HavingGroup<>(aggregates), operator);
        return new Clause(filters, List.copyOf(aggregates));
    }

    @Override
    Having<M> self() {
        return this;
    }

    @Override
    HavingGroup<M> child() {
        return new HavingGroup<>(aggregates);
    }

    @Override
    void check(SelectField<M, ?> column) {
        if (column instanceof AggregateField<?, ?> aggregate) {
            aggregates.add(aggregate);
        }
    }

    @Override
    public <C> Having<M> eq(AggregateField<M, C> aggregate, C value) {
        return super.eq(aggregate, value);
    }

    @Override
    public <C> Having<M> eq(AggregateField<M, C> aggregate, Optional<? extends C> value) {
        return super.eq(aggregate, value);
    }

    @Override
    public <C> Having<M> ne(AggregateField<M, C> aggregate, C value) {
        return super.ne(aggregate, value);
    }

    @Override
    public <C> Having<M> ne(AggregateField<M, C> aggregate, Optional<? extends C> value) {
        return super.ne(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> gt(AggregateField<M, C> aggregate, C value) {
        return super.gt(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> gt(AggregateField<M, C> aggregate, Optional<? extends C> value) {
        return super.gt(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> gte(AggregateField<M, C> aggregate, C value) {
        return super.gte(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> gte(
            AggregateField<M, C> aggregate, Optional<? extends C> value) {
        return super.gte(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> lt(AggregateField<M, C> aggregate, C value) {
        return super.lt(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> lt(AggregateField<M, C> aggregate, Optional<? extends C> value) {
        return super.lt(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> lte(AggregateField<M, C> aggregate, C value) {
        return super.lte(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> lte(
            AggregateField<M, C> aggregate, Optional<? extends C> value) {
        return super.lte(aggregate, value);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> range(
            AggregateField<M, C> aggregate, Optional<? extends C> fromInclusive, Optional<? extends C> toExclusive) {
        return super.range(aggregate, fromInclusive, toExclusive);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> between(
            AggregateField<M, C> aggregate, C fromInclusive, C toInclusive) {
        return super.between(aggregate, fromInclusive, toInclusive);
    }

    @Override
    public <C extends Comparable<? super C>> Having<M> between(
            AggregateField<M, C> aggregate, Optional<? extends C> fromInclusive, Optional<? extends C> toInclusive) {
        return super.between(aggregate, fromInclusive, toInclusive);
    }

    @Override
    public <C> Having<M> in(AggregateField<M, C> aggregate, Collection<? extends C> values) {
        return super.in(aggregate, values);
    }

    @Override
    public <C> Having<M> in(AggregateField<M, C> aggregate, Optional<? extends Collection<? extends C>> values) {
        return super.in(aggregate, values);
    }

    @Override
    public <C> Having<M> notIn(AggregateField<M, C> aggregate, Collection<? extends C> values) {
        return super.notIn(aggregate, values);
    }

    @Override
    public <C> Having<M> notIn(AggregateField<M, C> aggregate, Optional<? extends Collection<? extends C>> values) {
        return super.notIn(aggregate, values);
    }

    @Override
    public Having<M> like(AggregateField<M, String> aggregate, String value, LikeMode mode) {
        return super.like(aggregate, value, mode);
    }

    @Override
    public Having<M> like(AggregateField<M, String> aggregate, Optional<String> value, LikeMode mode) {
        return super.like(aggregate, value, mode);
    }

    @Override
    public Having<M> likeIgnoreCase(AggregateField<M, String> aggregate, String value, LikeMode mode) {
        return super.likeIgnoreCase(aggregate, value, mode);
    }

    @Override
    public Having<M> likeIgnoreCase(AggregateField<M, String> aggregate, Optional<String> value, LikeMode mode) {
        return super.likeIgnoreCase(aggregate, value, mode);
    }

    @Override
    public Having<M> eqIgnoreCase(AggregateField<M, String> aggregate, String value) {
        return super.eqIgnoreCase(aggregate, value);
    }

    @Override
    public Having<M> eqIgnoreCase(AggregateField<M, String> aggregate, Optional<String> value) {
        return super.eqIgnoreCase(aggregate, value);
    }

    @Override
    public Having<M> isNull(AggregateField<M, ?> aggregate) {
        return super.isNull(aggregate);
    }

    @Override
    public Having<M> isNotNull(AggregateField<M, ?> aggregate) {
        return super.isNotNull(aggregate);
    }

    @Override
    public Having<M> isNull(AggregateField<M, ?> aggregate, Optional<Boolean> isNull) {
        return super.isNull(aggregate, isNull);
    }

    @Override
    public <C> Having<M> compare(AggregateField<M, C> left, Op op, AggregateField<M, C> right) {
        return super.compare(left, op, right);
    }
}
