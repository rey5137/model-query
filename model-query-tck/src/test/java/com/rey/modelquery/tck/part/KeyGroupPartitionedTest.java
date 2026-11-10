package com.rey.modelquery.tck.part;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeyGroups;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.PartitionedTables;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.hibernate.SessionFactory;

/**
 * Key groups over a table partitioned by month, on PostgreSQL and MySQL: a grouped {@code byKeys} and a grouped child
 * load return the rows of the ungrouped read, and each byKeys round reads only the partition of its group's range,
 * a child round those from its group's lower bound on, where the ungrouped statement reads every partition (spec
 * api/15 R-FCH-18, engine/20 R-EXE-18; AC-FCH-19, AC-EXE-25). The pruning is read from {@code EXPLAIN} of each
 * captured statement with its binds written in.
 */
class KeyGroupPartitionedTest {

    private static final QueryParam<LocalDateTime> FROM = QueryParam.of("createdFrom", LocalDateTime.class);
    private static final QueryParam<LocalDateTime> TO = QueryParam.of("createdTo", LocalDateTime.class);
    private static final Range ALL = new Range(LocalDateTime.of(1000, 1, 1, 0, 0),
            LocalDateTime.of(9999, 12, 31, 0, 0));

    /** A half-open range of creation times. */
    record Range(LocalDateTime from, LocalDateTime to) {}

    /** A statement and its binds in position order. */
    private record Statement(String sql, List<Object> binds) {}

    private static Range monthRange(int month) {
        return new Range(PartitionedTables.start(month), PartitionedTables.start(month + 1));
    }

    private static final int PARTITIONS = 8;

    /** The partitions from month {@code month} on. */
    private static List<String> from(int month) {
        return java.util.stream.IntStream.range(month, PARTITIONS).mapToObj(KeyGroupPartitionedTest::tag).toList();
    }

    private static String tag(int month) {
        return PartitionedTables.start(month).toLocalDate().toString().substring(0, 7).replace("-", "");
    }

    /** Events of an order created in the range a group binds. */
    private static ModelQuery.Builder<PartEventEntity, Long, PartEvent> ranged() {
        return QPartEvent.query().select(QPartEvent.ALL)
                .where(f -> f.compare(QPartEvent.CREATED_AT, Op.GTE, Expr.param(FROM))
                        .compare(QPartEvent.CREATED_AT, Op.LT, Expr.param(TO)));
    }

    private static KeyGroups.Builder<Long, Range> byMonth() {
        return KeyGroups.by(Long.class, (Long event) -> monthRange(PartitionedTables.monthOf(event / 1000)))
                .bind(FROM, Range::from).bind(TO, Range::to);
    }

    private static ModelQuery<PartEventEntity, Long, PartEvent> ungroupedEvents() {
        return ranged().bind(FROM, ALL.from()).bind(TO, ALL.to()).build();
    }

    private static ModelQuery<PartOrderEntity, Long, PartOrder> orders(KeyGroups<?, ?> groups) {
        return QPartOrder.query().orderBy(QPartOrder.ID.asc())
                .fetch(FetchPlan.of(QPartOrder.ALL).child(QPartOrder.EVENTS, FetchPlan.of(QPartEvent.ALL), c -> {
                    var q = c.orderBy(QPartEvent.ID.asc());
                    return groups == null ? q : q
                                .where(f -> f.compare(QPartEvent.CREATED_AT, Op.GTE, Expr.param(FROM)))
                                .keyGroups(groups);
                }))
                .build();
    }

    // ---- AC-EXE-25

    @TckTest
    void ac_exe_25_a_grouped_by_keys_over_a_partitioned_table_reads_one_partition_per_round(
            TckDatabase db) {
        PartitionedTables.ensure(db);
        // Events of orders 1 and 7 (month 0), 3 (month 2), 6 (month 5) and 12 (month 5), and 99 which has none.
        List<Long> keys = List.of(7000L, 3001L, 1002L, 12000L, 6001L, 99000L, 7001L);
        Map<Long, PartEvent> expected = new LinkedHashMap<>();
        List<Statement> ungrouped = capture(db, ds -> withEvents(ds, ex -> expected.putAll(
                ex.byKeys(ungroupedEvents(), keys))));
        assertThat(expected.keySet()).containsExactly(7000L, 3001L, 1002L, 12000L, 6001L, 7001L);

        for (var groups : List.of(byMonth().assumeInRange(), byMonth().verifyMissing(ALL))) {
            var grouped = ranged().keyGroups(groups).build();
            List<Statement> rounds = capture(db, ds -> withEvents(ds, ex ->
                    assertThat(ex.byKeys(grouped, keys)).containsExactlyEntriesOf(expected)));
            // The rounds of months 0, 2 and 5, in first-occurrence order; the verifyMissing form re-reads key 99000.
            assertThat(rounds.stream().limit(3).map(s -> PartitionedTables.partitionsRead(db, s.sql(), s.binds())))
                    .containsExactly(List.of(tag(0)), List.of(tag(2)), List.of(tag(5)));
        }
        // The ungrouped statement reads every partition, so the pruning above is the groups' doing.
        assertThat(PartitionedTables.partitionsRead(db, ungrouped.get(0).sql(), ungrouped.get(0).binds()))
                .hasSize(PARTITIONS);
    }

    // ---- AC-FCH-19

    @TckTest
    void ac_fch_19_a_grouped_child_load_over_a_partitioned_table_prunes_the_earlier_partitions(
            TckDatabase db) {
        PartitionedTables.ensure(db);
        // A to-many child takes a lower bound only (R-FCH-18): a group's round reads its month's partition
        // and the later ones.
        var groups = KeyGroups.by(Long.class, (Long order) -> PartitionedTables.start(PartitionedTables.monthOf(order)))
                .bind(FROM, from -> from).assumeInRange();
        List<PartOrder> expected = new ArrayList<>();
        List<Statement> ungrouped = capture(db, ds -> withOrders(ds, ex -> expected.addAll(
                ex.list(orders(null), Limit.unlimited()))));
        assertThat(expected).hasSize(PartitionedTables.ORDERS)
                .allSatisfy(order -> assertThat(order.events()).hasSize(3));

        List<Statement> grouped = capture(db, ds -> withOrders(ds, ex ->
                assertThat(ex.list(orders(groups), Limit.unlimited())).isEqualTo(expected)));
        // The page, then one round per month, each reading its month's partition and the later ones.
        var rounds = grouped.stream().filter(s -> s.sql().contains("part_events")).toList();
        assertThat(rounds).hasSize(PartitionedTables.MONTHS);
        assertThat(rounds.stream().map(s -> PartitionedTables.partitionsRead(db, s.sql(), s.binds())))
                .containsExactly(from(0), from(1), from(2), from(3), from(4), from(5));
        var all = ungrouped.stream().filter(s -> s.sql().contains("part_events")).toList();
        assertThat(all).hasSize(1);
        assertThat(PartitionedTables.partitionsRead(db, all.get(0).sql(), all.get(0).binds())).hasSize(PARTITIONS);
    }

    private static void withEvents(DataSource ds, Consumer<ModelQueryExecutor<PartEventEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds, PartOrderEntity.class, PartEventEntity.class)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, PartEventEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }

    private static void withOrders(DataSource ds, Consumer<ModelQueryExecutor<PartOrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds, PartOrderEntity.class, PartEventEntity.class)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, PartOrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }

    /** The statements {@code work} runs over {@code db}, each with its binds. */
    private static List<Statement> capture(TckDatabase db, Consumer<DataSource> work) {
        List<Statement> captured = new ArrayList<>();
        DataSource proxy = ProxyDataSourceBuilder.create(JoinTestSupport.dataSource(db))
                .afterQuery((info, queries) -> queries.forEach(q -> q.getParametersList().forEach(ops -> captured.add(
                        new Statement(q.getQuery(), ops.stream()
                                .sorted(Comparator.comparingInt(
                                        (ParameterSetOperation op) -> (Integer) op.getArgs()[0]))
                                .map(op -> op.getArgs()[1]).toList())))))
                .build();
        work.accept(proxy);
        return captured;
    }
}
