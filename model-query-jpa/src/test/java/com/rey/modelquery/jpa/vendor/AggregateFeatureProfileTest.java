package com.rey.modelquery.jpa.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.AggregateFeature;
import org.junit.jupiter.api.Test;

/** The built-in profiles' answer for each {@link AggregateFeature} (R-VND-29). */
class AggregateFeatureProfileTest {

    @Test
    void ac_vnd_29_first_by_is_a_fact_of_postgresql_and_h2_only() {
        assertThat(BuiltInProfile.POSTGRESQL.aggregates(AggregateFeature.FIRST_BY)).isTrue();
        assertThat(BuiltInProfile.H2.aggregates(AggregateFeature.FIRST_BY)).isTrue();
        assertThat(BuiltInProfile.MYSQL.aggregates(AggregateFeature.FIRST_BY)).isFalse();
        assertThat(BuiltInProfile.MYSQL_CURSOR_FETCH.aggregates(AggregateFeature.FIRST_BY)).isFalse();
        assertThat(BuiltInProfile.OTHER.aggregates(AggregateFeature.FIRST_BY)).isFalse();
    }
}
