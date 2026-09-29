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
import java.util.function.UnaryOperator;

/**
 * The {@link Filters} implementation: an AND group that records one {@link Filter} per filter added. It lives only
 * while a {@code where} operator runs; {@link #collect} freezes what it recorded into an immutable list, which is
 * what a {@link ModelQuery} keeps (INV-9). The group is closed once {@code collect} returns, so a reference kept past
 * the operator fails loudly instead of adding filters nobody reads (D-23).
 */
final class FilterGroup<M> implements Filters<M> {

    private static final char ESCAPE = '\\';

    private final List<Filter> filters = new ArrayList<>();
    private boolean closed;

    private FilterGroup() {}

    /** Runs {@code operator} on an empty group and returns the filters it added, as an immutable list. */
    static <M> List<Filter> collect(UnaryOperator<Filters<M>> operator) {
        var group = new FilterGroup<M>();
        // Every filter added to the group counts, so a statement-style operator that ignores a return value
        // loses nothing; the operator's own result carries no extra information.
        try {
            operator.apply(group);
            return List.copyOf(group.filters);
        } finally {
            group.closed = true;
        }
    }

    /** The predicates of {@code filters} against one build's joins, leaving out filters that hold for every row. */
    static List<Predicate> toPredicates(List<Filter> filters, JoinContext ctx) {
        var predicates = new ArrayList<Predicate>();
        for (Filter filter : filters) {
            filter.toPredicate(ctx).ifPresent(predicates::add);
        }
        return predicates;
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
        Objects.requireNonNull(column, "column");
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
            return Optional.of(parts.size() == 1 ? parts.get(0) : cb.and(parts.toArray(Predicate[]::new)));
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
        Objects.requireNonNull(column, "column");
        return add(ctx -> Optional.of(ctx.cb().isNull(column.path(ctx))));
    }

    @Override
    public Filters<M> isNotNull(ColumnField<M, ?, ?> column) {
        Objects.requireNonNull(column, "column");
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
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(right, "right");
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

    // ---- helpers

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
    }

    /** Whether an {@code Optional}-form filter applies; a {@code null} Optional is a programming error (D-19). */
    private boolean present(ColumnField<?, ?, ?> column, Optional<?> value) {
        checkOpen();
        Objects.requireNonNull(column, "column");
        return Objects.requireNonNull(value, "value").isPresent();
    }

    /** The value of a value-form filter; {@code null} throws {@code MQ1301}, since skipping must be explicit (P-3). */
    private static <V> V required(ColumnField<?, ?, ?> column, String operator, V value) {
        Objects.requireNonNull(column, "column");
        if (value == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1301,
                    column + ": " + operator + "(...) received null; pass Optional.empty() to skip the filter");
        }
        return value;
    }

    /** An immutable copy of {@code values}; the collection or any element being {@code null} throws MQ1301. */
    private static <C> List<C> elements(ColumnField<?, ?, ?> column, String operator, Collection<? extends C> values) {
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
