package com.rey.modelquery.test;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.SelectField;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on the query a fetch plan runs to load one child, from {@link QueryAssert#child}: its filters, its order,
 * its selection and its {@code maxPerParent}, with the matchers and messages of {@link QueryAssert} (D-104). The
 * filters and the order are the {@code ChildQuery}'s, without the key match nor the primary-key tie-breaker.
 *
 * @param <C> the child model
 * @implSpec api/16 R-INS-06, R-INS-07
 */
@Incubating
public final class ChildQueryAssert<C> extends AbstractAssert<ChildQueryAssert<C>, ModelQuery<?, ?, C>> {

    /** The load, as the plan names it: the parent model and the child field. */
    private final String child;
    private final int maxPerParent;

    ChildQueryAssert(ModelQuery<?, ?, C> query, String child, int maxPerParent) {
        super(query, ChildQueryAssert.class);
        this.child = child;
        this.maxPerParent = maxPerParent;
    }

    /** The child filters are exactly these, in any order (a multiset). */
    public ChildQueryAssert<C> hasFilters(ConditionMatcher... expected) {
        query().hasFilters(expected);
        return this;
    }

    /** One of the child filters matches {@code expected}. */
    public ChildQueryAssert<C> containsFilter(ConditionMatcher expected) {
        query().containsFilter(expected);
        return this;
    }

    /** The child query has no filter of its own. */
    public ChildQueryAssert<C> hasNoFilters() {
        query().hasNoFilters();
        return this;
    }

    /** The child order is exactly these keys, in this order, with their directions and null precedence. */
    @SafeVarargs
    public final ChildQueryAssert<C> isOrderedBy(OrderField<C, ?>... expected) {
        query().isOrderedBy(expected);
        return this;
    }

    /** The child query has no order of its own, so it runs in primary-key order (R-FCH-04). */
    public ChildQueryAssert<C> isNotOrdered() {
        query().isNotOrdered();
        return this;
    }

    /**
     * The child query's selection is exactly these selections, in this order: the child plan's own selection, then
     * the child's {@code foreignKey} unless that selection holds it, then the child plan's join plans' selections
     * (R-FCH-05, R-FCH-07). A {@code through} child's query adds no {@code foreignKey} (R-FCH-14).
     */
    @SafeVarargs
    public final ChildQueryAssert<C> hasSelection(SelectField<C, ?>... expected) {
        query().hasSelection(expected);
        return this;
    }

    /** The child query's selection holds each of these selections, among others. */
    @SafeVarargs
    public final ChildQueryAssert<C> selectionContains(SelectField<C, ?>... expected) {
        query().selectionContains(expected);
        return this;
    }

    /** At most {@code expected} children are loaded per parent (R-FCH-11). */
    public ChildQueryAssert<C> hasMaxPerParent(int expected) {
        if (maxPerParent != expected) {
            failWithMessage("Expecting %s to load at most %s children per parent, but %s", subject(), expected,
                    bound());
        }
        return this;
    }

    /** The child has no {@code maxPerParent}: every matching child is loaded. */
    public ChildQueryAssert<C> hasNoMaxPerParent() {
        if (maxPerParent != 0) {
            failWithMessage("Expecting %s to have no maxPerParent, but %s", subject(), bound());
        }
        return this;
    }

    private String subject() {
        return "the child query of " + child;
    }

    private String bound() {
        return maxPerParent == 0 ? "it has none" : "it loads at most " + maxPerParent;
    }

    /** The child query's assertions, failing with this assertion's description and naming the child. */
    private QueryAssert<C> query() {
        return QueryAssert.withStateOf(this, new QueryAssert<>(actual, subject()));
    }
}
