package com.rey.modelquery.test;

import com.rey.modelquery.annotations.Incubating;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on a map child load from {@link QueryAssert#map} (D-257, D-263).
 *
 * @implSpec api/16 R-INS-06
 */
@Incubating
public final class MapChildAssert extends AbstractAssert<MapChildAssert, Object> {

    private final String load;
    private final int maxPerParent;

    MapChildAssert(Object field, String load, int maxPerParent) {
        super(field, MapChildAssert.class);
        this.load = load;
        this.maxPerParent = maxPerParent;
    }

    /** At most {@code expected} entries are loaded per parent, counted in rows (R-FCH-11). */
    public MapChildAssert hasMaxPerParent(int expected) {
        if (maxPerParent != expected) {
            failWithMessage("Expecting %s to load at most %s entries per parent, but %s", load, expected, bound());
        }
        return this;
    }

    /** The load has no {@code maxPerParent}. */
    public MapChildAssert hasNoMaxPerParent() {
        if (maxPerParent != 0) {
            failWithMessage("Expecting %s to have no maxPerParent, but %s", load, bound());
        }
        return this;
    }

    private String bound() {
        return maxPerParent == 0 ? "it has none" : "it loads at most " + maxPerParent;
    }
}
