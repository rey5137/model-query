package com.rey.modelquery.test;

import static java.util.Objects.requireNonNull;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Condition.Kind;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.Outer;
import com.rey.modelquery.core.ScalarField;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import java.util.Collection;
import java.util.List;

/**
 * One matcher per {@code Filters} operator, with its name and the value form of its parameters; the aggregate
 * overloads are the {@code Having} operators. No {@code Optional} overloads and no {@code when} or {@code apply},
 * which record nothing of their own (R-INS-07). Values are compared as passed to the query, before any converter.
 * The operands of {@code or}, {@code and}, {@code not} and {@code exists} match in the order they were added.
 *
 * <p>A path matches by identity or by its join, type and alias, since a path has no public key.
 *
 * @implSpec api/16 R-INS-07, D-101
 */
@Incubating
public final class FilterMatchers {

    private FilterMatchers() {}

    private static ConditionMatcher leaf(Kind kind, SelectField<?, ?> column, Object... values) {
        requireNonNull(column, "column");
        for (Object value : values) {
            requireNonNull(value, "a value; a filter never records null");
        }
        return new ConditionMatcher(kind, column, null, null, null, null, List.of(values), null, List.of());
    }

    private static ConditionMatcher like(Kind kind, SelectField<?, ?> column, String value, LikeMode mode) {
        requireNonNull(column, "column");
        return new ConditionMatcher(kind, column, null, null, requireNonNull(mode, "mode"), null,
                List.of(requireNonNull(value, "value")), null, List.of());
    }

    private static ConditionMatcher group(Kind kind, ConditionMatcher... operands) {
        return new ConditionMatcher(kind, null, null, null, null, null, List.of(), null, List.of(operands));
    }

    /** Matches eq(column, value) (EQ), on a column of a {@code where} or an aggregate of a {@code having}. */
    public static <C> ConditionMatcher eq(ScalarField<?, C> column, C value) {
        return leaf(Kind.EQ, column, value);
    }

    /** The {@code having} form of eq: an aggregate rather than a column. */
    public static <C> ConditionMatcher eq(AggregateField<?, C> aggregate, C value) {
        return leaf(Kind.EQ, aggregate, value);
    }

    /** Matches ne(column, value) (NE), on a column of a {@code where} or an aggregate of a {@code having}. */
    public static <C> ConditionMatcher ne(ScalarField<?, C> column, C value) {
        return leaf(Kind.NE, column, value);
    }

    /** The {@code having} form of ne: an aggregate rather than a column. */
    public static <C> ConditionMatcher ne(AggregateField<?, C> aggregate, C value) {
        return leaf(Kind.NE, aggregate, value);
    }

    /** Matches gt(column, value) (GT), on a column of a {@code where} or an aggregate of a {@code having}. */
    public static <C extends Comparable<? super C>> ConditionMatcher gt(ScalarField<?, C> column, C value) {
        return leaf(Kind.GT, column, value);
    }

    /** The {@code having} form of gt: an aggregate rather than a column. */
    public static <C extends Comparable<? super C>> ConditionMatcher gt(AggregateField<?, C> aggregate, C value) {
        return leaf(Kind.GT, aggregate, value);
    }

    /** Matches gte(column, value) (GTE), on a column of a {@code where} or an aggregate of a {@code having}. */
    public static <C extends Comparable<? super C>> ConditionMatcher gte(ScalarField<?, C> column, C value) {
        return leaf(Kind.GTE, column, value);
    }

    /** The {@code having} form of gte: an aggregate rather than a column. */
    public static <C extends Comparable<? super C>> ConditionMatcher gte(AggregateField<?, C> aggregate, C value) {
        return leaf(Kind.GTE, aggregate, value);
    }

    /** Matches lt(column, value) (LT), on a column of a {@code where} or an aggregate of a {@code having}. */
    public static <C extends Comparable<? super C>> ConditionMatcher lt(ScalarField<?, C> column, C value) {
        return leaf(Kind.LT, column, value);
    }

    /** The {@code having} form of lt: an aggregate rather than a column. */
    public static <C extends Comparable<? super C>> ConditionMatcher lt(AggregateField<?, C> aggregate, C value) {
        return leaf(Kind.LT, aggregate, value);
    }

    /** Matches lte(column, value) (LTE), on a column of a {@code where} or an aggregate of a {@code having}. */
    public static <C extends Comparable<? super C>> ConditionMatcher lte(ScalarField<?, C> column, C value) {
        return leaf(Kind.LTE, column, value);
    }

    /** The {@code having} form of lte: an aggregate rather than a column. */
    public static <C extends Comparable<? super C>> ConditionMatcher lte(AggregateField<?, C> aggregate, C value) {
        return leaf(Kind.LTE, aggregate, value);
    }

    /** Matches range(column, from, to) (RANGE), both bounds. A one-sided range is the comparison it renders. */
    public static <C extends Comparable<? super C>> ConditionMatcher range(
            ScalarField<?, C> column, C from, C to) {
        return leaf(Kind.RANGE, column, from, to);
    }

    /** The {@code having} form of range. */
    public static <C extends Comparable<? super C>> ConditionMatcher range(
            AggregateField<?, C> aggregate, C from, C to) {
        return leaf(Kind.RANGE, aggregate, from, to);
    }

    /** Matches between(column, from, to) (BETWEEN), both bounds. A one-sided between is the comparison it renders. */
    public static <C extends Comparable<? super C>> ConditionMatcher between(
            ScalarField<?, C> column, C from, C to) {
        return leaf(Kind.BETWEEN, column, from, to);
    }

    /** The {@code having} form of between. */
    public static <C extends Comparable<? super C>> ConditionMatcher between(
            AggregateField<?, C> aggregate, C from, C to) {
        return leaf(Kind.BETWEEN, aggregate, from, to);
    }

    /** Matches in(column, values) (IN); the values match as a multiset, so their order does not matter. */
    public static <C> ConditionMatcher in(ScalarField<?, C> column, Collection<? extends C> values) {
        return leaf(Kind.IN, column, values.toArray());
    }

    /** The {@code having} form of in. */
    public static <C> ConditionMatcher in(AggregateField<?, C> aggregate, Collection<? extends C> values) {
        return leaf(Kind.IN, aggregate, values.toArray());
    }

    /** Matches notIn(column, values) (NOT_IN); the values match as a multiset, so their order does not matter. */
    public static <C> ConditionMatcher notIn(ScalarField<?, C> column, Collection<? extends C> values) {
        return leaf(Kind.NOT_IN, column, values.toArray());
    }

    /** The {@code having} form of notIn. */
    public static <C> ConditionMatcher notIn(AggregateField<?, C> aggregate, Collection<? extends C> values) {
        return leaf(Kind.NOT_IN, aggregate, values.toArray());
    }

    /** Matches like(column, value, mode) (LIKE); the value as passed, not lower-cased. */
    public static ConditionMatcher like(ScalarField<?, String> column, String value, LikeMode mode) {
        return like(Kind.LIKE, column, value, mode);
    }

    /** The {@code having} form of like. */
    public static ConditionMatcher like(AggregateField<?, String> aggregate, String value, LikeMode mode) {
        return like(Kind.LIKE, aggregate, value, mode);
    }

    /** Matches likeIgnoreCase(column, value, mode) (LIKE_IGNORE_CASE); the value as passed, not lower-cased. */
    public static ConditionMatcher likeIgnoreCase(ScalarField<?, String> column, String value, LikeMode mode) {
        return like(Kind.LIKE_IGNORE_CASE, column, value, mode);
    }

    /** The {@code having} form of likeIgnoreCase. */
    public static ConditionMatcher likeIgnoreCase(AggregateField<?, String> aggregate, String value, LikeMode mode) {
        return like(Kind.LIKE_IGNORE_CASE, aggregate, value, mode);
    }

    /** Matches eqIgnoreCase(column, value) (EQ_IGNORE_CASE); the value as passed, not lower-cased. */
    public static ConditionMatcher eqIgnoreCase(ScalarField<?, String> column, String value) {
        return leaf(Kind.EQ_IGNORE_CASE, column, value);
    }

    /** The {@code having} form of eqIgnoreCase. */
    public static ConditionMatcher eqIgnoreCase(AggregateField<?, String> aggregate, String value) {
        return leaf(Kind.EQ_IGNORE_CASE, aggregate, value);
    }

    /** Matches isNull(column) (IS_NULL). */
    public static ConditionMatcher isNull(ScalarField<?, ?> column) {
        return leaf(Kind.IS_NULL, column);
    }

    /** The {@code having} form of isNull. */
    public static ConditionMatcher isNull(AggregateField<?, ?> aggregate) {
        return leaf(Kind.IS_NULL, aggregate);
    }

    /** Matches isNotNull(column) (IS_NOT_NULL). */
    public static ConditionMatcher isNotNull(ScalarField<?, ?> column) {
        return leaf(Kind.IS_NOT_NULL, column);
    }

    /** The {@code having} form of isNotNull. */
    public static ConditionMatcher isNotNull(AggregateField<?, ?> aggregate) {
        return leaf(Kind.IS_NOT_NULL, aggregate);
    }

    /** Matches compare(left, op, right) (COMPARE). */
    public static <C> ConditionMatcher compare(ScalarField<?, C> left, Op op, ScalarField<?, C> right) {
        return new ConditionMatcher(Kind.COMPARE, left, right, requireNonNull(op, "op"), null, null, List.of(), null,
                List.of());
    }

    /** The {@code having} form of compare. */
    public static <C> ConditionMatcher compare(AggregateField<?, C> left, Op op, AggregateField<?, C> right) {
        return new ConditionMatcher(Kind.COMPARE, left, right, requireNonNull(op, "op"), null, null, List.of(), null,
                List.of());
    }

    /** Matches or(...) (OR), one matcher per branch, in order. A branch of two or more filters is an {@link #and}. */
    public static ConditionMatcher or(ConditionMatcher... branches) {
        return group(Kind.OR, branches);
    }

    /** Matches the branch of an {@code or} that holds two or more filters (AND), its operands in order. */
    public static ConditionMatcher and(ConditionMatcher... operands) {
        return group(Kind.AND, operands);
    }

    /** Matches not(...) (NOT), its operands in order. */
    public static ConditionMatcher not(ConditionMatcher... operands) {
        return group(Kind.NOT, operands);
    }

    /** Matches exists(path, inner...) (EXISTS), its operands in order; none for {@code exists(path)}. */
    public static ConditionMatcher exists(TableField<?, ?> path, ConditionMatcher... inner) {
        return new ConditionMatcher(Kind.EXISTS, null, null, null, null, requireNonNull(path, "path"), List.of(), null,
                List.of(inner));
    }

    /** Matches notExists(path, inner...) (NOT_EXISTS), its operands in order. */
    public static ConditionMatcher notExists(TableField<?, ?> path, ConditionMatcher... inner) {
        return new ConditionMatcher(Kind.NOT_EXISTS, null, null, null, null, requireNonNull(path, "path"), List.of(),
                null, List.of(inner));
    }

    // ---- sub-selects (R-INS-08)

    /** Matches in(column, sub) (IN_SUBSELECT): the column and the sub-select's column, root and conditions. */
    public static <C> ConditionMatcher in(ScalarField<?, C> column, SubSelect<?, C> sub) {
        requireNonNull(column, "column");
        requireNonNull(sub, "sub");
        return new ConditionMatcher(Kind.IN_SUBSELECT, column, null, null, null, null, List.of(), null, List.of(),
                sub);
    }

    /** Matches notIn(column, sub) (NOT_IN_SUBSELECT). */
    public static <C> ConditionMatcher notIn(ScalarField<?, C> column, SubSelect<?, C> sub) {
        requireNonNull(column, "column");
        requireNonNull(sub, "sub");
        return new ConditionMatcher(Kind.NOT_IN_SUBSELECT, column, null, null, null, null, List.of(), null, List.of(),
                sub);
    }

    /** Matches exists(sub, correlation...) (EXISTS_SUBSELECT), the correlation matchers in order. */
    public static ConditionMatcher exists(SubSelect<?, ?> sub, ConditionMatcher... correlation) {
        requireNonNull(sub, "sub");
        return new ConditionMatcher(Kind.EXISTS_SUBSELECT, null, null, null, null, null, List.of(), null,
                List.of(correlation), sub);
    }

    /** Matches notExists(sub, correlation...) (NOT_EXISTS_SUBSELECT), the correlation matchers in order. */
    public static ConditionMatcher notExists(SubSelect<?, ?> sub, ConditionMatcher... correlation) {
        requireNonNull(sub, "sub");
        return new ConditionMatcher(Kind.NOT_EXISTS_SUBSELECT, null, null, null, null, null, List.of(), null,
                List.of(correlation), sub);
    }

    /**
     * The lifted column {@code Outer.column(column)} builds, for a correlation's matcher (R-INS-08). It equals the
     * column an actual correlation recorded, and its text marks it {@code outer.}.
     */
    public static <S, T, C> ColumnField<S, T, C> outer(ColumnField<S, T, C> column) {
        return Outer.reference(requireNonNull(column, "column"));
    }

    /** Matches a custom filter added with {@code add(label, ...)}; the predicate is opaque, so the label is all. */
    public static ConditionMatcher custom(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("a custom filter is matched by its label, which must not be blank");
        }
        return new ConditionMatcher(Kind.CUSTOM, null, null, null, null, null, List.of(), label, List.of());
    }
}
