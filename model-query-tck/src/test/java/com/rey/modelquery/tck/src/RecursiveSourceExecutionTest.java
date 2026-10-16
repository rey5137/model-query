package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.FolderNodeEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.fch.FetchTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.portability.LegacyRecursiveProviderSupport;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.VendorSources;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
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

    // ---- AC-SRC-18, the same statement checks the whole walk before returning any result

    @TckTest
    void ac_src_18_a_tree_selects_its_duplicate_probe_on_every_vendor(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "src-18-tree", ds -> withExecutor(ds, FolderNode.class, executor -> {
            SourceQuery<FolderNode> source = checkedTree(3, 1L);
            assertThat(executor.list(rows(source), Limit.unlimited())).hasSize(6);
        }));
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            assertThat(executor.count(rows(checkedTree(3, 1L)))).isEqualTo(6);
            assertThat(executor.count(levels(checkedTree(3, 1L)))).isEqualTo(3);
        }));
    }

    @TckTest
    void ac_src_18_overlapping_seeds_and_a_diamond_refuse_content_count_and_grouped_count(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            for (SourceQuery<FolderNode> source : List.of(checkedTree(3, 1L, 2L), diamond())) {
                assertThat(assertDuplicate(() -> executor.list(rows(source), Limit.unlimited()), "FolderRow", "id="))
                        .hasMessageNotContaining("first column");
                assertThat(assertDuplicate(() -> executor.count(rows(source)), "FolderRow", "id="))
                        .hasMessageNotContaining("first column");
                assertThat(assertDuplicate(() -> executor.count(levels(source)), "FolderLevelTotals", "id="))
                        .hasMessageNotContaining("first column");
            }
        }));
    }

    @TckTest
    void ac_src_18_the_probe_ignores_outer_filters_and_checks_streams_before_delivery(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withTransaction(ds, FolderNode.class, executor -> {
            ModelQuery<FolderNode, ?, FolderRow> filtered = rowsBuilder(checkedTree(3, 1L, 2L))
                    .where(f -> f.eq(QFolderRow.ID, 1L)).build();
            assertDuplicate(() -> executor.list(filtered, Limit.unlimited()), "FolderRow", "id=2");
            List<FolderRow> delivered = new ArrayList<>();
            assertDuplicate(() -> executor.stream(rows(diamond()), Limit.unlimited(), stream -> {
                stream.forEach(delivered::add);
                return delivered.size();
            }), "FolderRow", "id=203");
            assertThat(delivered).isEmpty();
        }));
    }

    @TckTest
    void ac_src_18_depth_and_cycle_errors_precede_the_duplicate_error(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            assertCode(() -> executor.list(rows(checkedTree(1, 10L, 11L)), Limit.unlimited()), MqCode.MQ2701);
            assertCode(() -> executor.list(rows(checkedTree(5, 100L)), Limit.unlimited()), MqCode.MQ2702);
        }));
    }

    @TckTest
    void ac_src_22_one_checks_the_walk_before_deciding_how_many_rows_it_returned(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor ->
                assertDuplicate(() -> executor.one(rows(checkedTree(3, 1L, 2L))), "FolderRow", "id=2")));
    }

    @TckTest
    void ac_src_24_an_old_renderer_refuses_trees_before_any_statement_but_still_serves_recursive(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> LegacyRecursiveProviderSupport.serving(false,
                () -> withExecutor(ds, FolderNode.class, executor -> {
                    assertCode(() -> executor.list(rows(checkedTree(3, 1L)), Limit.unlimited()), MqCode.MQ4014);
                    assertCode(() -> executor.count(rows(checkedTree(3, 1L))), MqCode.MQ4014);
                })));
        assertThat(sql).isEmpty();
        for (boolean extraCheck : new boolean[] {false, true}) {
            List<String> recursiveSql = SqlSnapshots.capture(db, ds -> LegacyRecursiveProviderSupport
                    .serving(extraCheck, () -> withExecutor(ds, FolderNode.class, executor ->
                            assertThat(executor.list(rows(tree(1L, 3)), Limit.unlimited())).hasSize(6))));
            SqlSnapshots.assertMatches(db, "src-12-tree", recursiveSql);
        }
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

    @TckTest
    void ac_src_21_postgres_and_mysql_check_the_whole_composite_key(TckDatabase db) {
        Assumptions.assumeTrue(!VendorSources.refusesCompositeNodeKey(db), "H2 refuses a composite node key");
        SourceQuery<CompositeNode> source = QCompositeNode.source()
                .where(f -> f.in(QCompositeNode.ID, List.of(1L, 2L)))
                .tree(step -> step.childKey(QCompositeNode.PARENT_ID).parentKey(QCompositeNode.ID), 3).build();
        ModelQuery<CompositeNode, ?, CompositeNodeRow> query = QCompositeNodeRow.query(source)
                .select(QCompositeNodeRow.ALL).orderBy(QCompositeNodeRow.ID.asc()).build();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, CompositeNode.class, executor -> {
            assertThat(assertDuplicate(() -> executor.list(query, Limit.unlimited()), "CompositeNodeRow", "tenantId=1"))
                    .hasMessageContaining("only the key's first column is shown");
            assertThat(assertDuplicate(() -> executor.count(query), "CompositeNodeRow", "tenantId=1"))
                    .hasMessageContaining("only the key's first column is shown");
        }));
    }

    /** The tree {@code composite_nodes} holds, from its root to level 3 (AC-SRC-21, R-SRC-13). */
    private static ModelQuery<CompositeNode, ?, CompositeNodeRow> compositeTree() {
        SourceQuery<CompositeNode> source = QCompositeNode.source()
                .where(f -> f.eq(QCompositeNode.ID, TckFixture.COMPOSITE_TREE_ROOT))
                .tree(step -> step.childKey(QCompositeNode.PARENT_ID).parentKey(QCompositeNode.ID), 3).build();
        return QCompositeNodeRow.query(source).select(QCompositeNodeRow.ALL)
                .orderBy(QCompositeNodeRow.ID.asc()).build();
    }

    @TckTest
    void ac_src_17_every_key_identified_read_visits_each_tree_node_once(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "src-17-tree-reads", ds -> withExecutor(ds, FolderNode.class, executor -> {
            var source = checkedTree(3, 1L);
            var plain = rows(source);
            var keyset = rowsBuilder(source).keyset().build();
            var pkFirst = rowsBuilder(source).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
            List<Long> expected = List.of(1L, 2L, 3L, 4L, 5L, 6L);
            for (var query : List.of(plain, keyset, pkFirst)) {
                List<FolderRow> exported = new ArrayList<>();
                executor.export(query, ExportOptions.of(2), page -> page, exported::add);
                assertThat(exported).extracting(FolderRow::id).containsExactlyElementsOf(expected);
            }
            List<FolderRow> keysetRows = new ArrayList<>();
            KeysetSpec next = KeysetSpec.first(2);
            while (true) {
                var page = executor.page(keyset, next);
                keysetRows.addAll(page.content());
                if (!page.hasNext()) {
                    break;
                }
                next = KeysetSpec.after(page.nextCursor().orElseThrow(), 2);
            }
            assertThat(keysetRows).extracting(FolderRow::id).containsExactlyElementsOf(expected);
            List<FolderRow> offsetRows = new ArrayList<>();
            for (int offset = 0; offset < 6; offset += 2) {
                offsetRows.addAll(executor.page(pkFirst, PageSpec.ofOffset(offset, 2), CountMode.NO_COUNT).content());
            }
            assertThat(offsetRows).extracting(FolderRow::id).containsExactlyElementsOf(expected);
            assertThat(executor.byKeys(plain, expected).values()).extracting(FolderRow::id)
                    .containsExactlyElementsOf(expected);
            for (Long id : expected) {
                assertThat(executor.one(plain, id)).get().extracting(FolderRow::id).isEqualTo(id);
            }
            var parents = executor.list(children(source), Limit.unlimited());
            assertThat(parents).extracting(FolderChildrenRow::id).containsExactlyElementsOf(expected);
            assertThat(parents.get(0).children()).extracting(FolderChild::id).containsExactly(2L, 3L);
            assertThat(parents.get(1).children()).extracting(FolderChild::id).containsExactly(4L, 5L);
            assertThat(parents.get(2).children()).extracting(FolderChild::id).containsExactly(6L);
            assertThat(parents.subList(3, 6)).allSatisfy(parent -> assertThat(parent.children()).isEmpty());
        }));
    }

    @TckTest
    void ac_src_18_remaining_statement_kinds_check_before_delivering_rows(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            for (int shape = 0; shape < 2; shape++) {
                var source = shape == 0 ? checkedTree(3, 1L, 2L) : diamond();
                String key = shape == 0 ? "id=2" : "id=203";
                var plain = rows(source);
                var keyset = rowsBuilder(source).keyset().build();
                var pkFirst = rowsBuilder(source).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
                Long requested = shape == 0 ? 2L : 201L;
                // The requested key can exclude the duplicate: the probe still checks the whole source.
                assertDuplicate(() -> executor.one(plain, requested), "FolderRow", key);
                assertDuplicate(() -> executor.byKeys(plain, List.of(2L, 201L)), "FolderRow", key);
                assertDuplicate(() -> executor.page(keyset, KeysetSpec.first(10)), "FolderRow", key);
                assertDuplicate(() -> executor.page(plain, PageSpec.ofOffset(1, 2), CountMode.NO_COUNT),
                        "FolderRow", key);
                assertDuplicate(() -> executor.page(pkFirst, PageSpec.ofOffset(1, 2), CountMode.NO_COUNT),
                        "FolderRow", key);
                for (var query : List.of(plain, keyset, pkFirst)) {
                    List<FolderRow> delivered = new ArrayList<>();
                    assertDuplicate(() -> executor.export(query, ExportOptions.of(2), page -> page, delivered::add),
                            "FolderRow", key);
                    assertThat(delivered).isEmpty();
                }
                assertDuplicate(() -> executor.list(children(source), Limit.unlimited()), "FolderChildrenRow", key);
            }
        }));
    }

    @TckTest
    void ac_src_18_duplicate_check_precedes_null_keys_and_single_row_checks(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            var nullOrder = rowsBuilder(checkedTree(3, 1L)).orderBy(QFolderRow.PARENT_ID.asc()).keyset().build();
            assertCode(() -> executor.page(nullOrder, KeysetSpec.first(10)), MqCode.MQ2202);
            assertCode(() -> executor.export(nullOrder, ExportOptions.of(10), page -> page, row -> {}),
                    MqCode.MQ2202);
            var duplicateNullOrder = rowsBuilder(checkedTree(3, 1L, 2L))
                    .orderBy(QFolderRow.PARENT_ID.asc()).keyset().build();
            assertDuplicate(() -> executor.page(duplicateNullOrder, KeysetSpec.first(10)), "FolderRow", "id=2");
            assertDuplicate(() -> executor.export(duplicateNullOrder, ExportOptions.of(10), page -> page, row -> {}),
                    "FolderRow", "id=2");
            assertDuplicate(() -> executor.byKeys(rows(checkedTree(3, 1L, 2L)), List.of(2L)), "FolderRow", "id=2");
            assertDuplicate(() -> executor.one(rows(checkedTree(3, 1L, 2L)), 2L), "FolderRow", "id=2");
        }));
    }

    @TckTest
    void ac_src_18_duplicate_check_precedes_an_unexpected_decimal_key(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db, DecimalFolderNode.class)) {
            sf.inSession(em -> {
                var executor = ModelQueryExecutor.create(em, FolderNode.class, ModelQueryConfig.defaults());
                var scaledKey = new BigDecimal("2.0");
                assertCode(() -> executor.byKeys(decimalKeys(1L), List.of(scaledKey)), MqCode.MQ2005);
                assertDuplicate(() -> executor.byKeys(decimalKeys(1L, 2L), List.of(scaledKey)), "FolderRow", "id=2");
            });
        }
    }

    @TckTest
    void ac_src_18_the_next_keyset_page_checks_before_the_moved_cursor_guard(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    em.getTransaction().begin();
                    try {
                        var executor = ModelQueryExecutor.create(em, FolderNode.class, ModelQueryConfig.defaults());
                        var query = rowsBuilder(checkedTree(5, 1L, 200L)).orderBy(QFolderRow.NAME.asc())
                                .keyset().build();
                        var first = executor.page(query, KeysetSpec.first(2));
                        String cursor = first.nextCursor().orElseThrow();
                        Long boundary = first.content().get(1).id();
                        em.createNativeQuery("UPDATE folder_nodes SET name = 'zz-moved' WHERE id = :id")
                                .setParameter("id", boundary).executeUpdate();
                        assertCode(() -> executor.page(query, KeysetSpec.after(cursor, 10)), MqCode.MQ2205);
                        em.createNativeQuery("UPDATE folder_nodes SET parent_id = 2 WHERE id = 200").executeUpdate();
                        assertDuplicate(() -> executor.page(query, KeysetSpec.after(cursor, 10)),
                                "FolderRow", "id=200");
                    } finally {
                        em.getTransaction().rollback();
                    }
                });
            }
        });
    }

    @TckTest
    void ac_src_18_keyset_export_checks_before_the_moved_cursor_guard(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                for (boolean duplicate : List.of(false, true)) {
                    sf.inSession(em -> {
                        em.getTransaction().begin();
                        try {
                            var executor = ModelQueryExecutor.create(em, FolderNode.class, ModelQueryConfig.defaults());
                            var query = rowsBuilder(checkedTree(5, 1L, 200L)).orderBy(QFolderRow.NAME.asc())
                                    .keyset().build();
                            List<FolderRow> delivered = new ArrayList<>();
                            Runnable export = () -> executor.export(query, ExportOptions.of(2), page -> {
                                if (delivered.isEmpty()) {
                                    em.createNativeQuery("UPDATE folder_nodes SET name = :name WHERE id = :id")
                                            .setParameter("name", page.get(1).name() + "-moved")
                                            .setParameter("id", page.get(1).id()).executeUpdate();
                                    if (duplicate) {
                                        em.createNativeQuery("UPDATE folder_nodes SET parent_id = 2 WHERE id = 200")
                                                .executeUpdate();
                                    }
                                }
                                return page;
                            }, delivered::add);
                            if (duplicate) assertDuplicate(export, "FolderRow", "id=200");
                            else assertCode(export, MqCode.MQ2205);
                            assertThat(delivered).hasSize(2);
                        } finally {
                            em.getTransaction().rollback();
                        }
                    });
                }
            }
        });
    }

    @TckTest
    void ac_src_18_second_pk_first_statement_and_later_by_keys_chunks_recheck_the_tree(TckDatabase db) {
        for (boolean pkFirst : List.of(true, false)) {
            AtomicInteger statements = new AtomicInteger();
            try {
                List<String> sql = SqlSnapshots.capture(db, ds -> {
                    DataSource changing = ProxyDataSourceBuilder.create(ds).beforeQuery((info, queries) -> {
                        if (queries.stream().anyMatch(q -> q.getQuery().toLowerCase().startsWith("with "))
                                && statements.incrementAndGet() == 2) {
                            moveDiamondRoot(db, true);
                        }
                    }).build();
                    var config = ModelQueryConfig.defaults()
                            .vendorProfiles(List.of(FetchTestSupport.limited(db, 2)));
                    withIsolation(changing, Connection.TRANSACTION_READ_COMMITTED, config, executor -> {
                        var source = checkedTree(5, 1L, 200L);
                        if (pkFirst) {
                            var query = rowsBuilder(source).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
                            assertDuplicate(() -> executor.page(query, PageSpec.ofOffset(1, 2), CountMode.NO_COUNT),
                                    "FolderRow", "id=200");
                        } else {
                            assertDuplicate(() -> executor.byKeys(rows(source), List.of(1L, 2L, 3L, 4L)),
                                    "FolderRow", "id=200");
                        }
                    });
                });
                assertThat(sql).hasSize(2);
                assertThat(statements).hasValue(2);
            } finally {
                moveDiamondRoot(db, false);
            }
        }
    }

    @TckTest
    void ac_src_19_export_rechecks_each_page_at_read_committed_and_keeps_a_repeatable_snapshot(TckDatabase db) {
        for (int isolation : new int[] {Connection.TRANSACTION_READ_COMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ}) {
            for (boolean keyset : List.of(false, true)) {
                List<FolderRow> delivered = new ArrayList<>();
                var config = ModelQueryConfig.defaults();
                try {
                    SqlSnapshots.capture(db, ds -> withIsolation(ds, isolation, config, executor -> {
                        var builder = rowsBuilder(checkedTree(5, 1L, 200L));
                        var query = keyset ? builder.keyset().build() : builder.build();
                        Runnable export = () -> executor.export(query, ExportOptions.of(2), page -> {
                            if (delivered.isEmpty()) {
                                moveDiamondRoot(db, true);
                            }
                            return page;
                        }, delivered::add);
                        if (isolation == Connection.TRANSACTION_READ_COMMITTED) {
                            assertDuplicate(export, "FolderRow", "id=200");
                            assertThat(delivered).extracting(FolderRow::id).containsExactly(1L, 2L);
                        } else {
                            export.run();
                            assertThat(delivered).extracting(FolderRow::id)
                                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 200L);
                        }
                    }));
                } finally {
                    moveDiamondRoot(db, false);
                }
            }
        }
    }

    @TckTest
    void ac_src_20_offset_tree_order_adds_only_the_primary_key(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "src-20-tree-offset", ds ->
                withExecutor(ds, FolderNode.class, executor -> {
                    var query = rowsBuilder(checkedTree(3, 1L)).orderBy(QFolderRow.NAME.asc()).build();
                    assertThat(executor.page(query, PageSpec.ofOffset(1, 2), CountMode.NO_COUNT).content())
                            .extracting(FolderRow::id).containsExactly(2L, 3L);
                }));
        String order = sql.get(0).substring(sql.get(0).lastIndexOf(" order by "));
        // NAME is projection 3 and the primary key is projection 1.
        assertThat(order).startsWith(" order by 3,1 ");
    }

    @TckTest
    void ac_src_20_offset_tree_without_a_primary_key_orders_by_every_selected_column(TckDatabase db) {
        List<FolderRow> everything = new ArrayList<>();
        List<FolderRow> paged = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-20-tree-offset-unkeyed", ds ->
                withExecutor(ds, FolderNode.class, executor -> {
                    var source = checkedTree(3, 1L);
                    var query = unkeyedRows(source).orderBy(QFolderRow.LEVEL.asc()).build();
                    everything.addAll(executor.list(rows(source), Limit.unlimited()));
                    int size = 2;
                    for (int offset = 0; offset < everything.size(); offset += size) {
                        paged.addAll(executor.page(query, PageSpec.ofOffset(offset, size), CountMode.NO_COUNT)
                                .content());
                    }
                }));
        String last = sql.get(sql.size() - 1);
        // LEVEL is projection 4; the definition's order is followed by every other selected column, ascending.
        assertThat(last.substring(last.lastIndexOf(" order by "))).startsWith(" order by 4,1,2,3 ");
        assertThat(paged).hasSameSizeAs(everything).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(everything);
    }

    @TckTest
    void ac_src_20_unorderable_offset_page_names_tree_only_for_a_recursive_source(TckDatabase db) {
        var root = TableField.source(FolderNode.class);
        var bytes = ColumnField.of(FolderNode.class, QFolderNode.ROOT, "name", byte[].class, String.class,
                new ColumnConverter<byte[], String>() {
                    @Override
                    public byte[] toModel(String attribute) {
                        return attribute.getBytes(StandardCharsets.UTF_8);
                    }

                    @Override
                    public String toAttribute(byte[] model) {
                        return new String(model, StandardCharsets.UTF_8);
                    }
                }).named("payload");
        var payload = ColumnField.over(byte[].class, root, bytes);
        var seed = SourceQuery.builder(FolderNode.class, QFolderNode.ROOT,
                SelectSet.of(QFolderNode.ID, bytes), QFolderNode.KEY).build();
        var recursive = SourceQuery.builder(FolderNode.class, QFolderNode.ROOT,
                SelectSet.of(QFolderNode.ID, QFolderNode.PARENT_ID, bytes, QFolderNode.LEVEL), QFolderNode.KEY)
                .depth(QFolderNode.LEVEL).where(f -> f.eq(QFolderNode.ID, TckFixture.FOLDER_TREE_ROOT))
                .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), 3).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            for (var source : List.of(recursive, seed.unionAll(seed))) {
                var query = ModelQuery.<FolderNode, byte[]>builder(root, row -> row.get(payload), source)
                        .select(SelectSet.of(payload)).build();
                var refusal = assertCode(() -> executor.page(query, PageSpec.ofOffset(0, 2), CountMode.NO_COUNT),
                        MqCode.MQ2212);
                if (source.recursive()) assertThat(refusal).hasMessageContaining("tree(...)");
                else assertThat(refusal).hasMessageNotContaining("tree(...)");
            }
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_20_unkeyed_tree_offset_page_with_an_unorderable_column_points_to_primary_key(TckDatabase db) {
        var root = TableField.source(FolderNode.class);
        var bytes = ColumnField.of(FolderNode.class, QFolderNode.ROOT, "name", byte[].class, String.class,
                new ColumnConverter<byte[], String>() {
                    @Override
                    public byte[] toModel(String attribute) {
                        return attribute.getBytes(StandardCharsets.UTF_8);
                    }

                    @Override
                    public String toAttribute(byte[] model) {
                        return new String(model, StandardCharsets.UTF_8);
                    }
                }).named("payload");
        var payload = ColumnField.over(byte[].class, root, bytes);
        var tree = SourceQuery.builder(FolderNode.class, QFolderNode.ROOT,
                SelectSet.of(QFolderNode.ID, QFolderNode.PARENT_ID, bytes, QFolderNode.LEVEL), QFolderNode.KEY)
                .depth(QFolderNode.LEVEL).where(f -> f.eq(QFolderNode.ID, TckFixture.FOLDER_TREE_ROOT))
                .tree(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), 3).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            var query = ModelQuery.<FolderNode, byte[]>builder(root, row -> row.get(payload), tree)
                    .select(SelectSet.of(payload)).build();
            assertThat(assertCode(() -> executor.page(query, PageSpec.ofOffset(0, 2), CountMode.NO_COUNT),
                    MqCode.MQ2212)).hasMessageContaining("primaryKey(...)").hasMessageNotContaining("make the source");
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_23_an_empty_outer_result_has_no_check_row_but_scalar_count_does(TckDatabase db) {
        SqlSnapshots.capture(db, ds -> withExecutor(ds, FolderNode.class, executor -> {
            var source = checkedTree(3, 1L, 2L);
            var empty = rowsBuilder(source).where(f -> f.eq(QFolderRow.ID, -1L)).build();
            assertThat(executor.list(empty, Limit.unlimited())).isEmpty();
            assertThat(executor.page(empty, PageSpec.ofOffset(0, 2), CountMode.NO_COUNT).content()).isEmpty();
            assertDuplicate(() -> executor.page(empty, PageSpec.ofOffset(0, 2), CountMode.COUNT), "FolderRow", "id=2");
            var noGroup = QFolderLevelTotals.query(source)
                    .select(SelectSet.of(QFolderLevelTotals.LEVEL, QFolderLevelTotals.NODES))
                    .having(h -> h.gt(QFolderLevelTotals.NODES, 1_000L)).build();
            assertThat(executor.count(noGroup)).isZero();
            assertDuplicate(() -> executor.count(levels(source)), "FolderLevelTotals", "id=2");
        }));
    }

    private static ModelQuery<FolderNode, ?, FolderChildrenRow> children(SourceQuery<FolderNode> source) {
        var plan = FetchPlan.of(QFolderChildrenRow.ALL).child(QFolderChildrenRow.CHILDREN,
                FetchPlan.of(QFolderChild.ALL));
        return QFolderChildrenRow.query(source).orderBy(QFolderChildrenRow.ID.asc()).fetch(plan).build();
    }

    private static ModelQuery<FolderNode, BigDecimal, FolderRow> decimalKeys(Long... seeds) {
        var root = TableField.root(DecimalFolderNode.class);
        var id = ColumnField.of(FolderNode.class, root, "id", BigDecimal.class).named("id");
        var parent = ColumnField.of(FolderNode.class, root, "parentId", BigDecimal.class).named("parentId");
        var depth = ColumnField.of(FolderNode.class, root, "level", Integer.class).named("level");
        var source = SourceQuery.builder(FolderNode.class, root, SelectSet.of(id, parent, depth), PrimaryKey.of(id))
                .depth(depth).where(f -> f.in(id, List.of(seeds).stream().map(BigDecimal::valueOf).toList()))
                .tree(step -> step.childKey(parent).parentKey(id), 3).build();
        var key = ColumnField.over(FolderRow.class, QFolderRow.ROOT, id);
        return ModelQuery.builder(QFolderRow.ROOT,
                row -> new FolderRow(row.get(key).longValueExact(), null, null, null), source)
                .select(SelectSet.of(key)).primaryKey(PrimaryKey.of(key)).build();
    }

    /** Reads the integer columns as decimals, so SQL equality can match keys whose Java scales differ. */
    @Entity(name = "DecimalFolderNode")
    @Table(name = "folder_nodes")
    static class DecimalFolderNode {
        @Id
        BigDecimal id;

        @Column(name = "parent_id")
        BigDecimal parentId;
    }

    private static void moveDiamondRoot(TckDatabase db, boolean overlap) {
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE folder_nodes SET parent_id = " + (overlap ? "2" : "NULL")
                    + " WHERE id = 200");
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private static void withIsolation(DataSource ds, int isolation, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<FolderNode>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> {
                em.doWork(connection -> connection.setTransactionIsolation(isolation));
                em.getTransaction().begin();
                try {
                    work.accept(ModelQueryExecutor.create(em, FolderNode.class, config));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }

    private static SourceQuery<FolderNode> tree(long root, int maxDepth) {
        return QFolderNode.source().where(f -> f.eq(QFolderNode.ID, root))
                .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
    }

    private static SourceQuery<FolderNode> checkedTree(int maxDepth, Long... seeds) {
        return QFolderNode.source().where(f -> f.in(QFolderNode.ID, List.of(seeds)))
                .tree(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
    }

    private static SourceQuery<FolderNode> diamond() {
        TableField<FolderNodeEntity, FolderNodeEntity> folders = TableField.root(FolderNodeEntity.class);
        ColumnField<FolderNode, FolderNodeEntity, Long> parent = ColumnField.of(FolderNode.class,
                TableField.join(folders, "parents", JoinType.INNER), "id", Long.class);
        return QFolderNode.source().where(f -> f.eq(QFolderNode.ID, 200L))
                .tree(step -> step.childKey(parent).parentKey(QFolderNode.ID), 3).build();
    }

    private static ModelQueryException assertDuplicate(Runnable work, String model, String key) {
        ModelQueryException refusal = assertCode(work, MqCode.MQ2703);
        assertThat(refusal).hasMessageContaining(model).hasMessageContaining(key)
                .hasMessageContaining("narrow the seeds").hasMessageContaining("recursive(...)");
        return refusal;
    }

    private static ModelQueryException assertCode(Runnable work, MqCode code) {
        Throwable refusal = catchThrowable(work::run);
        assertThat(refusal).isInstanceOf(ModelQueryException.class);
        assertThat(((ModelQueryException) refusal).code()).isEqualTo(code);
        return (ModelQueryException) refusal;
    }

    /** The seed of the cyclic graph: 100's parent is 101, whose parent is 100 again (AC-SRC-13). */
    private static SourceQuery<FolderNode> cycle(int maxDepth) {
        return tree(TckFixture.FOLDER_CYCLE_ROOT, maxDepth);
    }

    private static ModelQuery.Builder<FolderNode, Long, FolderRow> rowsBuilder(SourceQuery<FolderNode> source) {
        return QFolderRow.query(source).select(QFolderRow.ALL).orderBy(QFolderRow.ID.asc());
    }

    /** Hand-built over the public builder, so it has no primary key. */
    private static ModelQuery.Builder<FolderNode, ?, FolderRow> unkeyedRows(SourceQuery<FolderNode> source) {
        var id = QFolderRow.ID;
        var parent = QFolderRow.PARENT_ID;
        var name = QFolderRow.NAME;
        var level = QFolderRow.LEVEL;
        return ModelQuery.builder(QFolderRow.ROOT,
                row -> new FolderRow(row.get(id), row.get(parent), row.get(name), row.get(level)), source)
                .select(SelectSet.of(id, parent, name, level));
    }

    private static ModelQuery<FolderNode, Long, FolderRow> rows(SourceQuery<FolderNode> source) {
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
