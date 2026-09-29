package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * The {@link Filters} implementation: an AND group that records one {@link Filter} per filter added. It lives only
 * while a {@code where} operator runs; {@link #collect} freezes what it recorded into an immutable list, which is
 * what a {@link ModelQuery} keeps (INV-9). The group is closed once {@code collect} returns, so a reference kept past
 * the operator fails loudly instead of adding filters nobody reads (D-23). A nested group ({@code or} branch,
 * {@code not}, {@code when}, {@code apply}, {@code exists}) is collected the same way.
 */
final class FilterGroup<M> implements Filters<M> {

    private static final char ESCAPE = '\\';

    private final List<Filter> filters = new ArrayList<>();
    /** Inside an {@code exists}, its path: every column must sit on it or below it (R-FLT-11). */
    private final TableField<?, ?> scope;
    private boolean closed;
    /** Set while a nested group is collected, so a filter meant for the branch cannot land here by mistake. */
    private boolean nesting;

    private FilterGroup(TableField<?, ?> scope) {
        this.scope = scope;
    }

    /** Runs {@code operator} on an empty group and returns the filters it added, as an immutable list. */
    static <M> List<Filter> collect(UnaryOperator<Filters<M>> operator) {
        return collect(operator, null);
    }

    private static <M> List<Filter> collect(UnaryOperator<Filters<M>> operator, TableField<?, ?> scope) {
        var group = new FilterGroup<M>(scope);
        // Every filter added to the group counts, so a statement-style operator that ignores a return value
        // loses nothing; the operator's own result carries no extra information.
        try {
            operator.apply(group);
            return List.copyOf(group.filters);
        } finally {
            group.closed = true;
        }
    }

    /**
     * The predicates of {@code filters} against one build's joins, in order, leaving out filters that hold for every
     * row. {@code or} and {@code not} render last: an INNER join the rest of the group needs then already exists for
     * them to reuse (R-FLT-10), where rendering them first would join the path LEFT and the rest would join it again.
     */
    static List<Predicate> toPredicates(List<Filter> filters, JoinContext ctx) {
        var rendered = new ArrayList<Optional<Predicate>>(filters.size());
        for (Filter filter : filters) {
            rendered.add(filter instanceof LeftJoining ? null : filter.toPredicate(ctx));
        }
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i) instanceof LeftJoining filter) {
                rendered.set(i, filter.toPredicate(ctx));
            }
        }
        var predicates = new ArrayList<Predicate>();
        rendered.forEach(predicate -> predicate.ifPresent(predicates::add));
        return predicates;
    }

    /** An {@code or} or {@code not}: the joins it is the first to need resolve as LEFT (R-FLT-10). */
    private record LeftJoining(Filter filter) implements Filter {
        @Override
        public Optional<Predicate> toPredicate(JoinContext ctx) {
            return ctx.leftJoining(() -> filter.toPredicate(ctx));
        }
    }

    // ---- equality and comparison

    @Override
    public <C> Filters<M> eq(ColumnField<M, ?, C> column, C value) {
        C v = required(column, "eq", value);
        return add(ctx -> Optional.of(ctx.cb().equal(column.path(ctx), v)));
    }

    @Override
    public <C> Filters<M> eq(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return present(column, value) ? eq(column, value.get()) : this;
    }

    @Override
    public <C> Filters<M> ne(ColumnField<M, ?, C> column, C value) {
        C v = required(column, "ne", value);
        return add(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Path<C> path = column.path(ctx);
            // Plain SQL <> would drop NULL rows, which a report's "not X" means to keep (R-FLT-04). Rendered for
            // every column: a non-null attribute can still read NULL through a LEFT join.
            return Optional.of(cb.or(cb.notEqual(path, v), cb.isNull(path)));
        });
    }

    @Override
    public <C> Filters<M> ne(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return present(column, value) ? ne(column, value.get()) : this;
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gt(ColumnField<M, ?, C> column, C value) {
        C v = required(column, "gt", value);
        return add(ctx -> Optional.of(ctx.cb().greaterThan(column.path(ctx), v)));
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gt(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return present(column, value) ? gt(column, value.get()) : this;
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, C value) {
        C v = required(column, "gte", value);
        return add(ctx -> Optional.of(ctx.cb().greaterThanOrEqualTo(column.path(ctx), v)));
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> gte(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return present(column, value) ? gte(column, value.get()) : this;
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lt(ColumnField<M, ?, C> column, C value) {
        C v = required(column, "lt", value);
        return add(ctx -> Optional.of(ctx.cb().lessThan(column.path(ctx), v)));
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lt(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return present(column, value) ? lt(column, value.get()) : this;
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, C value) {
        C v = required(column, "lte", value);
        return add(ctx -> Optional.of(ctx.cb().lessThanOrEqualTo(column.path(ctx), v)));
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> lte(ColumnField<M, ?, C> column, Optional<? extends C> value) {
        return present(column, value) ? lte(column, value.get()) : this;
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> range(
            ColumnField<M, ?, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toExclusive) {
        return bounds(column, fromInclusive, toExclusive, false);
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> between(
            ColumnField<M, ?, C> column, C fromInclusive, C toInclusive) {
        C from = required(column, "between", fromInclusive);
        C to = required(column, "between", toInclusive);
        return add(ctx -> Optional.of(ctx.cb().between(column.path(ctx), from, to)));
    }

    @Override
    public <C extends Comparable<? super C>> Filters<M> between(
            ColumnField<M, ?, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toInclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toInclusive, "toInclusive");
        if (fromInclusive.isPresent() && toInclusive.isPresent()) {
            return between(column, fromInclusive.get(), toInclusive.get());
        }
        return bounds(column, fromInclusive, toInclusive, true);
    }

    /** {@code column >= from}, then {@code column < to} or {@code <= to}; each bound skipped on its own when empty. */
    private <C extends Comparable<? super C>> Filters<M> bounds(
            ColumnField<M, ?, C> column, Optional<? extends C> from, Optional<? extends C> to, boolean toInclusive) {
        checkOpen();
        inScope(column);
        C lower = Objects.requireNonNull(from, "fromInclusive").orElse(null);
        C upper = Objects.requireNonNull(to, toInclusive ? "toInclusive" : "toExclusive").orElse(null);
        if (lower == null && upper == null) {
            return this;
        }
        return add(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Path<C> path = column.path(ctx);
            var parts = new ArrayList<Predicate>(2);
            if (lower != null) {
                parts.add(cb.greaterThanOrEqualTo(path, lower));
            }
            if (upper != null) {
                parts.add(toInclusive ? cb.lessThanOrEqualTo(path, upper) : cb.lessThan(path, upper));
            }
            return Optional.of(and(cb, parts));
        });
    }

    // ---- sets

    @Override
    public <C> Filters<M> in(ColumnField<M, ?, C> column, Collection<? extends C> values) {
        List<C> copy = elements(column, "in", values);
        if (copy.isEmpty()) {
            // An empty selection means "none of these" (R-FLT-02); nothing to resolve, so no join either.
            return add(ctx -> Optional.of(ctx.cb().disjunction()));
        }
        return add(ctx -> Optional.of(column.path(ctx).in(copy)));
    }

    @Override
    public <C> Filters<M> in(ColumnField<M, ?, C> column, Optional<? extends Collection<? extends C>> values) {
        return present(column, values) ? in(column, values.get()) : this;
    }

    @Override
    public <C> Filters<M> notIn(ColumnField<M, ?, C> column, Collection<? extends C> values) {
        List<C> copy = elements(column, "notIn", values);
        if (copy.isEmpty()) {
            // Holds for every row rather than being skipped: it still counts as a filter inside a group.
            return add(ctx -> Optional.empty());
        }
        return add(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Path<C> path = column.path(ctx);
            return Optional.of(cb.or(cb.not(path.in(copy)), cb.isNull(path))); // NULL rows match (R-FLT-04)
        });
    }

    @Override
    public <C> Filters<M> notIn(ColumnField<M, ?, C> column, Optional<? extends Collection<? extends C>> values) {
        return present(column, values) ? notIn(column, values.get()) : this;
    }

    // ---- strings

    @Override
    public Filters<M> like(ColumnField<M, ?, String> column, String value, LikeMode mode) {
        String pattern = pattern(required(column, "like", value), Objects.requireNonNull(mode, "mode"));
        return add(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Path<String> path = column.path(ctx);
            return Optional.of(mode == LikeMode.EXACT ? cb.like(path, pattern) : cb.like(path, pattern, ESCAPE));
        });
    }

    @Override
    public Filters<M> like(ColumnField<M, ?, String> column, Optional<String> value, LikeMode mode) {
        Objects.requireNonNull(mode, "mode");
        return present(column, value) ? like(column, value.get(), mode) : this;
    }

    @Override
    public Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, String value, LikeMode mode) {
        Objects.requireNonNull(mode, "mode");
        String pattern = lower(pattern(required(column, "likeIgnoreCase", value), mode));
        return add(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Expression<String> lowered = cb.lower(column.path(ctx));
            return Optional.of(mode == LikeMode.EXACT ? cb.like(lowered, pattern) : cb.like(lowered, pattern, ESCAPE));
        });
    }

    @Override
    public Filters<M> likeIgnoreCase(ColumnField<M, ?, String> column, Optional<String> value, LikeMode mode) {
        Objects.requireNonNull(mode, "mode");
        return present(column, value) ? likeIgnoreCase(column, value.get(), mode) : this;
    }

    @Override
    public Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, String value) {
        String v = lower(required(column, "eqIgnoreCase", value));
        return add(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            return Optional.of(cb.equal(cb.lower(column.path(ctx)), v));
        });
    }

    @Override
    public Filters<M> eqIgnoreCase(ColumnField<M, ?, String> column, Optional<String> value) {
        return present(column, value) ? eqIgnoreCase(column, value.get()) : this;
    }

    // ---- nulls

    @Override
    public Filters<M> isNull(ColumnField<M, ?, ?> column) {
        inScope(column);
        return add(ctx -> Optional.of(ctx.cb().isNull(column.path(ctx))));
    }

    @Override
    public Filters<M> isNotNull(ColumnField<M, ?, ?> column) {
        inScope(column);
        return add(ctx -> Optional.of(ctx.cb().isNotNull(column.path(ctx))));
    }

    @Override
    public Filters<M> isNull(ColumnField<M, ?, ?> column, Optional<Boolean> isNull) {
        if (!present(column, isNull)) {
            return this;
        }
        return isNull.get() ? isNull(column) : isNotNull(column);
    }

    // ---- column against column

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <C> Filters<M> compare(ColumnField<M, ?, C> left, Op op, ColumnField<M, ?, C> right) {
        inScope(Objects.requireNonNull(left, "left"));
        Objects.requireNonNull(op, "op");
        inScope(Objects.requireNonNull(right, "right"));
        return add(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            // Raw: the ordering operators need a Comparable bound that compare's signature leaves open (api/12 §1).
            Expression l = left.path(ctx);
            Expression r = right.path(ctx);
            return Optional.of(switch (op) {
                case EQ -> cb.equal(l, r);
                case NE -> cb.notEqual(l, r);
                case LT -> cb.lessThan(l, r);
                case LTE -> cb.lessThanOrEqualTo(l, r);
                case GT -> cb.greaterThan(l, r);
                case GTE -> cb.greaterThanOrEqualTo(l, r);
            });
        });
    }

    // ---- composition

    @Override
    @SafeVarargs
    public final Filters<M> or(UnaryOperator<Filters<M>>... branches) {
        checkOpen();
        var groups = new ArrayList<List<Filter>>();
        for (UnaryOperator<Filters<M>> branch : Objects.requireNonNull(branches, "branches")) {
            List<Filter> group = nested(Objects.requireNonNull(branch, "branch"), scope);
            if (!group.isEmpty()) {
                groups.add(group); // a branch whose filters were all skipped is dropped (R-FLT-01)
            }
        }
        if (groups.isEmpty()) {
            return this; // skipped, rather than FALSE matching nothing (R-FLT-01)
        }
        List<List<Filter>> recorded = List.copyOf(groups);
        return add(new LeftJoining(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            var alternatives = new ArrayList<Predicate>(recorded.size());
            for (List<Filter> group : recorded) {
                List<Predicate> parts = toPredicates(group, ctx);
                if (parts.isEmpty()) {
                    return Optional.empty(); // this branch holds for every row, so the or does too
                }
                alternatives.add(and(cb, parts));
            }
            return Optional.of(alternatives.size() == 1
                    ? alternatives.get(0)
                    : cb.or(alternatives.toArray(Predicate[]::new)));
        }));
    }

    @Override
    public Filters<M> not(UnaryOperator<Filters<M>> group) {
        List<Filter> negated = nested(Objects.requireNonNull(group, "group"), scope);
        if (negated.isEmpty()) {
            return this; // R-FLT-01
        }
        return add(new LeftJoining(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            List<Predicate> parts = toPredicates(negated, ctx);
            // Plain NOT (R-FLT-05); a group holding for every row negates to one matching none.
            return Optional.of(parts.isEmpty() ? cb.disjunction() : cb.not(and(cb, parts)));
        }));
    }

    @Override
    public Filters<M> when(boolean condition, UnaryOperator<Filters<M>> group) {
        checkOpen();
        Objects.requireNonNull(group, "group");
        return condition ? apply(group) : this;
    }

    @Override
    public Filters<M> apply(UnaryOperator<Filters<M>> fragment) {
        List<Filter> added = nested(Objects.requireNonNull(fragment, "fragment"), scope);
        filters.addAll(added);
        return this;
    }

    // ---- correlated sub-queries

    @Override
    public Filters<M> exists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner) {
        List<Filter> required = nested(Objects.requireNonNull(inner, "inner"), existsPath(path));
        return required.isEmpty() ? this : add(exists(path, required, false)); // R-FLT-01
    }

    @Override
    public Filters<M> exists(TableField<?, ?> path) {
        checkOpen();
        return add(exists(existsPath(path), List.of(), false));
    }

    @Override
    public Filters<M> notExists(TableField<?, ?> path, UnaryOperator<Filters<M>> inner) {
        List<Filter> excluded = nested(Objects.requireNonNull(inner, "inner"), existsPath(path));
        return excluded.isEmpty() ? this : add(exists(path, excluded, true)); // R-FLT-01
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
            throw new IllegalArgumentException("exists(...) needs a join path, not the " + path.describe());
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
        return add(ctx -> {
            Predicate predicate = custom.apply(ctx, ctx.cb());
            if (predicate == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1301,
                        "add(...): the custom predicate returned null; skip it explicitly with when(...)");
            }
            return Optional.of(predicate);
        });
    }

    // ---- helpers

    /**
     * Collects {@code operator} as a nested group under {@code scope}. This group refuses filters meanwhile, so a
     * branch lambda that adds to the outer {@code Filters} by mistake fails instead of silently ANDing.
     */
    private List<Filter> nested(UnaryOperator<Filters<M>> operator, TableField<?, ?> scope) {
        checkOpen();
        nesting = true;
        try {
            return collect(operator, scope);
        } finally {
            nesting = false;
        }
    }

    private static Predicate and(CriteriaBuilder cb, List<Predicate> parts) {
        return parts.size() == 1 ? parts.get(0) : cb.and(parts.toArray(Predicate[]::new));
    }

    private FilterGroup<M> add(Filter filter) {
        checkOpen();
        filters.add(filter);
        return this;
    }

    /** Internal-misuse guard, like {@code PRIMARY_KEY} without a key: not a query definition error, so no MQ code. */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "This Filters instance is only valid inside its where(...) operator; add filters there");
        }
        if (nesting) {
            throw new IllegalStateException("This Filters instance is in use by a nested or/not/when/apply/exists "
                    + "operator; add that operator's filters to the Filters it receives");
        }
    }

    /** Inside an {@code exists}, a column must sit on its path or below it (R-FLT-11). */
    private void inScope(ColumnField<?, ?, ?> column) {
        Objects.requireNonNull(column, "column");
        if (scope != null && !column.table().isAtOrBelow(scope)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1302, column + " sits on " + column.table().describe()
                    + ", outside the exists(...) path " + scope.describe() + "; use a column on that path or below it");
        }
    }

    /** Whether an {@code Optional}-form filter applies; a {@code null} Optional is a programming error (D-19). */
    private boolean present(ColumnField<?, ?, ?> column, Optional<?> value) {
        checkOpen();
        inScope(column);
        return Objects.requireNonNull(value, "value").isPresent();
    }

    /** The value of a value-form filter; {@code null} throws {@code MQ1301}, since skipping must be explicit (P-3). */
    private <V> V required(ColumnField<?, ?, ?> column, String operator, V value) {
        inScope(column);
        if (value == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1301,
                    column + ": " + operator + "(...) received null; pass Optional.empty() to skip the filter");
        }
        return value;
    }

    /** An immutable copy of {@code values}; the collection or any element being {@code null} throws MQ1301. */
    private <C> List<C> elements(ColumnField<?, ?, ?> column, String operator, Collection<? extends C> values) {
        for (C value : required(column, operator, values)) {
            if (value == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1301, column + ": " + operator
                        + "(...) received a null element; NULL is matched with isNull(...)");
            }
        }
        return List.copyOf(values);
    }

    /** The {@code LIKE} pattern for {@code mode}; every mode but {@code EXACT} escapes the value (R-FLT-06). */
    private static String pattern(String value, LikeMode mode) {
        return switch (mode) {
            case EXACT -> value;
            case CONTAINS -> "%" + escape(value) + "%";
            case STARTS_WITH -> escape(value) + "%";
            case ENDS_WITH -> "%" + escape(value);
        };
    }

    /**
     * The value side of an ignore-case filter, lowered here rather than as {@code lower(?)}: JPA 3.1 has no bound
     * value expression, so {@code lower(literal)} would inline the value (R-FLT-08), and H2 cannot type a bare
     * {@code lower(?)}.
     */
    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
