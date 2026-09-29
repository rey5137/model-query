package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * The {@link Filters} implementation: a {@link ConditionGroup} over {@link ColumnField}s, plus the {@code WHERE}-only
 * operators, {@code exists} and {@code add}. It lives only while a {@code where} operator runs.
 */
final class FilterGroup<M> extends ConditionGroup<M, Filters<M>> implements Filters<M> {

    /** Inside an {@code exists}, its path: every column must sit on it or below it (R-FLT-11). */
    private final TableField<?, ?> scope;

    private FilterGroup(TableField<?, ?> scope) {
        super("Filters", "where");
        this.scope = scope;
    }

    /** Runs {@code operator} on an empty group and returns the filters it added, as an immutable list. */
    static <M> List<Filter> collect(UnaryOperator<Filters<M>> operator) {
        return collect(new FilterGroup<>(null), operator);
    }

    @Override
    Filters<M> self() {
        return this;
    }

    @Override
    FilterGroup<M> child() {
        return new FilterGroup<>(scope);
    }

    /** Inside an {@code exists}, a column must sit on its path or below it (R-FLT-11). */
    @Override
    void check(SelectField<M, ?> column) {
        if (scope != null && column instanceof ColumnField<?, ?, ?> c && !c.table().isAtOrBelow(scope)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1302, column + " sits on " + c.table().describe()
                    + ", outside the exists(...) path " + scope.describe() + "; use a column on that path or below it");
        }
    }

    // ---- equality and comparison

    @Override
    public <C> Filters<M> eq(ColumnField<M, ?, C> column, C value) {
        return super.eq(column, value);
    }

    @Override
    public <C> Filters<M> eq(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return super.eq(column, value);
    }

    @Override
    public <C> Filters<M> ne(ColumnField<M, ?, C> column, C value) {
        return super.ne(column, value);
    }

    @Override
    public <C> Filters<M> ne(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return super.ne(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gt(ColumnField<M, ?, C> column, C value) {
        return super.gt(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gt(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return super.gt(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, C value) {
        return super.gte(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return super.gte(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lt(ColumnField<M, ?, C> column, C value) {
        return super.lt(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lt(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return super.lt(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, C value) {
        return super.lte(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return super.lte(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> range(
            ColumnField<M, ?, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toExclusive) {
        return super.range(column, fromInclusive, toExclusive);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> between(
            ColumnField<M, ?, C> column, C fromInclusive, C toInclusive) {
        return super.between(column, fromInclusive, toInclusive);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> between(
            ColumnField<M, ?, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toInclusive) {
        return super.between(column, fromInclusive, toInclusive);
    }

    // ---- sets

    @Override
    public <C> Filters<M> in(ColumnField<M, ?, C> column, Collection<? extends C> values) {
        return super.in(column, values);
    }

    @Override
    public <C> Filters<M> in(ColumnField<M, ?, C> column, Optional<? extends Collection<? extends C>> values) {
        return super.in(column, values);
    }

    @Override
    public <C> Filters<M> notIn(ColumnField<M, ?, C> column, Collection<? extends C> values) {
        return super.notIn(column, values);
    }

    @Override
    public <C> Filters<M> notIn(ColumnField<M, ?, C> column, Optional<? extends Collection<? extends C>> values) {
        return super.notIn(column, values);
    }

    // ---- strings

    @Override
    public Filters<M> like(ColumnField<M, ?, String> column, String value, LikeMode mode) {
        return super.like(column, value, mode);
    }

    @Override
    public Filters<M> like(ColumnField<M, ?, String> column, Optional<String> value, LikeMode mode) {
        return super.like(column, value, mode);
    }

    @Override
    public Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, String value, LikeMode mode) {
        return super.likeIgnoreCase(column, value, mode);
    }

    @Override
    public Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, Optional<String> value, LikeMode mode) {
        return super.likeIgnoreCase(column, value, mode);
    }

    @Override
    public Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, String value) {
        return super.eqIgnoreCase(column, value);
    }

    @Override
    public Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, Optional<String> value) {
        return super.eqIgnoreCase(column, value);
    }

    // ---- nulls

    @Override
    public Filters<M> isNull(ColumnField<M, ?, ?> column) {
        return super.isNull(column);
    }

    @Override
    public Filters<M> isNotNull(ColumnField<M, ?, ?> column) {
        return super.isNotNull(column);
    }

    @Override
    public Filters<M> isNull(ColumnField<M, ?, ?> column, Optional<Boolean> isNull) {
        return super.isNull(column, isNull);
    }

    // ---- column against column

    @Override
    public <C> Filters<M> compare(ColumnField<M, ?, C> left, Op op, ColumnField<M, ?, C> right) {
        return super.compare(left, op, right);
    }

    // ---- correlated sub-queries

    @Override
    public Filters<M> exists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner) {
        return exists(path, inner, false);
    }

    @Override
    public Filters<M> exists(TableField<?, ?> path) {
        checkOpen();
        return record(exists(existsPath(path), List.of(), false));
    }

    @Override
    public Filters<M> notExists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner) {
        return exists(path, inner, true);
    }

    private Filters<M> exists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner, boolean negated) {
        List<Filter> group = nested(Objects.requireNonNull(inner, "inner"), new FilterGroup<>(existsPath(path)));
        return group.isEmpty() ? this : record(exists(path, group, negated)); // R-FLT-01
    }

    private static Filter exists(TableField<?, ?> path, List<Filter> inner, boolean negated) {
        return ctx -> {
            Predicate exists = ctx.exists(path, sub -> toPredicates(inner, sub));
            return Optional.of(negated ? ctx.cb().not(exists) : exists);
        };
    }

    /** {@code path}, checked to be a join, and inside an {@code exists} to sit on or below its path (R-FLT-11). */
    private TableField<?, ?> existsPath(TableField<?, ?> path) {
        checkOpen();
        Objects.requireNonNull(path, "path");
        if (path.rootEntity() != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1304,
                    "exists(...) needs a join path, not the " + path.describe());
        }
        if (scope != null && !path.isAtOrBelow(scope)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1302, "exists(...) on " + path.describe()
                    + " sits outside the enclosing exists(...) path " + scope.describe());
        }
        return path;
    }

    // ---- escape hatch

    @Override
    public Filters<M> add(BiFunction<JoinContext, CriteriaBuilder, Predicate> custom) {
        Objects.requireNonNull(custom, "custom");
        return record(ctx -> {
            Predicate predicate = custom.apply(ctx, ctx.cb());
            if (predicate == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1305,
                        "add(...): the custom predicate returned null; skip it explicitly with when(...)");
            }
            return Optional.of(predicate);
        });
    }
}
