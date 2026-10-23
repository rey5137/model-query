package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.FirstByRowEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.SampleValueEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.src.QOrderSource;
import com.rey.modelquery.tck.vnd.VendorAggregates;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Assumptions;

/**
 * Generated report models run end to end: an {@code AVG} over a {@code BigDecimal} with a {@code filter} and a
 * {@code scale}, {@code FIRST_BY} with an {@code orderBy}, and a {@code @JoinSource} with a {@code @SourceColumn}
 * (spec processor/30 R-PROC-44, R-PROC-45, AC-AGG-26, AC-SRC-42). The decimals of {@code sample_values} are 2.345 and
 * -2.5 (int 1), -2.345 and 2.5 (int 2).
 */
class GeneratedReportModelTest {

    @TckTest
    void ac_agg_26_a_generated_decimal_average_is_exact_filtered_and_rounded(TckDatabase db) {
        var query = QSampleAverages.query().select(SelectSet.of(QSampleAverages.INT_VALUE, QSampleAverages.AVERAGE))
                .orderBy(QSampleAverages.INT_VALUE.asc()).build();

        List<SampleAverages> rows =
                on(db, SampleValueEntity.class, executor -> executor.list(query, Limit.unlimited()));

        // Only the positive value of each group is averaged; 2.345 rounds half away from zero to 2.35.
        assertThat(rows).extracting(SampleAverages::intValue).containsExactly(1, 2);
        assertThat(rows.get(0).average()).isEqualByComparingTo(new BigDecimal("2.35"));
        assertThat(rows.get(1).average()).isEqualByComparingTo(new BigDecimal("2.50"));
    }

    @TckTest
    void ac_agg_26_a_generated_first_by_orders_by_its_key(TckDatabase db) {
        Assumptions.assumeTrue(VendorAggregates.servesFirstBy(db), "MySQL has no array aggregate: MQ4015");
        var query = QFirstByGroups.query().select(SelectSet.of(QFirstByGroups.GRP, QFirstByGroups.TOP))
                .orderBy(QFirstByGroups.GRP.asc()).build();

        List<FirstByGroups> rows =
                on(db, FirstByRowEntity.class, executor -> executor.list(query, Limit.unlimited()));

        // Group 1: the tie on key 30 goes to the lower id ("b"); group 2 has only NULL keys, so the lowest id ("e");
        // group 3 holds a NULL value.
        assertThat(rows).extracting(FirstByGroups::grp, FirstByGroups::top)
                .containsExactly(Tuple.tuple(1, "b"), Tuple.tuple(2, "e"), Tuple.tuple(3, null));
    }

    @TckTest
    void ac_src_42_a_generated_join_source_keeps_an_unmatched_order_and_reads_the_source_column(TckDatabase db) {
        var feed = QOrderSource.source().where(f -> f.lte(QOrderSource.ID, 12L)).build();
        var query = QOrderFeedStatus.query(feed)
                .select(SelectSet.of(QOrderFeedStatus.ID, QOrderFeedStatus.FEED_STATUS))
                .orderBy(QOrderFeedStatus.ID.asc()).build();

        List<OrderFeedStatus> rows = on(db, OrderEntity.class, executor -> executor.list(query, Limit.unlimited()));

        assertThat(rows).hasSizeGreaterThan(12);
        assertThat(rows.subList(0, 12)).allSatisfy(row -> assertThat(row.feedStatus()).isNotNull());
        assertThat(rows.subList(12, rows.size())).allSatisfy(row -> assertThat(row.feedStatus()).isNull());
    }

    private static <E, R> R on(TckDatabase db, Class<E> root, Function<ModelQueryExecutor<E>, R> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            return sf.fromSession(em -> work.apply(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
