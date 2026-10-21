package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.OperatorFeature;
import com.rey.modelquery.hibernate.HibernateOperators;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.tck.vnd.ContainmentOperatorsTest.ContainmentRow;
import com.rey.modelquery.tck.vnd.ContainmentOperatorsTest.R;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** AC-FLT-24 and AC-FLT-32: operator gates run per offline target, only for present values (R-VND-23). */
class OperatorPortabilityTest {

    private static final Target H2 = Target.of(DatabaseVendor.H2, "2.3.232");
    private static final Target POSTGRESQL = Target.of(DatabaseVendor.POSTGRESQL, "15.4");
    private static final Target MYSQL = Target.of(DatabaseVendor.MYSQL, "8.0.36");
    private static final List<Target> TARGETS = List.of(H2, POSTGRESQL, MYSQL);

    private record Case(String operator, OperatorFeature feature, UnaryOperator<Filters<R>> present,
            UnaryOperator<Filters<R>> empty) {}

    private static final List<Case> CATALOGUE = List.of(
            operator(HibernateOperators.regexMatch(), OperatorFeature.REGEX_MATCH, ContainmentOperatorsTest.DOC, "red"),
            operator(HibernateOperators.<String>arrayContains(), OperatorFeature.ARRAY_CONTAINMENT,
                    ContainmentOperatorsTest.TAGS, new String[] {"red"}),
            operator(HibernateOperators.<String>arrayOverlaps(), OperatorFeature.ARRAY_CONTAINMENT,
                    ContainmentOperatorsTest.TAGS, new String[] {"red"}),
            operator(HibernateOperators.<String>jsonbContains(), OperatorFeature.JSONB_CONTAINMENT,
                    ContainmentOperatorsTest.DOC, "{}"),
            operator(HibernateOperators.ilike(LikeMode.EXACT), OperatorFeature.ILIKE,
                    ContainmentOperatorsTest.DOC, "red"));
    private static final List<Case> FULL_TEXT = List.of(operator(HibernateOperators.fullTextMatch("english"),
            OperatorFeature.FULL_TEXT, ContainmentOperatorsTest.DOC, "red"));

    @Test
    void ac_flt_24_present_operators_report_vendor_support_per_target() {
        assertVendorReports(CATALOGUE);
    }

    @Test
    void ac_flt_32_present_full_text_reports_vendor_support_per_target() {
        assertVendorReports(FULL_TEXT);
    }

    @Test
    void ac_flt_24_empty_operators_reach_every_target_without_an_operator_in_the_report() {
        assertEmptyReports(CATALOGUE);
    }

    @Test
    void ac_flt_32_empty_full_text_reaches_every_target_without_an_operator_in_the_report() {
        assertEmptyReports(FULL_TEXT);
    }

    @Test
    void ac_flt_24_custom_profiles_reach_hibernates_provider_check_per_target() {
        assertProviderReports(CATALOGUE);
    }

    @Test
    void ac_flt_32_custom_profiles_reach_hibernates_full_text_check_per_target() {
        assertProviderReports(FULL_TEXT);
    }

    private static void assertVendorReports(List<Case> cases) {
        var check = check(ModelQueryConfig.defaults());
        cases.forEach(c -> call(check, c.operator(), c.present()));
        List<String> lines = report(check);
        assertThat(lines).hasSize(1 + cases.size() * TARGETS.size());
        for (Case c : cases) {
            for (Target target : TARGETS) {
                boolean served = target.equals(POSTGRESQL) || target.equals(H2) && c.feature() == OperatorFeature.ILIKE;
                assertThat(lines).anySatisfy(line -> {
                    assertThat(line).startsWith(c.operator() + " on " + target + ": "
                            + (served ? "REACHED" : "REFUSED " + MqCode.MQ4015));
                    if (!served) {
                        assertThat(line).contains("op(" + c.operator() + ")", "requires " + c.feature());
                    }
                });
            }
        }
    }

    private static void assertEmptyReports(List<Case> cases) {
        var check = check(ModelQueryConfig.defaults());
        // One refused control exposes the whole report, including the otherwise portable empty calls.
        call(check, "control", CATALOGUE.get(0).present());
        for (int i = 0; i < cases.size(); i++) {
            call(check, "empty-" + i, cases.get(i).empty());
        }
        List<String> lines = report(check);
        assertThat(lines).hasSize(1 + (cases.size() + 1) * TARGETS.size());
        for (int i = 0; i < cases.size(); i++) {
            String label = "empty-" + i;
            for (Target target : TARGETS) {
                assertThat(lines).anySatisfy(line -> assertThat(line)
                        .startsWith(label + " on " + target + ": REACHED")
                        .doesNotContainIgnoringCase("mq_regex_match", "mq_array_contains", "mq_array_overlaps",
                                "mq_jsonb_contains", "mq_ts_match", " ilike "));
            }
        }
    }

    private static void assertProviderReports(List<Case> cases) {
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(
                serving(DatabaseVendor.H2), serving(DatabaseVendor.MYSQL)));
        var check = check(config);
        cases.forEach(c -> call(check, c.operator(), c.present()));
        List<String> lines = report(check);
        assertThat(lines).hasSize(1 + cases.size() * TARGETS.size());
        for (Case c : cases) {
            for (Target target : TARGETS) {
                boolean served = target.equals(POSTGRESQL) || target.equals(H2) && c.feature() == OperatorFeature.ILIKE;
                assertThat(lines).anySatisfy(line -> {
                    assertThat(line).startsWith(c.operator() + " on " + target + ": "
                            + (served ? "REACHED" : "REFUSED " + MqCode.MQ4014));
                    if (!served) {
                        assertThat(line).contains("op(" + c.operator() + ")", "requires " + c.feature(),
                                "ProviderSupport#operators");
                    }
                });
            }
        }
    }

    private static <C> Case operator(FilterOperator<C, C> operator, OperatorFeature feature,
            ColumnField<R, ContainmentRow, C> column, C value) {
        return new Case(operator.name(), feature, f -> f.op(operator, column, Optional.of(value)),
                f -> f.op(operator, column, Optional.<C>empty()));
    }

    private static PortabilityCheck check(ModelQueryConfig config) {
        return PortabilityCheck.of(List.of(ContainmentRow.class), config).targets(H2, POSTGRESQL, MYSQL);
    }

    private static void call(PortabilityCheck check, String label, UnaryOperator<Filters<R>> filter) {
        var query = ContainmentOperatorsTest.QUERY.where(filter).build();
        check.call(label, ContainmentRow.class, executor -> executor.list(query, Limit.of(10)));
    }

    private static List<String> report(PortabilityCheck check) {
        try {
            check.assertPortable();
        } catch (AssertionError e) {
            return Arrays.asList(e.getMessage().split("\n"));
        }
        throw new AssertionError("the check passed");
    }

    private static VendorProfile serving(DatabaseVendor vendor) {
        return new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return vendor;
            }

            @Override
            public int maxInListSize() {
                return 1000;
            }

            @Override
            public int maxBindParameters() {
                return 60_000;
            }

            @Override
            public void applyTimeout(Query query, Duration timeout) {
            }

            @Override
            public NullOrdering defaultAscendingNullOrdering() {
                return NullOrdering.NULLS_FIRST;
            }

            @Override
            public boolean operators(OperatorFeature feature) {
                return true;
            }
        };
    }
}
