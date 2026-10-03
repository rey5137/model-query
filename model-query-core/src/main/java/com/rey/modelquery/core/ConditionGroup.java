package com.rey.modelquery.core;

import com.rey.modelquery.core.Condition.Kind;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * The recording engine behind {@link Filters} and {@link Having}: an AND group that records one {@link Filter} per
 * filter added, over any {@link SelectField}, each with the {@link Condition} it records (R-INS-01). Each subclass
 * exposes it through its public builder type {@code G}, whose signatures decide which selections are accepted:
 * {@code Filters} takes a {@code ColumnField} and {@code Having} an {@code AggregateField} (R-COL-06, R-AGG-06), so
 * the skip and {@code Optional} semantics are shared, not copied.
 *
 * <p>A group lives only while its operator runs; {@link #collect} freezes what it recorded into an immutable list,
 * which is what a {@link ModelQuery} keeps (INV-9). The group is closed once {@code collect} returns, so a reference
 * kept past the operator fails loudly, with {@code MQ1303}, instead of adding filters nobody reads (D-23). A nested
 * group ({@code or}
 * branch, {@code not}, {@code when}, {@code apply}, {@code exists}) is collected the same way.
 *
 * @param <M> the model the query maps to
 * @param <G> the public builder type the operators receive
 */
abstract class ConditionGroup<M, G> {

    private static final char ESCAPE = '\\';

    private final List<Filter> filters = new ArrayList<>();
    /** The builder's public name and the clause its operator fills, for misuse messages. */
    private final String builder;
    private final String clause;
    private boolean closed;
    /** Set while a nested group is collected, so a filter meant for the branch cannot land here by mistake. */
    private boolean nesting;

    ConditionGroup(String builder, String clause) {
        this.builder = builder;
        this.clause = clause;
    }

    /** This group as the builder its operator receives. */
    abstract G self();

    /** An empty group for a nested {@code or}, {@code not}, {@code when} or {@code apply} operator. */
    abstract ConditionGroup<M, G> child();

    /** Checks {@code column} before a filter over it is recorded, or skipped. */
    abstract void check(SelectField<M, ?> column);

    /** Runs {@code operator} on {@code group} and returns the filters it added, as an immutable list. */
    static <M, G> List<Filter> collect(ConditionGroup<M, G> group, UnaryOperator<G> operator) {
        // Every filter added to the group counts, so a statement-style operator that ignores a return value
        // loses nothing; the operator's own result carries no extra information.
        try {
            operator.apply(group.self());
            return List.copyOf(group.filters);
        } finally {
            group.closed = true;
        }
    }

    /**
     * Runs the two-argument {@code operator} on {@code group}, the shape a correlated {@code exists} takes: its
     * correlation receives the sub-select's inner group and its {@link Outer}.
     */
    static <M, G, X> List<Filter> collect(ConditionGroup<M, G> group, BiFunction<G, X, G> operator, X argument) {
        try {
            operator.apply(group.self(), argument);
            return List.copyOf(group.filters);
        } finally {
            group.closed = true;
        }
    }

    /** Marks this group as in use by a nested operator, so a filter meant for the branch cannot land here. */
    final void beginNesting() {
        checkOpen();
        nesting = true;
    }

    /** Ends {@link #beginNesting()}. */
    final void endNesting() {
        nesting = false;
    }

    /** The predicates of one group against one build's joins, rendered as {@link #clausePredicates} renders. */
    static List<Predicate> toPredicates(List<Filter> filters, JoinContext ctx) {
        return clausePredicates(List.of(filters), ctx).get(0);
    }

    /**
     * The predicates of each clause of {@code clauses} ({@code where}, then {@code having}) against one build's joins,
     * each clause in order, leaving out filters that hold for every row. Every {@code or} and {@code not} renders
     * after every other filter of every clause: an INNER join the rest of the query needs then already exists for them
     * to reuse (R-FLT-10), where rendering one first would join the path LEFT and the rest would join it again (D-26).
     */
    static List<List<Predicate>> clausePredicates(List<List<Filter>> clauses, JoinContext ctx) {
        var rendered = new ArrayList<List<Optional<Predicate>>>(clauses.size());
        for (List<Filter> filters : clauses) {
            var clause = new ArrayList<Optional<Predicate>>(filters.size());
            for (Filter filter : filters) {
                clause.add(leftJoining(filter) ? null : filter.toPredicate(ctx));
            }
            rendered.add(clause);
        }
        var result = new ArrayList<List<Predicate>>(clauses.size());
        for (int c = 0; c < clauses.size(); c++) {
            List<Filter> filters = clauses.get(c);
            for (int i = 0; i < filters.size(); i++) {
                if (leftJoining(filters.get(i))) {
                    rendered.get(c).set(i, filters.get(i).toPredicate(ctx));
                }
            }
            var predicates = new ArrayList<Predicate>();
            rendered.get(c).forEach(predicate -> predicate.ifPresent(predicates::add));
            result.add(predicates);
        }
        return result;
    }

    /** The conditions {@code filters} recorded, in order (R-INS-01). */
    static List<Condition> conditions(List<Filter> filters) {
        return filters.stream().map(filter -> ((Recorded) filter).condition()).toList();
    }

    private static boolean leftJoining(Filter filter) {
        return ((Recorded) filter).filter() instanceof LeftJoining;
    }

    /** An {@code or} or {@code not}: the joins it is the first to need resolve as LEFT (R-FLT-10). */
    private record LeftJoining(Filter filter) implements Filter {
        @Override
        public Optional<Predicate> toPredicate(JoinContext ctx) {
            return ctx.leftJoining(() -> filter.toPredicate(ctx));
        }
    }

    /** A filter with the condition it records: every filter a group holds is one, so none lacks its condition. */
    private record Recorded(Condition condition, Filter filter) implements Filter {
        @Override
        public Optional<Predicate> toPredicate(JoinContext ctx) {
            return filter.toPredicate(ctx);
        }
    }

    // ---- equality and comparison

    final <C> G eq(SelectField<M, C> column, C value) {
        C v = bound(column, required(column, "eq", value));
        return record(Condition.of(Kind.EQ, column, List.of(value)),
                ctx -> Optional.of(ctx.cb().equal(column.expression(ctx), v)));
    }

    final <C> G eq(SelectField<M, C> column, Optional<? extends C> value) {
        return present(column, value) ? eq(column, value.get()) : self();
    }

    final <C> G ne(SelectField<M, C> column, C value) {
        C v = bound(column, required(column, "ne", value));
        return record(Condition.of(Kind.NE, column, List.of(value)), ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Expression<C> expression = column.expression(ctx);
            // Plain SQL <> would drop NULL rows, which a report's "not X" means to keep (R-FLT-04). Rendered for
            // every column: a non-null attribute can still read NULL through a LEFT join.
            return Optional.of(cb.or(cb.notEqual(expression, v), cb.isNull(expression)));
        });
    }

    final <C> G ne(SelectField<M, C> column, Optional<? extends C> value) {
        return present(column, value) ? ne(column, value.get()) : self();
    }

    final <C extends Comparable<? super C>> G gt(SelectField<M, C> column, C value) {
        C v = bound(column, required(column, "gt", value), Comparable.class, "gt");
        return record(Condition.of(Kind.GT, column, List.of(value)),
                ctx -> Optional.of(ctx.cb().greaterThan(column.expression(ctx), v)));
    }

    final <C extends Comparable<? super C>> G gt(SelectField<M, C> column, Optional<? extends C> value) {
        return present(column, value) ? gt(column, value.get()) : self();
    }

    final <C extends Comparable<? super C>> G gte(SelectField<M, C> column, C value) {
        C v = bound(column, required(column, "gte", value), Comparable.class, "gte");
        return record(Condition.of(Kind.GTE, column, List.of(value)),
                ctx -> Optional.of(ctx.cb().greaterThanOrEqualTo(column.expression(ctx), v)));
    }

    final <C extends Comparable<? super C>> G gte(SelectField<M, C> column, Optional<? extends C> value) {
        return present(column, value) ? gte(column, value.get()) : self();
    }

    final <C extends Comparable<? super C>> G lt(SelectField<M, C> column, C value) {
        C v = bound(column, required(column, "lt", value), Comparable.class, "lt");
        return record(Condition.of(Kind.LT, column, List.of(value)),
                ctx -> Optional.of(ctx.cb().lessThan(column.expression(ctx), v)));
    }

    final <C extends Comparable<? super C>> G lt(SelectField<M, C> column, Optional<? extends C> value) {
        return present(column, value) ? lt(column, value.get()) : self();
    }

    final <C extends Comparable<? super C>> G lte(SelectField<M, C> column, C value) {
        C v = bound(column, required(column, "lte", value), Comparable.class, "lte");
        return record(Condition.of(Kind.LTE, column, List.of(value)),
                ctx -> Optional.of(ctx.cb().lessThanOrEqualTo(column.expression(ctx), v)));
    }

    final <C extends Comparable<? super C>> G lte(SelectField<M, C> column, Optional<? extends C> value) {
        return present(column, value) ? lte(column, value.get()) : self();
    }

    final <C extends Comparable<? super C>> G range(
            SelectField<M, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toExclusive) {
        return bounds(column, fromInclusive, toExclusive, false);
    }

    final <C extends Comparable<? super C>> G between(SelectField<M, C> column, C fromInclusive, C toInclusive) {
        C from = bound(column, required(column, "between", fromInclusive), Comparable.class, "between");
        C to = bound(column, required(column, "between", toInclusive), Comparable.class, "between");
        return record(Condition.of(Kind.BETWEEN, column, List.of(fromInclusive, toInclusive)),
                ctx -> Optional.of(ctx.cb().between(column.expression(ctx), from, to)));
    }

    final <C extends Comparable<? super C>> G between(
            SelectField<M, C> column, Optional<? extends C> fromInclusive, Optional<? extends C> toInclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toInclusive, "toInclusive");
        if (fromInclusive.isPresent() && toInclusive.isPresent()) {
            return between(column, fromInclusive.get(), toInclusive.get());
        }
        return bounds(column, fromInclusive, toInclusive, true);
    }

    /** {@code column >= from}, then {@code column < to} or {@code <= to}; each bound skipped on its own when empty. */
    private <C extends Comparable<? super C>> G bounds(
            SelectField<M, C> column, Optional<? extends C> from, Optional<? extends C> to, boolean toInclusive) {
        guard(column, "column");
        requireAttribute(column, Comparable.class, "between");
        C lowerValue = Objects.requireNonNull(from, "fromInclusive").orElse(null);
        C upperValue = Objects.requireNonNull(to, toInclusive ? "toInclusive" : "toExclusive").orElse(null);
        C lower = boundOrNull(column, lowerValue);
        C upper = boundOrNull(column, upperValue);
        if (lower == null && upper == null) {
            return self();
        }
        // Recorded as rendered: both bounds of a range, else the one comparison left (R-INS-01). Both bounds of a
        // between never get here.
        Condition condition = lower == null
                ? Condition.of(toInclusive ? Kind.LTE : Kind.LT, column, List.of(upperValue))
                : upper == null
                        ? Condition.of(Kind.GTE, column, List.of(lowerValue))
                        : Condition.of(Kind.RANGE, column, List.of(lowerValue, upperValue));
        return record(condition, ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Expression<C> expression = column.expression(ctx);
            var parts = new ArrayList<Predicate>(2);
            if (lower != null) {
                parts.add(cb.greaterThanOrEqualTo(expression, lower));
            }
            if (upper != null) {
                parts.add(toInclusive ? cb.lessThanOrEqualTo(expression, upper) : cb.lessThan(expression, upper));
            }
            return Optional.of(and(cb, parts));
        });
    }

    // ---- sets

    /**
     * A condition no row meets, FALSE: nothing to resolve, so no join either (R-FLT-02, D-92). Shared by an empty
     * {@code in} and an empty {@code or}, so it records the {@code condition} each passes.
     */
    private G none(Condition condition) {
        return record(condition, ctx -> Optional.of(ctx.cb().disjunction()));
    }

    final <C> G in(SelectField<M, C> column, Collection<? extends C> values) {
        List<C> copy = elements(column, "in", values);
        Condition condition = Condition.of(Kind.IN, column, List.copyOf(values));
        if (copy.isEmpty()) {
            // An empty selection means "none of these" (R-FLT-02).
            return none(condition);
        }
        return record(condition, ctx -> {
            List<Predicate> chunks = inChunks(column, "in", column.expression(ctx), copy, ctx.renderOptions());
            return Optional.of(chunks.size() == 1 ? chunks.get(0) : ctx.cb().or(chunks.toArray(Predicate[]::new)));
        });
    }

    final <C> G in(SelectField<M, C> column, Optional<? extends Collection<? extends C>> values) {
        return present(column, values) ? in(column, values.get()) : self();
    }

    final <C> G notIn(SelectField<M, C> column, Collection<? extends C> values) {
        List<C> copy = elements(column, "notIn", values);
        Condition condition = Condition.of(Kind.NOT_IN, column, List.copyOf(values));
        if (copy.isEmpty()) {
            // Holds for every row rather than being skipped: it still counts as a filter inside a group.
            return record(condition, ctx -> Optional.empty());
        }
        return record(condition, ctx -> {
            CriteriaBuilder cb = ctx.cb();
            Expression<C> expression = column.expression(ctx);
            List<Predicate> notIn = inChunks(column, "notIn", expression, copy, ctx.renderOptions()).stream()
                    .map(cb::not)
                    .toList();
            // NULL is unknown in every chunk, so it is ORed in once, outside their AND: NULLs match (R-FLT-04).
            return Optional.of(cb.or(and(cb, notIn), cb.isNull(expression)));
        });
    }

    final <C> G notIn(SelectField<M, C> column, Optional<? extends Collection<? extends C>> values) {
        return present(column, values) ? notIn(column, values.get()) : self();
    }

    // ---- strings

    final G like(SelectField<M, String> column, String value, LikeMode mode) {
        String pattern = pattern(bound(column, required(column, "like", value), String.class, "like"),
                Objects.requireNonNull(mode, "mode"));
        return record(Condition.like(Kind.LIKE, column, value, mode),
                ctx -> Optional.of(like(ctx.cb(), column.expression(ctx), pattern, mode)));
    }

    /** {@code LIKE}, with the escape character only when the pattern was escaped, i.e. not {@code EXACT}. */
    private static Predicate like(CriteriaBuilder cb, Expression<String> expression, String pattern, LikeMode mode) {
        return mode == LikeMode.EXACT ? cb.like(expression, pattern) : cb.like(expression, pattern, ESCAPE);
    }

    final G like(SelectField<M, String> column, Optional<String> value, LikeMode mode) {
        Objects.requireNonNull(mode, "mode");
        return present(column, value) ? like(column, value.get(), mode) : self();
    }

    final G likeIgnoreCase(SelectField<M, String> column, String value, LikeMode mode) {
        Objects.requireNonNull(mode, "mode");
        String pattern = lower(pattern(
                bound(column, required(column, "likeIgnoreCase", value), String.class, "likeIgnoreCase"), mode));
        return record(Condition.like(Kind.LIKE_IGNORE_CASE, column, value, mode),
                ctx -> Optional.of(like(ctx.cb(), ctx.cb().lower(column.expression(ctx)), pattern, mode)));
    }

    final G likeIgnoreCase(SelectField<M, String> column, Optional<String> value, LikeMode mode) {
        Objects.requireNonNull(mode, "mode");
        return present(column, value) ? likeIgnoreCase(column, value.get(), mode) : self();
    }

    final G eqIgnoreCase(SelectField<M, String> column, String value) {
        String v = lower(bound(column, required(column, "eqIgnoreCase", value), String.class, "eqIgnoreCase"));
        return record(Condition.of(Kind.EQ_IGNORE_CASE, column, List.of(value)), ctx -> {
            CriteriaBuilder cb = ctx.cb();
            return Optional.of(cb.equal(cb.lower(column.expression(ctx)), v));
        });
    }

    final G eqIgnoreCase(SelectField<M, String> column, Optional<String> value) {
        return present(column, value) ? eqIgnoreCase(column, value.get()) : self();
    }

    // ---- nulls

    final G isNull(SelectField<M, ?> column) {
        guard(column, "column");
        return record(Condition.of(Kind.IS_NULL, column, List.of()),
                ctx -> Optional.of(ctx.cb().isNull(column.expression(ctx))));
    }

    final G isNotNull(SelectField<M, ?> column) {
        guard(column, "column");
        return record(Condition.of(Kind.IS_NOT_NULL, column, List.of()),
                ctx -> Optional.of(ctx.cb().isNotNull(column.expression(ctx))));
    }

    final G isNull(SelectField<M, ?> column, Optional<Boolean> isNull) {
        if (!present(column, isNull)) {
            return self();
        }
        return isNull.get() ? isNull(column) : isNotNull(column);
    }

    // ---- selection against selection

    @SuppressWarnings({"unchecked", "rawtypes"})
    final <C> G compare(SelectField<M, C> left, Op op, SelectField<M, C> right) {
        guard(left, "left");
        Objects.requireNonNull(op, "op");
        guard(right, "right");
        if ((converted(left) != null || converted(right) != null) && attributeType(left) != attributeType(right)) {
            // The database compares attribute values, and no converter can be applied to a column (R-COL-14).
            throw new ModelQueryDefinitionException(MqCode.MQ1001, String.format(
                    "%s %s %s: the columns read attributes of type %s and %s, which a converter makes look alike; "
                            + "compare columns of one attribute type", left, op, right,
                    attributeType(left).getSimpleName(), attributeType(right).getSimpleName()));
        }
        return record(Condition.compare(left, op, right), ctx -> {
            CriteriaBuilder cb = ctx.cb();
            // Raw: the ordering operators need a Comparable bound that compare's signature leaves open (api/12 §1).
            Expression l = left.expression(ctx);
            Expression r = right.expression(ctx);
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

    // ---- composition: public here, since these signatures take G and so implement the builder's own

    public final G or(UnaryOperator<G> first, UnaryOperator<G> second) {
        return or(Arrays.asList(first, second));
    }

    public final G or(UnaryOperator<G> first, UnaryOperator<G> second, UnaryOperator<G> third) {
        return or(Arrays.asList(first, second, third));
    }

    public final G or(List<? extends UnaryOperator<G>> branches) {
        checkOpen();
        if (Objects.requireNonNull(branches, "branches").isEmpty()) {
            // No branch at all is "none of these" (D-92, P-3), as an empty `in` is (R-FLT-02).
            return none(Condition.group(Kind.OR, List.of()));
        }
        var groups = new ArrayList<List<Filter>>();
        for (UnaryOperator<G> branch : branches) {
            List<Filter> group = nested(Objects.requireNonNull(branch, "branch"), child());
            if (!group.isEmpty()) {
                groups.add(group); // a branch whose filters were all skipped is dropped (R-FLT-01)
            }
        }
        if (groups.isEmpty()) {
            return self(); // every branch had its filters all skipped, so the or is skipped (R-FLT-01)
        }
        List<List<Filter>> recorded = List.copyOf(groups);
        var branchConditions = new ArrayList<Condition>(recorded.size());
        for (List<Filter> group : recorded) {
            // A branch of several filters is an AND node, so or(a.and(b), c) and or(a, b, c) differ (D-101).
            List<Condition> conditions = conditions(group);
            branchConditions.add(conditions.size() == 1 ? conditions.get(0) : Condition.group(Kind.AND, conditions));
        }
        return record(Condition.group(Kind.OR, branchConditions), new LeftJoining(ctx -> {
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

    public final G not(UnaryOperator<G> group) {
        List<Filter> negated = nested(Objects.requireNonNull(group, "group"), child());
        if (negated.isEmpty()) {
            return self(); // R-FLT-01
        }
        return record(Condition.group(Kind.NOT, conditions(negated)), new LeftJoining(ctx -> {
            CriteriaBuilder cb = ctx.cb();
            List<Predicate> parts = toPredicates(negated, ctx);
            // Plain NOT (R-FLT-05); a group holding for every row negates to one matching none.
            return Optional.of(parts.isEmpty() ? cb.disjunction() : cb.not(and(cb, parts)));
        }));
    }

    public final G when(boolean condition, UnaryOperator<G> group) {
        checkOpen();
        Objects.requireNonNull(group, "group");
        return condition ? apply(group) : self();
    }

    public final G apply(UnaryOperator<G> fragment) {
        List<Filter> added = nested(Objects.requireNonNull(fragment, "fragment"), child());
        filters.addAll(added);
        return self();
    }

    // ---- helpers

    /**
     * Collects {@code operator} on {@code group} as a nested group. This group refuses filters meanwhile, so a branch
     * lambda that adds to the outer builder by mistake fails instead of silently ANDing.
     */
    final List<Filter> nested(UnaryOperator<G> operator, ConditionGroup<M, G> group) {
        checkOpen();
        nesting = true;
        try {
            return collect(group, operator);
        } finally {
            nesting = false;
        }
    }

    static Predicate and(CriteriaBuilder cb, List<Predicate> parts) {
        return parts.size() == 1 ? parts.get(0) : cb.and(parts.toArray(Predicate[]::new));
    }

    /** The one point a filter is recorded, with the condition it records, so no predicate lacks one (D-101). */
    final G record(Condition condition, Filter filter) {
        checkOpen();
        filters.add(new Recorded(Objects.requireNonNull(condition, "condition"), filter));
        return self();
    }

    /** Refuses a builder kept past its operator, or used while a nested operator runs, with {@code MQ1303} (D-23). */
    final void checkOpen() {
        if (closed) {
            throw new ModelQueryDefinitionException(MqCode.MQ1303, "This " + builder + " instance is only valid "
                    + "inside its " + clause + "(...) operator; add filters there");
        }
        if (nesting) {
            throw new ModelQueryDefinitionException(MqCode.MQ1303, "This " + builder + " instance is in use by a "
                    + "nested operator; add that operator's filters to the " + builder + " it receives");
        }
    }

    /** Whether an {@code Optional}-form filter applies; a {@code null} Optional is a programming error (D-19). */
    private boolean present(SelectField<M, ?> column, Optional<?> value) {
        guard(column, "column");
        return Objects.requireNonNull(value, "value").isPresent();
    }

    /** The checks every operator runs on a column it is given: the builder is open, the column non-null and valid. */
    private void guard(SelectField<M, ?> column, String name) {
        checkOpen();
        check(Objects.requireNonNull(column, name));
    }

    /** The value of a value-form filter; {@code null} throws {@code MQ1301}, since skipping must be explicit (P-3). */
    private <V> V required(SelectField<M, ?> column, String operator, V value) {
        check(Objects.requireNonNull(column, "column"));
        if (value == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1301,
                    column + ": " + operator + "(...) received null; pass Optional.empty() to skip the filter");
        }
        return value;
    }

    /**
     * An immutable copy of {@code values}, each as the column binds it; the collection or any element being
     * {@code null} throws MQ1301.
     */
    private <C> List<C> elements(SelectField<M, C> column, String operator, Collection<? extends C> values) {
        var copy = new ArrayList<C>(required(column, operator, values).size());
        for (C value : values) {
            if (value == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1301, column + ": " + operator
                        + "(...) received a null element; NULL is matched with isNull(...)");
            }
            copy.add(bound(column, value));
        }
        return List.copyOf(copy);
    }

    /**
     * The value a filter on {@code column} binds for {@code value}: the attribute value for a column with a
     * {@link ColumnConverter}, or a {@code min} or {@code max} over one (R-AGG-04), converted once when the filter is
     * recorded, else {@code value} itself (R-COL-14).
     */
    @SuppressWarnings("unchecked")
    private static <C> C bound(SelectField<?, C> column, C value) {
        // A converted column's path is of the attribute's type although typed by the model's (D-37), and the bound
        // value is typed the same way, so the two still meet in one Criteria call.
        ColumnField<?, ?, C> values = ColumnField.valueColumn(column);
        return values != null ? (C) values.toAttribute(value) : value;
    }

    /** {@link #bound(SelectField, Object)} for an operator that needs the attribute to be a {@code needed}. */
    private static <C> C bound(SelectField<?, C> column, C value, Class<?> needed, String operator) {
        requireAttribute(column, needed, operator);
        return bound(column, value);
    }

    /**
     * Refuses {@code operator} on a converted column whose attribute is not a {@code needed}: the database applies
     * the operator to attribute values, whatever the model's type is (R-COL-14).
     */
    private static void requireAttribute(SelectField<?, ?> column, Class<?> needed, String operator) {
        ColumnField<?, ?, ?> field = converted(column);
        if (field != null && !needed.isAssignableFrom(field.attributeType())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1001, String.format(
                    "%s: %s(...) needs a %s attribute, and the column's converter %s reads one of type %s",
                    column, operator, needed.getSimpleName(), field.converterClass().getSimpleName(),
                    field.attributeType().getSimpleName()));
        }
    }

    /** The {@link ColumnField} with a {@link ColumnConverter} that maps {@code column}'s values, or {@code null}. */
    static ColumnField<?, ?, ?> converted(SelectField<?, ?> column) {
        ColumnField<?, ?, ?> values = ColumnField.valueColumn(column);
        return values != null && values.isConverted() ? values : null;
    }

    static Class<?> attributeType(SelectField<?, ?> column) {
        ColumnField<?, ?, ?> values = ColumnField.valueColumn(column);
        return values != null ? values.attributeType() : column.type();
    }

    private static <C> C boundOrNull(SelectField<?, C> column, C value) {
        return value == null ? null : bound(column, value);
    }

    /**
     * {@code expression IN} each run of at most {@code maxInListSize()} of {@code values}, in order (R-FLT-09). One
     * filter's values cannot be split across statements, so more of them than one statement binds throws
     * {@code MQ1306} here rather than failing in the database.
     */
    private static <C> List<Predicate> inChunks(SelectField<?, C> column, String operator, Expression<C> expression,
            List<C> values, RenderOptions options) {
        if (values.size() > options.maxBindParameters()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1306, column + ": " + operator + "(...) received "
                    + values.size() + " values, more than the " + options.maxBindParameters()
                    + " bind parameters one statement takes");
        }
        int size = options.maxInListSize();
        var chunks = new ArrayList<Predicate>();
        for (int from = 0, to; from < values.size(); from = to) {
            to = from + Math.min(size, values.size() - from); // never from + size, which overflows a huge limit
            chunks.add(expression.in(values.subList(from, to)));
        }
        return chunks;
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
