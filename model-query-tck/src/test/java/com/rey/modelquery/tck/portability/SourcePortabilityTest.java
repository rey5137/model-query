package com.rey.modelquery.tck.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.tck.src.OrderFeed;
import com.rey.modelquery.tck.src.OrderSource;
import com.rey.modelquery.tck.src.QOrderFeed;
import com.rey.modelquery.tck.src.QOrderSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC-SRC-16: {@code MQ4014} and {@code MQ4015} are reported per declared target without a database. Each check runs
 * one call over a source on each target's offline factory (spec api/17 R-SRC-11).
 */
class SourcePortabilityTest {

    private static final Target H2 = Target.of(DatabaseVendor.H2, "2.3.232");
    private static final Target POSTGRESQL = Target.of(DatabaseVendor.POSTGRESQL, "15.4");
    private static final Target MYSQL = Target.of(DatabaseVendor.MYSQL, "8.0.36");
    /** Tier 2, whose vendor has no profile of its own, so it takes the conservative {@code OTHER} one (R-VND-21). */
    private static final Target MARIADB = Target.of(DatabaseVendor.MARIADB, "10.11.6");

    private static final ModelQuery<OrderSource, ?, OrderFeed> FEED = QOrderFeed
            .query(QOrderSource.source().build()).select(QOrderFeed.ALL).orderBy(QOrderFeed.ID.asc()).build();

    @Test
    void ac_src_16_a_provider_without_source_support_reports_mq4014_on_every_declared_target() {
        List<String> lines = new ArrayList<>();
        // The boot's provider support is Hibernate's, found only inside serving(...), so every target's factory boots
        // and its queries are refused for the missing hook (R-SRC-11).
        NoSourceProviderSupport.serving(() -> lines.addAll(report(check(ModelQueryConfig.defaults())
                .targets(H2, POSTGRESQL, MYSQL).call("FEED", OrderSource.class, x -> x.list(FEED, Limit.of(10))))));

        assertThat(lines).hasSize(4);
        for (Target target : List.of(H2, POSTGRESQL, MYSQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("FEED on " + target + ": REFUSED MQ4014")
                    .contains("SourceSupport"));
        }
    }

    @Test
    void ac_src_16_a_vendor_without_the_cte_feature_reports_mq4015_on_its_target_alone() {
        List<String> lines = report(check(ModelQueryConfig.defaults()).targets(H2, POSTGRESQL, MYSQL, MARIADB)
                .call("FEED", OrderSource.class, x -> x.list(FEED, Limit.of(10))));

        assertThat(lines).hasSize(5);
        for (Target target : List.of(H2, POSTGRESQL, MYSQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("FEED on " + target + ": REACHED")
                    .contains("orders"));
        }
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("FEED on " + MARIADB + ": REFUSED MQ4015")
                .contains("SourceFeature.CTE"));
    }

    private static PortabilityCheck check(ModelQueryConfig config) {
        List<Class<?>> classes = OfflineBootTest.TCK_UNIT.stream().<Class<?>>map(SourcePortabilityTest::load).toList();
        return PortabilityCheck.of(classes, config);
    }

    /** The report lines of a check that fails, the header first. */
    private static List<String> report(PortabilityCheck check) {
        try {
            check.assertPortable();
        } catch (AssertionError e) {
            return Arrays.asList(e.getMessage().split("\n"));
        }
        throw new AssertionError("the check passed");
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
