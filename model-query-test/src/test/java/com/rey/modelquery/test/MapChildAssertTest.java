package com.rey.modelquery.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MapChildAssertTest {

    @Test
    void ac_ins_25_map_assertions_report_the_bound_and_keep_their_type() {
        var bounded = new MapChildAssert(new Object(), "the map of OrderView.labels", 2);
        var unbounded = new MapChildAssert(new Object(), "the map of OrderView.notes", 0);
        assertThat(bounded.hasMaxPerParent(2)).isSameAs(bounded);
        assertThat(unbounded.hasNoMaxPerParent()).isSameAs(unbounded);
        assertThatThrownBy(() -> unbounded.hasMaxPerParent(1))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the map of OrderView.notes to load at most 1 entries per parent, "
                        + "but it has none");
        assertThatThrownBy(() -> bounded.hasNoMaxPerParent())
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expecting the map of OrderView.labels to have no maxPerParent, but it loads at most 2");
    }
}
