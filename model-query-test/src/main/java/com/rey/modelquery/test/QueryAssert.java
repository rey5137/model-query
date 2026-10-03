package com.rey.modelquery.test;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.Condition;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.SelectField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on a built {@link ModelQuery}, from its public view: {@code conditions()}, {@code orderBy()},
 * {@code select()} and the fetch plan's {@code select()}, plus the plan's child queries through {@link #child}
 * (R-INS-06, D-104). Needs no {@code EntityManager}, metamodel or database.
 *
 * @implSpec api/16 R-INS-06, R-INS-07
 */
@Incubating
public final class QueryAssert<M> extends AbstractAssert<QueryAssert<M>, ModelQuery<?, ?, M>> {

    /** What a failure message calls the query: the query of its model, or a plan's child query. */
    private final String subject;

    QueryAssert(ModelQuery<?, ?, M> actual) {
        this(actual, "the query of " + actual);
    }

    QueryAssert(ModelQuery<?, ?, M> actual, String subject) {
        super(actual, QueryAssert.class);
        this.subject = subject;
    }

    /** The top-level {@code where} conditions are exactly these, in any order (a multiset). */
    public QueryAssert<M> hasFilters(ConditionMatcher... expected) {
        return hasExactly("where", query().conditions().where(), expected);
    }

    /** One of the top-level {@code where} conditions matches {@code expected}. */
    public QueryAssert<M> containsFilter(ConditionMatcher expected) {
        return contains("where", query().conditions().where(), expected);
    }

    /** The query has no {@code where} condition: every filter was skipped, or none was added. */
    public QueryAssert<M> hasNoFilters() {
        return hasExactly("where", query().conditions().where());
    }

    /** The top-level {@code having} conditions are exactly these, in any order (a multiset). */
    public QueryAssert<M> hasHaving(ConditionMatcher... expected) {
        return hasExactly("having", query().conditions().having(), expected);
    }

    /** One of the top-level {@code having} conditions matches {@code expected}. */
    public QueryAssert<M> containsHaving(ConditionMatcher expected) {
        return contains("having", query().conditions().having(), expected);
    }

    /** The query has no {@code having} condition. */
    public QueryAssert<M> hasNoHaving() {
        return hasExactly("having", query().conditions().having());
    }

    /** {@code orderBy()} is exactly these keys, in this order, with their directions and null precedence. */
    @SafeVarargs
    public final QueryAssert<M> isOrderedBy(OrderField<M, ?>... expected) {
        List<? extends OrderField<M, ?>> actualOrder = query().orderBy();
        if (!actualOrder.equals(List.of(expected))) {
            return fail("Expecting %s to be ordered by%n  %s%nbut it is ordered by%n  %s",
                    describe(), render(List.of(expected)), render(actualOrder));
        }
        return this;
    }

    /** The query has no ordering. */
    public QueryAssert<M> isNotOrdered() {
        return isOrderedBy();
    }

    /** {@code select()} is exactly these selections, in this order. */
    @SafeVarargs
    public final QueryAssert<M> hasSelection(SelectField<M, ?>... expected) {
        return selection("selection", query().select().fields(), expected);
    }

    /** {@code select()} holds each of these selections, among others. */
    @SafeVarargs
    public final QueryAssert<M> selectionContains(SelectField<M, ?>... expected) {
        return selectionContains("selection", query().select().fields(), expected);
    }

    /** The fetch plan's selection is exactly these selections, in this order; fails when the query has no plan. */
    @SafeVarargs
    public final QueryAssert<M> hasFetchSelection(SelectField<M, ?>... expected) {
        return selection("fetch plan selection", fetchSelection(), expected);
    }

    /** The fetch plan's selection holds each of these selections, among others; fails without a plan. */
    @SafeVarargs
    public final QueryAssert<M> fetchSelectionContains(SelectField<M, ?>... expected) {
        return selectionContains("fetch plan selection", fetchSelection(), expected);
    }

    /** The query has no fetch plan. */
    public QueryAssert<M> hasNoFetchPlan() {
        if (query().fetch().isPresent()) {
            return fail("Expecting %s to have no fetch plan, but it has one", describe());
        }
        return this;
    }

    /**
     * The query the fetch plan runs to load {@code field}: its filters, order, selection and {@code maxPerParent},
     * asserted with the same matchers (D-104). Fails, naming the children the plan loads, when the plan doesn't load
     * {@code field} or the query has no plan.
     *
     * @param <C> the child model
     */
    @SuppressWarnings("unchecked") // the plan loads field, so the child query is a query of C
    public <C> ChildQueryAssert<C> child(ChildField<M, C> field) {
        Objects.requireNonNull(field, "field");
        String name = query() + "." + field.name(); // a query's toString is its model's name
        FetchPlan<M> plan = query().fetch().orElseThrow(() -> failure(
                "Expecting %s to load the child %s, but it has no fetch plan", describe(), name));
        for (var load : plan.childLoads()) {
            if (load.field().name().equals(field.name())) {
                return withStateOf(this, new ChildQueryAssert<>((ModelQuery<?, ?, C>) load.query(), load.toString(),
                        load.maxPerParent()));
            }
        }
        throw failure("Expecting %s to load the child %s, but its fetch plan loads%n  %s",
                describe(), name, render(plan.childLoads()));
    }

    /** {@code target}, which fails with the description, overriding message and representation of {@code source}. */
    static <A extends AbstractAssert<?, ?>> A withStateOf(AbstractAssert<?, ?> source, A target) {
        target.info.description(source.info.description());
        target.info.overridingErrorMessage(source.info.overridingErrorMessage());
        target.info.useRepresentation(source.info.representation());
        return target;
    }

    /** Fails through AssertJ, so a description and a soft assertion work; returns for a soft assertion to go on. */
    private QueryAssert<M> fail(String message, Object... args) {
        failWithMessage(message, args);
        return this;
    }

    private ModelQuery<?, ?, M> query() {
        isNotNull();
        return actual;
    }

    private String describe() {
        return subject;
    }

    private List<? extends SelectField<M, ?>> fetchSelection() {
        return query().fetch().map(FetchPlan::select).map(select -> select.fields())
                .orElseThrow(() -> failure("Expecting %s to have a fetch plan, but it has none", describe()));
    }

    private QueryAssert<M> selection(String what, List<? extends SelectField<M, ?>> actualFields,
            SelectField<M, ?>[] expected) {
        if (!actualFields.equals(Arrays.asList(expected))) {
            return fail("Expecting the %s of %s to be%n  %s%nbut it is%n  %s",
                    what, describe(), render(Arrays.asList(expected)), render(actualFields));
        }
        return this;
    }

    private QueryAssert<M> selectionContains(String what, List<? extends SelectField<M, ?>> actualFields,
            SelectField<M, ?>[] expected) {
        List<Object> missing = Arrays.stream(expected).filter(field -> !actualFields.contains(field))
                .map(Object.class::cast).toList();
        if (!missing.isEmpty()) {
            return fail("Expecting the %s of %s to contain%n  %s%nbut these are missing%n  %s%nit is%n  %s",
                    what, describe(), render(Arrays.asList(expected)), render(missing), render(actualFields));
        }
        return this;
    }

    private static String render(List<?> items) {
        return items.isEmpty() ? "(none)" : items.stream().map(Objects::toString).toList().toString();
    }

    private QueryAssert<M> contains(String clause, List<Condition> conditions, ConditionMatcher expected) {
        if (conditions.stream().noneMatch(expected::matches)) {
            return fail("Expecting %s to have a %s filter matching%n  %s%nbut none of its %s conditions does:%n%s",
                    describe(), clause, expected, clause, tree(conditions));
        }
        return this;
    }

    private QueryAssert<M> hasExactly(String clause, List<Condition> conditions, ConditionMatcher... matchers) {
        int[] matchOf = assign(conditions, matchers);
        List<String> missing = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (int m = 0; m < matchers.length; m++) {
            if (!isAssigned(matchOf, m)) {
                missing.add(matchers[m].toString());
            }
        }
        for (int c = 0; c < conditions.size(); c++) {
            if (matchOf[c] < 0) {
                unexpected.add(ConditionText.describe(conditions.get(c)));
            }
        }
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            return fail("Expecting %s to have exactly these %s filters, in any order:%n%s%n"
                            + "missing (no condition matched):%n%s%n"
                            + "unexpected (no expectation matched):%n%s%n"
                            + "actual %s conditions:%n%s",
                    describe(), clause, lines(Arrays.stream(matchers).map(ConditionMatcher::toString).toList()),
                    lines(missing), lines(unexpected), clause, tree(conditions));
        }
        return this;
    }

    /** A maximum matching of conditions to matchers; {@code result[c]} is the matcher of condition {@code c} or -1. */
    private static int[] assign(List<Condition> conditions, ConditionMatcher[] matchers) {
        int[] matcherOf = new int[conditions.size()];
        Arrays.fill(matcherOf, -1);
        for (int m = 0; m < matchers.length; m++) {
            augment(m, conditions, matchers, matcherOf, new boolean[conditions.size()]);
        }
        return matcherOf;
    }

    private static boolean augment(int matcher, List<Condition> conditions, ConditionMatcher[] matchers,
            int[] matcherOf, boolean[] seen) {
        for (int c = 0; c < conditions.size(); c++) {
            if (!seen[c] && matchers[matcher].matches(conditions.get(c))) {
                seen[c] = true;
                if (matcherOf[c] < 0 || augment(matcherOf[c], conditions, matchers, matcherOf, seen)) {
                    matcherOf[c] = matcher;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAssigned(int[] matcherOf, int matcher) {
        return Arrays.stream(matcherOf).anyMatch(assigned -> assigned == matcher);
    }

    private static String lines(List<String> items) {
        return items.isEmpty() ? "  (none)" : items.stream().map(item -> "  " + item)
                .reduce((a, b) -> a + System.lineSeparator() + b).orElse("");
    }

    private static String tree(List<Condition> conditions) {
        if (conditions.isEmpty()) {
            return "  (none)";
        }
        var out = new StringBuilder();
        conditions.forEach(condition -> ConditionText.tree(condition, 1, out));
        return out.toString().stripTrailing();
    }
}
