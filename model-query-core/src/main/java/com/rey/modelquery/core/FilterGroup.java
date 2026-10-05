package com.rey.modelquery.core;

import com.rey.modelquery.core.Condition.Kind;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * The {@link Filters} implementation: a {@link ConditionGroup} over {@link ScalarField}s, plus the {@code WHERE}-only
 * operators, {@code exists} and {@code add}. It lives only while a {@code where} operator runs.
 */
final class FilterGroup<M> extends ConditionGroup<M, Filters<M>> implements Filters<M> {

    /** Inside a sub-select or an {@code exists}, its root or path: every column must sit on it or below it. */
    private final TableField<?, ?> scope;
    /**
     * The code a column outside {@link #scope} raises: {@code MQ1302} inside {@code exists}, {@code MQ1003} in a
     * sub-select.
     */

    private final MqCode scopeCode;
    /** Whether a lifted outer column is allowed here, which it is inside a correlation (R-FLT-17). */
    private final boolean allowLifted;
    /**
     * The entity the enclosing query is rooted at, so a correlation's {@link Outer} rejects a column of another root
     * at definition ({@code MQ1311}); {@code null} when the group was built without a root (R-FLT-17).
     */
    private final Class<?> rootEntity;

    private FilterGroup(TableField<?, ?> scope, MqCode scopeCode, boolean allowLifted, Class<?> rootEntity) {
        super("Filters", "where");
        this.scope = scope;
        this.scopeCode = scopeCode;
        this.allowLifted = allowLifted;
        this.rootEntity = rootEntity;
    }

    /** Runs {@code operator} on an empty group and returns the filters it added, as an immutable list. */
    static <M> List<Filter> collect(UnaryOperator<Filters<M>> operator) {
        return collect(new FilterGroup<>(null, null, false, null), operator);
    }

    /**
     * As {@link #collect(UnaryOperator)}, with {@code rootEntity} as the query's root entity, so a correlation built
     * here checks a lifted column against it (R-FLT-17).
     */
    static <M> List<Filter> collect(Class<?> rootEntity, UnaryOperator<Filters<M>> operator) {
        return collect(new FilterGroup<>(null, null, false, rootEntity), operator);
    }

    /** Collects a {@link SubSelect#where} over {@code root}: every filter column must start there (R-FLT-15). */
    static <S> List<Filter> collectWhere(TableField<?, ?> root, UnaryOperator<Filters<S>> filters) {
        TableField<?, ?> checked = Objects.requireNonNull(root, "root");
        return collect(new FilterGroup<>(checked, MqCode.MQ1003, false, checked.rootEntity()),
                Objects.requireNonNull(filters, "filters"));
    }

    @Override
    Filters<M> self() {
        return this;
    }

    @Override
    FilterGroup<M> child() {
        return new FilterGroup<>(scope, scopeCode, allowLifted, rootEntity);
    }

    /** A column must sit on the scope's path or below it: MQ1302 inside {@code exists}, MQ1003 in a sub-select. */
    @Override
    void check(SelectField<M, ?> column) {
        if (column instanceof ExpressionField<?, ?> expression) {
            // An expression's columns are checked as columns are (R-FLT-18): each of them, at any depth.
            for (ColumnField<?, ?, ?> read : expression.columns()) {
                checkColumn(read);
            }
        } else if (column instanceof ColumnField<?, ?, ?> plain) {
            checkColumn(plain);
        }
    }

    /** One column's scope check, shared by a plain filter and an expression's operands (R-FLT-18). */
    private void checkColumn(ColumnField<?, ?, ?> column) {
        if (scope == null) {
            return;
        }
        if (column.isLifted()) {
            if (allowLifted) {
                return; // the correlation's lifted outer column (R-FLT-17)
            }
            // A sub-select's own where has its own root, not the enclosing query's: lifting there would read the
            // wrong row, so reject it at definition rather than render it against the sub-select's context.
            throw new ModelQueryDefinitionException(scopeCode, column
                    + " is a lifted outer column, which is not allowed here; lift it inside exists(...) instead");
        }
        if (!column.table().isAtOrBelow(scope)) {
            throw new ModelQueryDefinitionException(scopeCode, scopeCode == MqCode.MQ1003
                    ? column + " sits on " + column.table().describe() + ", not on the sub-select's root "
                            + scope.describe()
                    : column + " sits on " + column.table().describe() + ", outside the exists(...) path "
                            + scope.describe() + "; use a column on that path or below it");
        }
    }

    // ---- equality and comparison

    @Override
    public <C> Filters<M> eq(ScalarField<M, C> column, C value) {
        return super.eq(column, value);
    }

    @Override
    public <C> Filters<M> eq(ScalarField<M, C> column, Optional<? extends C> value) {
        return super.eq(column, value);
    }

    @Override
    public <C> Filters<M> ne(ScalarField<M, C> column, C value) {
        return super.ne(column, value);
    }

    @Override
    public <C> Filters<M> ne(ScalarField<M, C> column, Optional<? extends C> value) {
        return super.ne(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gt(ScalarField<M, C> column, C value) {
        return super.gt(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gt(ScalarField<M, C> column, Optional<? extends C> value) {
        return super.gt(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gte(ScalarField<M, C> column, C value) {
        return super.gte(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gte(ScalarField<M, C> column, Optional<? extends C> value) {
        return super.gte(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lt(ScalarField<M, C> column, C value) {
        return super.lt(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lt(ScalarField<M, C> column, Optional<? extends C> value) {
        return super.lt(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lte(ScalarField<M, C> column, C value) {
        return super.lte(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lte(ScalarField<M, C> column, Optional<? extends C> value) {
        return super.lte(column, value);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> range(
            ScalarField<M, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toExclusive) {
        return super.range(column, fromInclusive, toExclusive);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> between(
            ScalarField<M, C> column, C fromInclusive, C toInclusive) {
        return super.between(column, fromInclusive, toInclusive);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> between(
            ScalarField<M, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toInclusive) {
        return super.between(column, fromInclusive, toInclusive);
    }

    // ---- sets

    @Override
    public <C> Filters<M> in(ScalarField<M, C> column, Collection<? extends C> values) {
        return super.in(column, values);
    }

    @Override
    public <C> Filters<M> in(ScalarField<M, C> column, Optional<? extends Collection<? extends C>> values) {
        return super.in(column, values);
    }

    @Override
    public <C> Filters<M> notIn(ScalarField<M, C> column, Collection<? extends C> values) {
        return super.notIn(column, values);
    }

    @Override
    public <C> Filters<M> notIn(ScalarField<M, C> column, Optional<? extends Collection<? extends C>> values) {
        return super.notIn(column, values);
    }

    // ---- sets over a sub-select

    @Override
    public <C> Filters<M> in(ScalarField<M, C> column, SubSelect<?, C> values) {
        checkSubSelect("in", column, values);
        return record(Condition.inSubSelect(Kind.IN_SUBSELECT, column, values),
                ctx -> Optional.of(ctx.inSubSelect(column, values, false)));
    }

    @Override
    public <C> Filters<M> notIn(ScalarField<M, C> column, SubSelect<?, C> values) {
        checkSubSelect("notIn", column, values);
        return record(Condition.inSubSelect(Kind.NOT_IN_SUBSELECT, column, values),
                ctx -> Optional.of(ctx.inSubSelect(column, values, true)));
    }

    /**
     * Checks the two sides of {@code in}/{@code notIn} over a sub-select before recording it: the same invariant
     * {@code C} is compile-time, and a converter that hides different attribute types is {@code MQ1001} here, as for
     * {@code compare} (R-FLT-15).
     */
    private void checkSubSelect(String operator, ScalarField<M, ?> column, SubSelect<?, ?> values) {
        check(Objects.requireNonNull(column, "column"));
        Objects.requireNonNull(values, "values");
        ColumnField<?, ?, ?> selected = values.column();
        if ((converted(column) != null || converted(selected) != null)
                && attributeType(column) != attributeType(selected)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1001, String.format(
                    "%s(%s, %s): the columns read attributes of type %s and %s, which a converter makes look alike",
                    operator, column, values, attributeType(column).getSimpleName(),
                    attributeType(selected).getSimpleName()));
        }
    }

    // ---- strings

    @Override
    public Filters<M> like(ScalarField<M, String> column, String value, LikeMode mode) {
        return super.like(column, value, mode);
    }

    @Override
    public Filters<M> like(ScalarField<M, String> column, Optional<String> value, LikeMode mode) {
        return super.like(column, value, mode);
    }

    @Override
    public Filters<M> likeIgnoreCase(ScalarField<M, String> column, String value, LikeMode mode) {
        return super.likeIgnoreCase(column, value, mode);
    }

    @Override
    public Filters<M> likeIgnoreCase(ScalarField<M, String> column, Optional<String> value, LikeMode mode) {
        return super.likeIgnoreCase(column, value, mode);
    }

    @Override
    public Filters<M> eqIgnoreCase(ScalarField<M, String> column, String value) {
        return super.eqIgnoreCase(column, value);
    }

    @Override
    public Filters<M> eqIgnoreCase(ScalarField<M, String> column, Optional<String> value) {
        return super.eqIgnoreCase(column, value);
    }

    // ---- nulls

    @Override
    public Filters<M> isNull(ScalarField<M, ?> column) {
        return super.isNull(column);
    }

    @Override
    public Filters<M> isNotNull(ScalarField<M, ?> column) {
        return super.isNotNull(column);
    }

    @Override
    public Filters<M> isNull(ScalarField<M, ?> column, Optional<Boolean> isNull) {
        return super.isNull(column, isNull);
    }

    // ---- column against column

    @Override
    public <C> Filters<M> compare(ScalarField<M, C> left, Op op, ScalarField<M, C> right) {
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
        TableField<?, ?> checked = existsPath(path);
        return record(Condition.exists(Kind.EXISTS, checked, List.of()), exists(checked, List.of(), false));
    }

    @Override
    public Filters<M> notExists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner) {
        return exists(path, inner, true);
    }

    private Filters<M> exists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner, boolean negated) {
        List<Filter> group = nested(Objects.requireNonNull(inner, "inner"),
                new FilterGroup<>(existsPath(path), MqCode.MQ1302, allowLifted, rootEntity));
        if (group.isEmpty()) {
            return this; // R-FLT-01
        }
        return record(Condition.exists(negated ? Kind.NOT_EXISTS : Kind.EXISTS, path, conditions(group)),
                exists(path, group, negated));
    }

    private static Filter exists(TableField<?, ?> path, List<Filter> inner, boolean negated) {
        return ctx -> {
            Predicate exists = ctx.exists(path, sub -> toPredicates(inner, sub));
            return Optional.of(negated ? ctx.cb().not(exists) : exists);
        };
    }

    // ---- correlated exists over a sub-select (R-FLT-17)

    @Override
    public <S> Filters<M> exists(SubSelect<S, ?> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation) {
        return existsSub(rows, correlation, false);
    }

    @Override
    public <S> Filters<M> notExists(SubSelect<S, ?> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation) {
        return existsSub(rows, correlation, true);
    }

    private <S> Filters<M> existsSub(SubSelect<S, ?> rows,
            BiFunction<Filters<S>, Outer<M, S>, Filters<S>> correlation, boolean negated) {
        checkOpen();
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(correlation, "correlation");
        // The inner group accepts the sub-select's root columns and the lifted outer columns (R-FLT-17). Its root
        // entity is the sub-select's own, so a nested correlation here lifts against the sub-select's root.
        var inner = new FilterGroup<S>(rows.root(), MqCode.MQ1003, true, rows.rootEntity());
        List<Filter> group;
        beginNesting();
        try {
            group = ConditionGroup.collect(inner, correlation, new Outer<M, S>(rootEntity));
        } finally {
            endNesting();
        }
        List<Condition> conditions = ConditionGroup.conditions(group);
        if (!anyLifted(conditions)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1309, "exists(...) over " + rows
                    + ": its correlation lifts no outer column; use in(...) or a plain exists(path, ...) instead");
        }
        return record(Condition.existsSubSelect(
                        negated ? Kind.NOT_EXISTS_SUBSELECT : Kind.EXISTS_SUBSELECT, rows, conditions),
                ctx -> Optional.of(ctx.existsSubSelect(rows, group, negated)));
    }

    /** Whether any condition, at any depth, reads a lifted outer column (R-FLT-17, MQ1309). */
    private static boolean anyLifted(List<Condition> conditions) {
        for (Condition condition : conditions) {
            if (lifted(condition.column().orElse(null)) || lifted(condition.right().orElse(null))
                    || anyLifted(condition.children())) {
                return true;
            }
        }
        return false;
    }

    private static boolean lifted(SelectField<?, ?> field) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            return column.isLifted();
        }
        // A lifted column inside an expression counts for MQ1309 (R-FLT-18).
        return field instanceof ExpressionField<?, ?> expression
                && expression.columns().stream().anyMatch(ColumnField::isLifted);
    }

    /** {@code path}, checked to be a join, and inside a scope to sit on or below it (R-FLT-11). */
    private TableField<?, ?> existsPath(TableField<?, ?> path) {
        checkOpen();
        Objects.requireNonNull(path, "path");
        if (path.rootEntity() != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1304,
                    "exists(...) needs a join path, not the " + path.describe());
        }
        if (scope != null && !path.isAtOrBelow(scope)) {
            throw new ModelQueryDefinitionException(scopeCode == null ? MqCode.MQ1302 : scopeCode,
                    "exists(...) on " + path.describe() + " sits outside the enclosing scope "
                            + scope.describe());
        }
        return path;
    }

    // ---- escape hatch

    @Override
    public Filters<M> add(BiFunction<JoinContext, CriteriaBuilder, Predicate> custom) {
        return addCustom(Condition.custom(null), custom);
    }

    @Override
    public Filters<M> add(String label, BiFunction<JoinContext, CriteriaBuilder, Predicate> custom) {
        checkOpen();
        if (label == null || label.isBlank()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1301, "add(label, ...) received a "
                    + (label == null ? "null" : "blank") + " label; name the custom filter, or use add(...)");
        }
        return addCustom(Condition.custom(label), custom);
    }

    private Filters<M> addCustom(Condition condition, BiFunction<JoinContext, CriteriaBuilder, Predicate> custom) {
        Objects.requireNonNull(custom, "custom");
        return record(condition, ctx -> {
            Predicate predicate = custom.apply(ctx, ctx.cb());
            if (predicate == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1305,
                        "add(...): the custom predicate returned null; skip it explicitly with when(...)");
            }
            return Optional.of(predicate);
        });
    }
}
