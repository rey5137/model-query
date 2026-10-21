package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.OperatorFeature;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.EnumSet;
import java.util.Set;
import org.hibernate.SessionFactory;

/**
 * Hibernate's answer per Tier-1 database to which operator features it renders: {@code ILIKE} by the dialect, a
 * pattern feature by its {@code mq_*} function in the factory's registry (spec vendor/40 R-VND-23; TCK AC-FLT-24).
 */
class OperatorFeaturesTest {

    @TckTest
    void ac_flt_24_hibernate_serves_ilike_on_postgresql_and_h2_and_regex_match_on_postgresql_alone(TckDatabase db) {
        Set<OperatorFeature> served = EnumSet.noneOf(OperatorFeature.class);
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                HibernateProviderSupport support = new HibernateProviderSupport();
                for (OperatorFeature feature : OperatorFeature.values()) {
                    if (support.operators(sf, feature)) {
                        served.add(feature);
                    }
                }
            }
        });
        // The array, jsonb and full-text functions aren't registered on any dialect yet, so none is served.
        Set<OperatorFeature> expected = switch (db.vendor()) {
            case POSTGRESQL -> EnumSet.of(OperatorFeature.ILIKE, OperatorFeature.REGEX_MATCH);
            case H2 -> EnumSet.of(OperatorFeature.ILIKE);
            case MYSQL -> EnumSet.noneOf(OperatorFeature.class);
        };
        assertThat(served).isEqualTo(expected);
    }
}
