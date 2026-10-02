package com.rey.modelquery.test;

import com.rey.modelquery.annotations.Incubating;
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
 * Assertions on a built {@link ModelQuery}, from its public view only: {@code conditions()}, {@code orderBy()},
 * {@code select()} and the fetch plan's {@code select()} (R-INS-06). Needs no {@code EntityManager}, metamodel or
 * database. Filters of a fetch plan's child queries are not in the view (Q-13).
 *
 * @implSpec api/16 R-INS-06, R-INS-07
 */
@Incubating
public final class QueryAssert extends AbstractAssert<QueryAssert, ModelQuery<?, ?, ?>> {

    QueryAssert(ModelQuery<?, ?, ?> actual) {
        super(actual, QueryAssert.class);
    }

    /** The top-level {@code where} conditions are exactly these, in any order (a multiset). */
    public QueryAssert hasFilters(ConditionMatcher... expected) {
        return hasExactly("where", query().conditions().where(), expected);
    }

    /** One of the top-level {@code where} conditions matches {@code expected}. */
    public QueryAssert containsFilter(ConditionMatcher expected) {
        return contains("where", query().conditions().where(), expected);
    }

    /** The query has no {@code where} condition: every filter was skipped, or none was added. */
    public QueryAssert hasNoFilters() {
        return hasExactly("where", query().conditions().where());
    }

    /** The top-level {@code having} conditions are exactly these, in any order (a multiset). */
    public QueryAssert hasHaving(ConditionMatcher... expected) {
        return hasExactly("having", query().conditions().having(), expected);
    }

    /** One of the top-level {@code having} conditions matches {@code expected}. */
    public QueryAssert containsHaving(ConditionMatcher expected) {
        return contains("having", query().conditions().having(), expected);
    }

    /** The query has no {@code having} condition. */
    public QueryAssert hasNoHaving() {
        return hasExactly("having", query().conditions().having());
    }

    /** {@code orderBy()} is exactly these keys, in this order, with their directions and null precedence. */
    public QueryAssert isOrderedBy(OrderField<?, ?>... expected) {
        List<OrderField<?, ?>> actualOrder = new ArrayList<>(query().orderBy());
        if (!actualOrder.equals(List.of(expected))) {
            return fail("Expecting %s to be ordered by%n  %s%nbut it is ordered by%n  %s",
                    describe(), render(List.of(expected)), render(actualOrder));
        }
        return this;
    }

    /** The query has no ordering. */
    public QueryAssert isNotOrdered() {
        return isOrderedBy();
    }

    /** {@code select()} is exactly these selections, in this order. */
    public QueryAssert hasSelection(SelectField<?, ?>... expected) {
        return selection("selection", query().select().fields(), expected);
    }

    /** {@code select()} holds each of these selections, among others. */
    public QueryAssert selectionContains(SelectField<?, ?>... expected) {
        return selectionContains("selection", query().select().fields(), expected);
    }

    /** The fetch plan's selection is exactly these selections, in this order; fails when the query has no plan. */
    public QueryAssert hasFetchSelection(SelectField<?, ?>... expected) {
        return selection("fetch plan selection", fetchSelection(), expected);
    }

    /** The fetch plan's selection holds each of these selections, among others; fails without a plan. */
    public QueryAssert fetchSelectionContains(SelectField<?, ?>... expected) {
        return selectionContains("fetch plan selection", fetchSelection(), expected);
    }

    /** The query has no fetch plan. */
    public QueryAssert hasNoFetchPlan() {
        if (query().fetch().isPresent()) {
            return fail("Expecting %s to have no fetch plan, but it has one", describe());
        }
        return this;
    }

    /** Fails through AssertJ, so a description and a soft assertion work; returns for a soft assertion to go on. */
    private QueryAssert fail(String message, Object... args) {
        failWithMessage(message, args);
        return this;
    }

    private ModelQuery<?, ?, ?> query() {
        isNotNull();
        return actual;
    }

    private String describe() {
        return "the query of " + actual;
    }

    private List<? extends SelectField<?, ?>> fetchSelection() {
        return query().fetch().map(FetchPlan::select).map(select -> select.fields())
                .orElseThrow(() -> failure("Expecting %s to have a fetch plan, but it has none", describe()));
    }

    private QueryAssert selection(String what, List<? extends SelectField<?, ?>> actualFields,
            SelectField<?, ?>[] expected) {
        if (!new ArrayList<>(actualFields).equals(Arrays.asList(expected))) {
            return fail("Expecting the %s of %s to be%n  %s%nbut it is%n  %s",
                    what, describe(), render(Arrays.asList(expected)), render(actualFields));
        }
        return this;
    }

    private QueryAssert selectionContains(String what, List<? extends SelectField<?, ?>> actualFields,
            SelectField<?, ?>[] expected) {
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

    private QueryAssert contains(String clause, List<Condition> conditions, ConditionMatcher expected) {
        if (conditions.stream().noneMatch(expected::matches)) {
            return fail("Expecting %s to have a %s filter matching%n  %s%nbut none of its %s conditions does:%n%s",
                    describe(), clause, expected, clause, tree(conditions));
        }
        return this;
    }

    private QueryAssert hasExactly(String clause, List<Condition> conditions, ConditionMatcher... matchers) {
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
