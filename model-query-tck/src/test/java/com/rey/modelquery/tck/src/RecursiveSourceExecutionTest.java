package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.VendorSources;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * A recursive source over a tree of folders: the {@code with recursive} CTE, the level each step adds, the two checks
 * that make a tree deeper than {@code maxDepth} and a cyclic graph loud, and the vendor ceiling (spec api/17 AC-SRC-12,
 * AC-SRC-13, AC-SRC-14, R-SRC-07, R-SRC-08; RFC 0013 §5).
 */
class RecursiveSourceExecutionTest {

    /** The executor's log, where R-EXE-03's warning lands (the same logger {@code tck/exe/ExecutionTest} captures). */
    private static final String EXECUTOR_LOG = "com.rey.modelquery.jpa.DefaultModelQueryExecutor";

    // ---- AC-SRC-12

    @TckTest
    void ac_src_12_a_tree_read_by_a_recursive_source_returns_every_node_at_its_level(TckDatabase db)
            throws SQLException {
        List<FolderRow> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-12-tree", ds -> withExecutor(ds, FolderNode.class,
                executor -> rows.addAll(executor.list(rows(tree(TckFixture.FOLDER_TREE_ROOT, 3)),
                        Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(rows).hasSize(TckFixture.FOLDER_TREE_NODES);
        assertThat(nodes(rows)).containsExactlyInAnyOrder("1|null|node-1|0", "2|1|node-2|1", "3|1|node-3|1",
                "4|2|node-4|2", "5|2|node-5|2", "6|3|node-6|2");
        assertThat(nodes(rows)).containsExactlyInAnyOrderElementsOf(rows(db, """
                with recursive folder_tree (id, parent_id, name, lvl) as (
                    select id, parent_id, name, 0 from folder_nodes where id = 1
                  union all
                    select n.id, n.parent_id, n.name, t.lvl + 1 from folder_nodes n
                    join folder_tree t on n.parent_id = t.id)
                select id, parent_id, name, lvl from folder_tree order by id"""));
    }

    @TckTest
    void ac_src_12_the_count_of_a_recursive_source_reads_the_same_rows(TckDatabase db) {
        long[] counted = new long[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class,
                executor -> counted[0] = executor.count(rows(tree(TckFixture.FOLDER_TREE_ROOT, 3)))));

        assertThat(sql).isNotEmpty();
        assertThat(counted[0]).isEqualTo(TckFixture.FOLDER_TREE_NODES);
    }

    // ---- AC-SRC-13

    @TckTest
    void ac_src_13_a_tree_deeper_than_max_depth_is_mq2701(TckDatabase db) {
        Throwable[] refusal = new Throwable[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor ->
                refusal[0] = catchThrowable(() -> executor.list(rows(tree(TckFixture.FOLDER_CHAIN_ROOT, 3)),
                        Limit.unlimited()))));
        SqlSnapshots.assertMatches(db, "src-13-depth", sql);

        assertThat(refusal[0]).isInstanceOf(ModelQueryExecutionException.class);
        assertThat(((ModelQueryExecutionException) refusal[0]).code()).isEqualTo(MqCode.MQ2701);
        // The chain is 10..15, so the row one level past maxDepth 3 is 14, the first key the check reads.
        assertThat(refusal[0].getMessage()).contains("maxDepth(3)").contains("key 14");
    }

    @TckTest
    void ac_src_13_the_count_of_a_tree_deeper_than_max_depth_is_mq2701_too(TckDatabase db) {
        Throwable[] refusal = new Throwable[1];
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor ->
                refusal[0] = catchThrowable(() -> executor.count(rows(tree(TckFixture.FOLDER_CHAIN_ROOT, 3))))));

        assertThat(refusal[0]).isInstanceOf(ModelQueryExecutionException.class);
        assertThat(((ModelQueryExecutionException) refusal[0]).code()).isEqualTo(MqCode.MQ2701);
        assertThat(refusal[0].getMessage()).contains("FolderRow").contains("maxDepth(3)").contains("key 14");
    }

    @TckTest
    void ac_src_13_a_cyclic_graph_is_mq2702_and_no_marked_row_reaches_the_result(TckDatabase db) {
        List<FolderRow> collected = new ArrayList<>();
        Throwable[] refusal = new Throwable[1];
        // A stream needs an active transaction on some vendors (R-EXE-08), so it runs in one.
        List<String> sql = SqlSnapshots.capture(db, ds -> withTransaction(ds, FolderNode.class, executor ->
                refusal[0] = catchThrowable(() -> executor.stream(rows(cycle(5)), Limit.unlimited(), stream -> {
                    stream.forEach(collected::add);
                    return collected.size();
                }))));
        SqlSnapshots.assertMatches(db, "src-13-cycle", sql);

        assertThat(refusal[0]).isInstanceOf(ModelQueryExecutionException.class);
        assertThat(((ModelQueryExecutionException) refusal[0]).code()).isEqualTo(MqCode.MQ2702);
        // The cycle is 100 -> 101 -> 100, so 100 is the first key the mark is set on.
        assertThat(refusal[0].getMessage()).contains("cycle").contains("key 100");
        // The outer query filters on `not mark`, and the check runs on the statement's first row: nothing is emitted.
        assertThat(collected).isEmpty();
        assertThat(sql).anySatisfy(statement -> assertThat(statement).contains("mqCycleMark"));
    }

    @TckTest
    void ac_src_13_a_cyclic_graph_is_mq2702_for_a_count_too(TckDatabase db) {
        Throwable[] refusal = new Throwable[1];
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor ->
                refusal[0] = catchThrowable(() -> executor.count(rows(cycle(5))))));

        assertThat(refusal[0]).isInstanceOf(ModelQueryExecutionException.class);
        assertThat(((ModelQueryExecutionException) refusal[0]).code()).isEqualTo(MqCode.MQ2702);
        assertThat(refusal[0].getMessage()).contains("FolderRow").contains("cycle").contains("key 100");
    }

    // ---- AC-SRC-13, a count over the groups of a recursive source (the M25.5 fix, R-EXE-03, R-EXE-05)

    @TckTest
    void ac_src_13_a_grouped_count_over_a_too_deep_tree_is_mq2701(TckDatabase db) {
        Throwable[] refusal = new Throwable[1];
        List<String> warnings = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor ->
                warnings.addAll(capturingWarnings(() -> refusal[0] = catchThrowable(
                        () -> executor.count(levels(tree(TckFixture.FOLDER_CHAIN_ROOT, 3))))))));

        assertThat(refusal[0]).isInstanceOf(ModelQueryExecutionException.class);
        assertThat(((ModelQueryExecutionException) refusal[0]).code()).isEqualTo(MqCode.MQ2701);
        assertThat(refusal[0].getMessage()).contains("maxDepth(3)").contains("key 14");
        assertThat(sql).isNotEmpty();
        // A count over groups over a source that carries checks counts client-side and keeps the checks (R-EXE-05),
        // so it logs no "counts its rows in memory" warning of R-EXE-03 (RFC 0014 §3).
        assertThat(warnings).noneMatch(warning -> warning.contains("counts its rows in memory"));
    }

    @TckTest
    void ac_src_13_a_grouped_count_over_a_cyclic_graph_is_mq2702(TckDatabase db) {
        Throwable[] refusal = new Throwable[1];
        List<String> warnings = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor ->
                warnings.addAll(capturingWarnings(() -> refusal[0] = catchThrowable(
                        () -> executor.count(levels(cycle(5))))))));

        assertThat(refusal[0]).isInstanceOf(ModelQueryExecutionException.class);
        assertThat(((ModelQueryExecutionException) refusal[0]).code()).isEqualTo(MqCode.MQ2702);
        assertThat(refusal[0].getMessage()).contains("cycle").contains("key 100");
        assertThat(sql).isNotEmpty();
        assertThat(warnings).noneMatch(warning -> warning.contains("counts its rows in memory"));
    }

    @TckTest
    void ac_src_13_a_grouped_count_over_a_healthy_tree_is_its_number_of_levels(TckDatabase db) {
        long[] counted = new long[1];
        List<FolderLevelTotals> groups = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor ->
                warnings.addAll(capturingWarnings(() -> {
                    counted[0] = executor.count(levels(tree(TckFixture.FOLDER_TREE_ROOT, 5)));
                    groups.addAll(executor.list(levels(tree(TckFixture.FOLDER_TREE_ROOT, 5)), Limit.unlimited()));
                }))));

        // The tree is 1, then 2 and 3, then 4, 5 and 6: three levels, so three groups, counted from one statement.
        assertThat(counted[0]).isEqualTo(3);
        assertThat(groups.stream().map(group -> group.level() + ":" + group.nodes()).toList())
                .containsExactlyInAnyOrder("0:1", "1:2", "2:3");
        assertThat(sql).hasSize(2);
        assertThat(warnings).noneMatch(warning -> warning.contains("counts its rows in memory"));
    }

    @TckTest
    void ac_src_13_a_grouped_count_matching_no_group_reads_no_row_and_so_no_check(TckDatabase db) {
        long[] counted = new long[1];
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> counted[0] = executor.count(
                QFolderLevelTotals.query(tree(TckFixture.FOLDER_CHAIN_ROOT, 3))
                        .select(SelectSet.of(QFolderLevelTotals.LEVEL, QFolderLevelTotals.NODES))
                        .where(f -> f.gt(QFolderLevelTotals.LEVEL, 99)).build())));

        // The chain is too deep for maxDepth 3, but no group matches, so no row carries the checks: the same
        // empty-result limit as list (R-SRC-08, RFC 0014 §3).
        assertThat(counted[0]).isZero();
    }

    // ---- AC-SRC-25, the stream's check site reads the first row before the stream is handed on

    @TckTest
    void ac_src_25_a_stream_over_a_too_deep_tree_is_mq2701_before_its_body_runs(TckDatabase db) {
        boolean[] ran = new boolean[1];
        Throwable[] refusal = new Throwable[1];
        SqlSnapshots.capture(db, ds -> withTransaction(ds, FolderNode.class, executor ->
                refusal[0] = catchThrowable(() -> executor.stream(rows(tree(TckFixture.FOLDER_CHAIN_ROOT, 3)),
                        Limit.unlimited(), stream -> {
                            ran[0] = true;
                            return stream.count();
                        }))));

        assertThat(refusal[0]).isInstanceOf(ModelQueryExecutionException.class);
        assertThat(((ModelQueryExecutionException) refusal[0]).code()).isEqualTo(MqCode.MQ2701);
        assertThat(refusal[0].getMessage()).contains("maxDepth(3)").contains("key 14");
        assertThat(ran[0]).isFalse();
    }

    @TckTest
    void ac_src_25_a_stream_over_a_healthy_tree_hands_on_every_row_its_check_read(TckDatabase db) {
        List<Long> ids = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withTransaction(ds, FolderNode.class, executor ->
                executor.stream(rows(tree(TckFixture.FOLDER_TREE_ROOT, 5)), Limit.unlimited(),
                        stream -> ids.addAll(stream.map(FolderRow::id).toList()))));

        // The check read the first row; the stream still delivers it, ahead of the rest, in the query's order.
        assertThat(ids).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    // ---- MQ1901 and MQ2212 on a recursive source, before any statement (AC-SRC-11, R-SRC-08, R-SRC-09)

    @TckTest
    void ac_src_11_mq1901_is_raised_on_a_recursive_source_before_any_statement(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> assertThatThrownBy(() -> QFolderRow
                .query(tree(TckFixture.FOLDER_TREE_ROOT, 3)).select(QFolderRow.ALL)
                .orderBy(QFolderRow.ID.asc()).keyset().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1901))
                .hasMessageContaining("no unique key"));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_11_mq2212_is_raised_on_a_recursive_source_before_any_statement(TckDatabase db) {
        // The key's own type is kept here: byKeys and one identify a row by it, which is what the refusal is about.
        var feed = QFolderRow.query(tree(TckFixture.FOLDER_TREE_ROOT, 3)).select(QFolderRow.ALL)
                .orderBy(QFolderRow.ID.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            assertThatThrownBy(() -> executor.byKeys(feed, List.of(TckFixture.FOLDER_TREE_ROOT)))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2212))
                    .hasMessageContaining("byKeys");
            assertThatThrownBy(() -> executor.one(feed, TckFixture.FOLDER_TREE_ROOT))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2212));
        }));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_11_mq2212_is_raised_for_a_fetch_plan_with_a_child_over_a_recursive_source(TckDatabase db) {
        var plan = FetchPlan.of(QFolderChildrenRow.ALL).child(QFolderChildrenRow.CHILDREN,
                FetchPlan.of(QFolderChild.ALL));
        var feed = QFolderChildrenRow.query(tree(TckFixture.FOLDER_TREE_ROOT, 3)).orderBy(QFolderChildrenRow.ID.asc())
                .fetch(plan).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            assertThatThrownBy(() -> executor.list(feed, Limit.of(10)))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2212));
            // A stream has no page, so it refuses any plan with a child on its own (MQ2605), before the source is read.
            assertThatThrownBy(() -> executor.stream(feed, Limit.of(10), Stream::toList))
                    .isInstanceOfSatisfying(ModelQueryException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2605));
        }));

        assertThat(sql).isEmpty();
    }

    // ---- AC-SRC-14

    @TckTest
    void ac_src_14_a_max_depth_of_998_walks_the_deep_chain_on_every_vendor(TckDatabase db) {
        List<FolderRow> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class,
                executor -> rows.addAll(executor.list(rows(tree(TckFixture.FOLDER_DEEP_ROOT, 998)),
                        Limit.unlimited()))));

        assertThat(rows).hasSize(TckFixture.FOLDER_DEEP_NODES);
        assertThat(rows.get(0).id()).isEqualTo(TckFixture.FOLDER_DEEP_ROOT);
        assertThat(rows.get(0).level()).isZero();
        assertThat(rows.get(rows.size() - 1).level()).isEqualTo(998);
    }

    // ---- AC-SRC-21, a node key of more than one column

    @TckTest
    void ac_src_21_h2_refuses_a_composite_node_key_before_any_statement(TckDatabase db) {
        Assumptions.assumeTrue(VendorSources.refusesCompositeNodeKey(db),
                "H2 rejects the multi-column cycle clause; PostgreSQL and MySQL render it (RFC 0014 §7)");
        Throwable[] refusal = new Throwable[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CompositeNode.class, executor ->
                refusal[0] = catchThrowable(() -> executor.list(compositeTree(), Limit.unlimited()))));

        assertThat(refusal[0]).isInstanceOf(ModelQueryConfigurationException.class);
        assertThat(((ModelQueryConfigurationException) refusal[0]).code()).isEqualTo(MqCode.MQ4015);
        assertThat(refusal[0].getMessage()).contains("RECURSIVE_COMPOSITE_KEY");
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_21_postgres_and_mysql_read_a_tree_over_a_composite_id(TckDatabase db) {
        Assumptions.assumeTrue(!VendorSources.refusesCompositeNodeKey(db),
                "H2 refuses a composite node key with MQ4015");
        List<CompositeNodeRow> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, CompositeNode.class,
                executor -> rows.addAll(executor.list(compositeTree(), Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(rows).hasSize(TckFixture.COMPOSITE_TREE_NODES);
        assertThat(rows.stream().map(row -> row.tenantId() + "|" + row.id() + "|" + row.level()).toList())
                .containsExactlyInAnyOrder("1|1|0", "1|2|1", "1|3|1", "1|4|2", "1|5|2", "1|6|2");
    }

    /** The tree {@code composite_nodes} holds, from its root to level 3 (AC-SRC-21, R-SRC-07). */
    private static ModelQuery<CompositeNode, ?, CompositeNodeRow> compositeTree() {
        SourceQuery<CompositeNode> source = QCompositeNode.source()
                .where(f -> f.eq(QCompositeNode.ID, TckFixture.COMPOSITE_TREE_ROOT))
                .recursive(step -> step.childKey(QCompositeNode.PARENT_ID).parentKey(QCompositeNode.ID), 3).build();
        return QCompositeNodeRow.query(source).select(QCompositeNodeRow.ALL)
                .orderBy(QCompositeNodeRow.ID.asc()).build();
    }

    private static SourceQuery<FolderNode> tree(long root, int maxDepth) {
        return QFolderNode.source().where(f -> f.eq(QFolderNode.ID, root))
                .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
    }

    /** The seed of the cyclic graph: 100's parent is 101, whose parent is 100 again (AC-SRC-13). */
    private static SourceQuery<FolderNode> cycle(int maxDepth) {
        return tree(TckFixture.FOLDER_CYCLE_ROOT, maxDepth);
    }

    private static ModelQuery.Builder<FolderNode, ?, FolderRow> rowsBuilder(SourceQuery<FolderNode> source) {
        return QFolderRow.query(source).select(QFolderRow.ALL).orderBy(QFolderRow.ID.asc());
    }

    private static ModelQuery<FolderNode, ?, FolderRow> rows(SourceQuery<FolderNode> source) {
        return rowsBuilder(source).build();
    }

    /**
     * The grouped model over {@code source}: one row per level, so a count of it is a count over the groups. The
     * source's own key is not a group key, so the selection is the group key and the aggregate (MQ1401).
     */
    private static ModelQuery<FolderNode, ?, FolderLevelTotals> levels(SourceQuery<FolderNode> source) {
        return QFolderLevelTotals.query(source)
                .select(SelectSet.of(QFolderLevelTotals.LEVEL, QFolderLevelTotals.NODES)).build();
    }

    /** The messages {@code work} logs to {@link #EXECUTOR_LOG} at {@code Level.WARNING}. */
    private static List<String> capturingWarnings(Runnable work) {
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger(EXECUTOR_LOG);
        Level previous = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    messages.add(new SimpleFormatter().formatMessage(record));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.WARNING);
        logger.addHandler(handler);
        try {
            work.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return messages;
    }

    /** The nodes as {@code id|parentId|name|level} texts, comparable with a hand-written SQL's. */
    private static List<String> nodes(List<FolderRow> rows) {
        return rows.stream().map(row -> normalised(row.id()) + "|" + normalised(row.parentId()) + "|"
                + normalised(row.name()) + "|" + normalised(row.level())).toList();
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }

    /** As {@link #withExecutor(DataSource, Class, Consumer)}, in a transaction, as a stream needs (R-EXE-08). */
    private static <E> void withTransaction(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.fromTransaction(em -> {
                work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults()));
                return null;
            });
        }
    }

    /** {@code sql}'s rows, each as its values joined with {@code |} and {@link #normalised}. */
    private static List<String> rows(TckDatabase db, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    row.append(column == 1 ? "" : "|").append(normalised(result.getObject(column)));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    /** A value as text: a number without a decimal point or trailing zeros, so vendors and types compare alike. */
    private static String normalised(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
