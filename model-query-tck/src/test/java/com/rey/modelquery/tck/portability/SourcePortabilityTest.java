package com.rey.modelquery.tck.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.SourceFeature;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.tck.src.CompositeNode;
import com.rey.modelquery.tck.src.CompositeNodeRow;
import com.rey.modelquery.tck.src.DispatchFeed;
import com.rey.modelquery.tck.src.FolderNode;
import com.rey.modelquery.tck.src.FolderRow;
import com.rey.modelquery.tck.src.OrderFeed;
import com.rey.modelquery.tck.src.OrderSource;
import com.rey.modelquery.tck.src.QCompositeNode;
import com.rey.modelquery.tck.src.QCompositeNodeRow;
import com.rey.modelquery.tck.src.QDispatchFeed;
import com.rey.modelquery.tck.src.QDispatchRow;
import com.rey.modelquery.tck.src.QFolderNode;
import com.rey.modelquery.tck.src.QFolderRow;
import com.rey.modelquery.tck.src.QOrderFeed;
import com.rey.modelquery.tck.src.QOrderSource;
import com.rey.modelquery.tck.src.QShelvedDispatchFeed;
import com.rey.modelquery.tck.src.QWindowFeed;
import com.rey.modelquery.tck.src.QWindowSource;
import com.rey.modelquery.tck.src.WindowSource;
import jakarta.persistence.Query;
import java.time.Duration;
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
                    .contains("SourceRenderer"));
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

    @Test
    void ac_src_16_a_vendor_without_the_window_feature_reports_mq4015_on_its_target_alone() {
        var feed = QWindowFeed.query(QWindowSource.source().build()).select(QWindowFeed.ALL)
                .orderBy(QWindowFeed.ID.asc()).build();
        ModelQueryConfig config = ModelQueryConfig.defaults()
                .vendorProfiles(List.of(mysqlWithout(SourceFeature.WINDOW)));
        List<String> lines = report(check(config).targets(H2, POSTGRESQL, MYSQL)
                .call("WINDOWS", WindowSource.class, x -> x.list(feed, Limit.of(10))));

        assertThat(lines).hasSize(4);
        for (Target target : List.of(H2, POSTGRESQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("WINDOWS on " + target + ": REACHED")
                    .contains("over"));
        }
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("WINDOWS on " + MYSQL + ": REFUSED MQ4015")
                .contains("SourceFeature.WINDOW"));
    }

    @Test
    void ac_src_16_a_vendor_without_the_union_feature_reports_mq4015_on_its_target_alone() {
        var feed = QDispatchRow.query(QDispatchFeed.source().build().union(QShelvedDispatchFeed.source().build()))
                .select(QDispatchRow.ALL).orderBy(QDispatchRow.BRANCH.asc()).build();
        ModelQueryConfig config = ModelQueryConfig.defaults()
                .vendorProfiles(List.of(mysqlWithout(SourceFeature.UNION)));
        List<String> lines = report(check(config).targets(H2, POSTGRESQL, MYSQL)
                .call("DISPATCH", DispatchFeed.class, x -> x.list(feed, Limit.of(10))));

        assertThat(lines).hasSize(4);
        for (Target target : List.of(H2, POSTGRESQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("DISPATCH on " + target + ": REACHED")
                    .contains("union"));
        }
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("DISPATCH on " + MYSQL + ": REFUSED MQ4015")
                .contains("SourceFeature.UNION"));
    }

    @Test
    void ac_src_14_the_recursion_ceiling_is_the_targets_own() {
        ModelQuery<FolderNode, ?, FolderRow> served = tree(998);
        ModelQuery<FolderNode, ?, FolderRow> refused = tree(999);
        List<String> lines = report(check(ModelQueryConfig.defaults()).targets(H2, POSTGRESQL, MYSQL)
                .call("DEPTH-998", FolderNode.class, x -> x.list(served, Limit.of(10)))
                .call("DEPTH-999", FolderNode.class, x -> x.list(refused, Limit.of(10))));

        assertThat(lines).hasSize(7);
        for (Target target : List.of(H2, POSTGRESQL, MYSQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("DEPTH-998 on " + target + ": REACHED")
                    .contains("with recursive"));
        }
        // H2 and PostgreSQL set no ceiling of their own, so 999 is a level nothing refuses before the statement.
        for (Target target : List.of(H2, POSTGRESQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("DEPTH-999 on " + target + ": REACHED"));
        }
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("DEPTH-999 on " + MYSQL + ": REFUSED MQ4015")
                .contains("maxRecursionDepth"));
    }

    @Test
    void ac_src_21_h2_reports_mq4015_for_a_composite_node_key_with_no_database() {
        List<String> lines = report(check(ModelQueryConfig.defaults()).targets(H2, POSTGRESQL, MYSQL)
                .call("COMPOSITE", CompositeNode.class, x -> x.list(compositeTree(), Limit.of(10))));

        assertThat(lines).hasSize(4);
        // H2's own profile answers RECURSIVE_COMPOSITE_KEY false; PostgreSQL's and MySQL's answer true (R-VND-21).
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("COMPOSITE on " + H2 + ": REFUSED MQ4015")
                .contains("SourceFeature.RECURSIVE_COMPOSITE_KEY"));
        for (Target target : List.of(POSTGRESQL, MYSQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("COMPOSITE on " + target + ": REACHED")
                    .contains("with recursive"));
        }
    }

    @Test
    void ac_src_21_a_profile_without_the_composite_key_feature_reports_mq4015_on_its_target_too() {
        ModelQueryConfig config = ModelQueryConfig.defaults()
                .vendorProfiles(List.of(mysqlWithout(SourceFeature.RECURSIVE_COMPOSITE_KEY)));
        List<String> lines = report(check(config).targets(H2, POSTGRESQL, MYSQL)
                .call("COMPOSITE", CompositeNode.class, x -> x.list(compositeTree(), Limit.of(10))));

        assertThat(lines).hasSize(4);
        // The refusal is the profile's answer, not the vendor's name (R-VND-21, INV-6): a MySQL profile that answers
        // false is refused as H2's is, and PostgreSQL's built-in profile still reaches the statement.
        for (Target target : List.of(H2, MYSQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("COMPOSITE on " + target + ": REFUSED MQ4015")
                    .contains("SourceFeature.RECURSIVE_COMPOSITE_KEY"));
        }
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .startsWith("COMPOSITE on " + POSTGRESQL + ": REACHED")
                .contains("with recursive"));
    }

    /** A recursive read of the composite tree to level 3 (api/17 AC-SRC-21, R-SRC-07). */
    private static ModelQuery<CompositeNode, ?, CompositeNodeRow> compositeTree() {
        SourceQuery<CompositeNode> source = QCompositeNode.source().where(f -> f.eq(QCompositeNode.ID, 1L))
                .recursive(step -> step.childKey(QCompositeNode.PARENT_ID).parentKey(QCompositeNode.ID), 3).build();
        return QCompositeNodeRow.query(source).select(QCompositeNodeRow.ALL)
                .orderBy(QCompositeNodeRow.ID.asc()).build();
    }

    /** A recursive read of the folder tree to level {@code maxDepth} (api/17 R-SRC-07). */
    private static ModelQuery<FolderNode, ?, FolderRow> tree(int maxDepth) {
        SourceQuery<FolderNode> source = QFolderNode.source().where(f -> f.eq(QFolderNode.ID, 1L))
                .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
        return QFolderRow.query(source).select(QFolderRow.ALL).orderBy(QFolderRow.ID.asc()).build();
    }

    /** MySQL's profile answering every source feature but {@code refused}, as one written before it existed. */
    private static VendorProfile mysqlWithout(SourceFeature refused) {
        return new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return DatabaseVendor.MYSQL;
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
            public boolean sources(SourceFeature feature) {
                return feature != refused;
            }
        };
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
