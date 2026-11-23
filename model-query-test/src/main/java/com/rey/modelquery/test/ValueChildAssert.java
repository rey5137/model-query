package com.rey.modelquery.test;

import com.rey.modelquery.annotations.Incubating;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on what a fetch plan loads into a value child, from {@link QueryAssert#values}: that the plan loads the
 * field, and its {@code maxPerParent}, with the messages of {@link QueryAssert} (D-257). A value child's load has no
 * filter, order or selection of its own to assert.
 *
 * @implSpec api/16 R-INS-06, D-257
 */
@Incubating
public final class ValueChildAssert extends AbstractAssert<ValueChildAssert, Object> {

    /** The load, as a failure names it: {@code the values of OrderView.tags}. */
    private final String load;
    private final int maxPerParent;

    ValueChildAssert(Object field, String load, int maxPerParent) {
        super(field, ValueChildAssert.class);
        this.load = load;
        this.maxPerParent = maxPerParent;
    }

    /** At most {@code expected} values are loaded per parent, counted in rows (R-FCH-11). */
    public ValueChildAssert hasMaxPerParent(int expected) {
        if (maxPerParent != expected) {
            failWithMessage("Expecting %s to load at most %s values per parent, but %s", load, expected, bound());
        }
        return this;
    }

    /** The load has no {@code maxPerParent}: every value is loaded. */
    public ValueChildAssert hasNoMaxPerParent() {
        if (maxPerParent != 0) {
            failWithMessage("Expecting %s to have no maxPerParent, but %s", load, bound());
        }
        return this;
    }

    private String bound() {
        return maxPerParent == 0 ? "it has none" : "it loads at most " + maxPerParent;
    }
}
