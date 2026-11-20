package com.rey.modelquery.test;

import com.rey.modelquery.annotations.Incubating;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on what a fetch plan loads into a value child or a map child, from {@link QueryAssert#values} or
 * {@link QueryAssert#map}: that the plan loads the field, and its {@code maxPerParent}, with the messages of
 * {@link QueryAssert} (D-257). A value child's load has no filter, order or selection of its own to assert, and a
 * map's entries come in no order.
 *
 * @implSpec api/16 R-INS-06, D-257
 */
@Incubating
public final class ValueChildAssert extends AbstractAssert<ValueChildAssert, Object> {

    /** The load, as a failure names it: {@code the values of OrderView.tags}, {@code the map of OrderView.labels}. */
    private final String load;
    /** What the load counts per parent: {@code values} or {@code entries}. */
    private final String rows;
    private final int maxPerParent;

    ValueChildAssert(Object field, String load, String rows, int maxPerParent) {
        super(field, ValueChildAssert.class);
        this.load = load;
        this.rows = rows;
        this.maxPerParent = maxPerParent;
    }

    /** At most {@code expected} values, or entries, are loaded per parent, counted in rows (R-FCH-11). */
    public ValueChildAssert hasMaxPerParent(int expected) {
        if (maxPerParent != expected) {
            failWithMessage("Expecting %s to load at most %s %s per parent, but %s", load, expected, rows, bound());
        }
        return this;
    }

    /** The load has no {@code maxPerParent}: every value, or entry, is loaded. */
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
