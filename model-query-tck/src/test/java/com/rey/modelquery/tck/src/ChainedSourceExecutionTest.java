package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.InlineValueHandling;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Sources over sources (spec api/17 §8, R-SRC-16, R-SRC-18, AC-SRC-26..32; RFC 0015 §4): every level of a chain
 * registers on the top statement from innermost to outermost, the walk filter sits inside the next CTE body before its
 * windows and aggregates, depth/cycle/duplicate-key checks remain in the top statement on every statement kind, and
 * key predicates apply only to the top statement.
 */
class ChainedSourceExecutionTest {

    private static final SourceQuery<DispatchFeed> LIVE = QDispatchFeed.source().build();
    private static final SourceQuery<DispatchFeed> SHELVED = QShelvedDispatchFeed.source().build();

    // ---- AC-SRC-26: source over plain source

    @TckTest
    void ac_src_26_a_source_over_a_plain_source_executes_and_matches_hand_written_sql(TckDatabase db)
            throws SQLException {
        SourceQuery<OrderSource> inner = QOrderSource.source()
                .where(f -> f.in(QOrderSource.STATUS, List.of("PAID", "SHIPPED")))
                .build();
        SourceQuery<RankedOrderSource> ranked = QRankedOrderSource.source(inner)
                .where(f -> f.gte(QRankedOrderSource.ORDER_TOTAL, new BigDecimal("50.00")))
                .build();
        ModelQuery<RankedOrderSource, Long, RankedOrderFeed> query = QRankedOrderFeed.query(ranked)
                .select(QRankedOrderFeed.ALL)
                .where(f -> f.lte(QRankedOrderFeed.RANK, 3L))
                .orderBy(QRankedOrderFeed.STATUS.asc(), QRankedOrderFeed.RANK.asc())
                .build();

        List<RankedOrderFeed> rows = new ArrayList<>();
        long[] count = new long[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedOrderSource.class, executor -> {
            rows.addAll(executor.list(query, Limit.unlimited()));
            count[0] = executor.count(query);
        }));

        assertThat(sql).hasSize(2);
        assertThat(rows).hasSize(6);
        assertThat(count[0]).isEqualTo(6L);
        assertThat(rankedOrders(rows)).isEqualTo(rows(db, """
                with order_src as (
                    select id, status, total from orders where status in ('PAID', 'SHIPPED')
                ),
                ranked_src as (
                    select id, status, total,
                           row_number() over (partition by status order by total desc, id asc) as rnk
                    from order_src
                    where total >= 50.00
                )
                select id, status, total, rnk
                from ranked_src
                where rnk <= 3
                order by status asc, rnk asc"""));
    }

    // ---- AC-SRC-27: source over grouped source & grouped source over source

    @TckTest
    void ac_src_27_a_source_over_a_grouped_source_and_a_grouped_source_over_a_source_execute(TckDatabase db)
            throws SQLException {
        SourceQuery<QuantitySource> groupedInner = QQuantitySource.source()
                .where(f -> f.gte(QQuantitySource.QUANTITY, 3))
                .having(h -> h.gt(QQuantitySource.LINE_COUNT, 100L))
                .build();
        SourceQuery<RankedQuantityTier> rankedTiers = QRankedQuantityTier.source(groupedInner)
                .where(f -> f.gte(QRankedQuantityTier.REVENUE, new BigDecimal("1000.00")))
                .build();
        ModelQuery<RankedQuantityTier, Integer, RankedQuantityTierFeed> tierQuery =
                QRankedQuantityTierFeed.query(rankedTiers)
                        .select(QRankedQuantityTierFeed.ALL)
                        .orderBy(QRankedQuantityTierFeed.RANK.asc())
                        .build();

        List<RankedQuantityTierFeed> tiers = new ArrayList<>();
        long[] tierCount = new long[1];
        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedQuantityTier.class, executor -> {
            tiers.addAll(executor.list(tierQuery, Limit.unlimited()));
            tierCount[0] = executor.count(tierQuery);
        }));

        assertThat(tiers).isNotEmpty();
        assertThat(tierCount[0]).isEqualTo(tiers.size());
        assertThat(tiers.stream().map(r -> normalised(r.quantity()) + "|" + normalised(r.lineCount()) + "|"
                + normalised(r.revenue()) + "|" + normalised(r.rank())).toList())
                .isEqualTo(rows(db, """
                        with qty_src as (
                            select quantity, count(*) as line_count, sum(unit_price) as revenue
                            from order_items
                            where quantity >= 3
                            group by quantity
                            having count(*) > 100
                        ),
                        ranked_tiers as (
                            select quantity, line_count, revenue,
                                   row_number() over (order by revenue desc, quantity asc) as rnk
                            from qty_src
                            where revenue >= 1000.00
                        )
                        select quantity, line_count, revenue, rnk
                        from ranked_tiers
                        order by rnk asc"""));

        // Grouped source over a source (SubtreeSize over FolderNode)
        SourceQuery<FolderNode> tree = checkedTree(3, TckFixture.FOLDER_TREE_ROOT);
        SourceQuery<SubtreeSize> subtreeSizes = QSubtreeSize.source(tree).build();
        ModelQuery<SubtreeSize, Integer, SubtreeSizeRow> sizeQuery = QSubtreeSizeRow.query(subtreeSizes)
                .select(QSubtreeSizeRow.ALL)
                .orderBy(QSubtreeSizeRow.LEVEL.asc())
                .build();

        List<SubtreeSizeRow> sizes = new ArrayList<>();
        long[] sizeCount = new long[1];
        SqlSnapshots.capture(db, ds -> withExecutor(ds, SubtreeSize.class, executor -> {
            sizes.addAll(executor.list(sizeQuery, Limit.unlimited()));
            sizeCount[0] = executor.count(sizeQuery);
        }));

        assertThat(sizeCount[0]).isEqualTo(3L);
        assertThat(subtreeSizes(sizes)).containsExactly("0|1", "1|2", "2|3");
    }

    // ---- AC-SRC-28: source over union shape

    @TckTest
    void ac_src_28_a_source_over_a_union_shape_executes_with_combined_source_and_single_branch(TckDatabase db)
            throws SQLException {
        SourceQuery<RankedActivity> overUnion = QRankedActivity.source(LIVE.union(SHELVED)).build();
        ModelQuery<RankedActivity, ?, RankedActivityRow> unionQuery = QRankedActivityRow.query(overUnion)
                .select(QRankedActivityRow.ALL)
                .where(f -> f.lte(QRankedActivityRow.RANK, 2L))
                .orderBy(QRankedActivityRow.LABEL.asc(), QRankedActivityRow.RANK.asc(),
                        QRankedActivityRow.BRANCH.asc(), QRankedActivityRow.ID.asc())
                .build();

        List<RankedActivityRow> unionRows = new ArrayList<>();
        long[] unionCount = new long[1];
        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedActivity.class, executor -> {
            unionRows.addAll(executor.list(unionQuery, Limit.unlimited()));
            unionCount[0] = executor.count(unionQuery);
        }));

        assertThat(unionRows).isNotEmpty();
        assertThat(unionCount[0]).isEqualTo(unionRows.size());
        assertThat(activities(unionRows)).isEqualTo(rows(db, """
                with combined (branch, id, label) as (
                    select 'live', id, status from orders
                    union
                    select 'shelved', id, sort_text from nullable_sort_rows
                ),
                ranked as (
                    select branch, id, label,
                           row_number() over (partition by label order by id desc, branch asc) as rnk
                    from combined
                )
                select branch, id, label, rnk
                from ranked
                where rnk <= 2
                order by label asc, rnk asc, branch asc, id asc"""));

        // Single branch of the union shape as inner source
        SourceQuery<RankedActivity> overBranch = QRankedActivity.source(SHELVED).build();
        ModelQuery<RankedActivity, ?, RankedActivityRow> branchQuery = QRankedActivityRow.query(overBranch)
                .select(QRankedActivityRow.ALL)
                .orderBy(QRankedActivityRow.BRANCH.asc(), QRankedActivityRow.ID.asc())
                .build();

        List<RankedActivityRow> branchRows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedActivity.class,
                executor -> branchRows.addAll(executor.list(branchQuery, Limit.unlimited()))));

        assertThat(activities(branchRows)).isEqualTo(rows(db, """
                with shelved (branch, id, label) as (
                    select 'shelved', id, sort_text from nullable_sort_rows
                ),
                ranked as (
                    select branch, id, label,
                           row_number() over (partition by label order by id desc, branch asc) as rnk
                    from shelved
                )
                select branch, id, label, rnk
                from ranked
                order by branch asc, id asc"""));
    }

    // ---- AC-SRC-29: source over recursive(...) walk

    @TckTest
    void ac_src_29_a_source_over_a_recursive_walk_carries_non_uniqueness_unless_grouped(TckDatabase db) {
        SourceQuery<FolderNode> walk = walk(TckFixture.FOLDER_TREE_ROOT, 3);
        SourceQuery<RankedFolderNode> ungrouped = QRankedFolderNode.source(walk).build();
        assertThat(ungrouped.unique()).isFalse();

        // Build-time MQ1901 on keyset and primaryKeyFirst
        assertThat(assertCode(() -> QRankedFolderRow.query(ungrouped).select(QRankedFolderRow.ALL)
                .orderBy(QRankedFolderRow.ID.asc()).keyset().build(), MqCode.MQ1901))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("tree(...)");
        assertThat(assertCode(() -> QRankedFolderRow.query(ungrouped).select(QRankedFolderRow.ALL)
                .orderBy(QRankedFolderRow.ID.asc()).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build(),
                MqCode.MQ1901))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("tree(...)");

        ModelQuery<RankedFolderNode, Long, RankedFolderRow> plain = QRankedFolderRow.query(ungrouped)
                .select(QRankedFolderRow.ALL)
                .orderBy(QRankedFolderRow.RANK.asc())
                .build();

        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedFolderNode.class, executor -> {
            List<RankedFolderRow> list = executor.list(plain, Limit.unlimited());
            assertThat(rankedFolders(list)).containsExactly(
                    "4|2|node-4|2|1", "5|2|node-5|2|2", "6|3|node-6|2|3",
                    "2|1|node-2|1|4", "3|1|node-3|1|5", "1|null|node-1|0|6");
            assertThat(executor.count(plain)).isEqualTo(6L);
            Slice<RankedFolderRow> page = executor.page(plain, PageSpec.ofOffset(0, 2), CountMode.COUNT);
            assertThat(rankedFolders(page.content())).containsExactly("4|2|node-4|2|1", "5|2|node-5|2|2");
            assertThat(page.total()).hasValue(6L);

            // Call-time MQ2212 before any statement on key-identifying reads
            assertThat(assertCode(() -> executor.byKeys(plain, List.of(1L)), MqCode.MQ2212))
                    .hasMessageContaining("tree(...)");
            assertThat(assertCode(() -> executor.one(plain, 1L), MqCode.MQ2212))
                    .hasMessageContaining("tree(...)");
            assertThat(assertCode(() -> executor.export(plain, ExportOptions.of(2), p -> p, r -> {}), MqCode.MQ2212))
                    .hasMessageContaining("tree(...)");
        }));

        // Grouped source over the same non-unique recursive(...) walk is unique by its group keys
        SourceQuery<SubtreeSize> grouped = QSubtreeSize.source(walk).build();
        assertThat(grouped.unique()).isTrue();
        ModelQuery<SubtreeSize, Integer, SubtreeSizeRow> keysetGrouped = QSubtreeSizeRow.query(grouped)
                .select(QSubtreeSizeRow.ALL)
                .orderBy(QSubtreeSizeRow.LEVEL.asc())
                .keyset()
                .build();

        SqlSnapshots.capture(db, ds -> withExecutor(ds, SubtreeSize.class, executor -> {
            KeysetSlice<SubtreeSizeRow> first = executor.page(keysetGrouped, KeysetSpec.first(2));
            assertThat(subtreeSizes(first.content())).containsExactly("0|1", "1|2");
            KeysetSlice<SubtreeSizeRow> second = executor.page(
                    keysetGrouped, KeysetSpec.after(first.nextCursor().orElseThrow(), 2));
            assertThat(subtreeSizes(second.content())).containsExactly("2|3");
            assertThat(executor.one(keysetGrouped, 2)).contains(new SubtreeSizeRow(2, 3L));
        }));
    }

    // ---- AC-SRC-30: source over checked tree(...)

    @TckTest
    void ac_src_30_a_source_over_a_checked_tree_retains_uniqueness_across_all_reads(TckDatabase db) {
        SourceQuery<FolderNode> tree = checkedTree(3, TckFixture.FOLDER_TREE_ROOT);
        SourceQuery<RankedFolderNode> ranked = QRankedFolderNode.source(tree).build();
        assertThat(ranked.unique()).isTrue();

        ModelQuery<RankedFolderNode, Long, RankedFolderRow> keysetQuery = QRankedFolderRow.query(ranked)
                .select(QRankedFolderRow.ALL)
                .orderBy(QRankedFolderRow.ID.asc())
                .keyset()
                .build();
        ModelQuery<RankedFolderNode, Long, RankedFolderRow> pkFirstQuery = QRankedFolderRow.query(ranked)
                .select(QRankedFolderRow.ALL)
                .orderBy(QRankedFolderRow.ID.asc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                .build();

        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedFolderNode.class, executor -> {
            KeysetSlice<RankedFolderRow> first = executor.page(keysetQuery, KeysetSpec.first(2));
            assertThat(rankedFolders(first.content())).containsExactly("1|null|node-1|0|6", "2|1|node-2|1|4");
            KeysetSlice<RankedFolderRow> second = executor.page(
                    keysetQuery, KeysetSpec.after(first.nextCursor().orElseThrow(), 2));
            assertThat(rankedFolders(second.content())).containsExactly("3|1|node-3|1|5", "4|2|node-4|2|1");

            Slice<RankedFolderRow> pkPage = executor.page(pkFirstQuery, PageSpec.ofOffset(2, 2), CountMode.COUNT);
            assertThat(rankedFolders(pkPage.content())).containsExactly("3|1|node-3|1|5", "4|2|node-4|2|1");
            assertThat(pkPage.total()).hasValue(6L);

            assertThat(rankedFolders(new ArrayList<>(executor.byKeys(keysetQuery, List.of(1L, 4L)).values())))
                    .containsExactlyInAnyOrder("1|null|node-1|0|6", "4|2|node-4|2|1");
            assertThat(executor.one(keysetQuery, 4L)).contains(new RankedFolderRow(4L, 2L, "node-4", 2, 1L));

            List<RankedFolderRow> exported = new ArrayList<>();
            executor.export(keysetQuery, ExportOptions.of(2), p -> p, exported::add);
            assertThat(exported).hasSize(6);
        }));
    }

    // ---- AC-SRC-31: three source levels

    @TckTest
    void ac_src_31_three_source_levels_execute_through_generated_factories(TckDatabase db) throws SQLException {
        SourceQuery<OrderSource> level1 = QOrderSource.source()
                .where(f -> f.in(QOrderSource.STATUS, List.of("PAID", "SHIPPED")))
                .build();
        SourceQuery<RankedOrderSource> level2 = QRankedOrderSource.source(level1)
                .where(f -> f.gte(QRankedOrderSource.ORDER_TOTAL, new BigDecimal("50.00")))
                .build();
        SourceQuery<TopOrderSource> level3 = QTopOrderSource.source(level2)
                .where(f -> f.lte(QTopOrderSource.RANK, 2L))
                .build();
        ModelQuery<TopOrderSource, Long, TopOrderFeed> query = QTopOrderFeed.query(level3)
                .select(QTopOrderFeed.ALL)
                .orderBy(QTopOrderFeed.OVERALL_RANK.asc())
                .keyset()
                .build();

        List<TopOrderFeed> rows = new ArrayList<>();
        long[] count = new long[1];
        SqlSnapshots.capture(db, ds -> withExecutor(ds, TopOrderSource.class, executor -> {
            rows.addAll(executor.list(query, Limit.unlimited()));
            count[0] = executor.count(query);
            KeysetSlice<TopOrderFeed> first = executor.page(query, KeysetSpec.first(2));
            assertThat(first.content()).hasSize(2);
            assertThat(executor.one(query, rows.get(0).id())).contains(rows.get(0));
        }));

        assertThat(rows).hasSize(4);
        assertThat(count[0]).isEqualTo(4L);
        assertThat(rows.stream().map(r -> normalised(r.id()) + "|" + r.status() + "|"
                + normalised(r.total()) + "|" + normalised(r.rank()) + "|" + normalised(r.overallRank())).toList())
                .isEqualTo(rows(db, """
                        with l1 as (
                            select id, status, total from orders where status in ('PAID', 'SHIPPED')
                        ),
                        l2 as (
                            select id, status, total,
                                   row_number() over (partition by status order by total desc, id asc) as rnk
                            from l1
                            where total >= 50.00
                        ),
                        l3 as (
                            select id, status, total, rnk,
                                   row_number() over (order by total desc, id asc) as overall_rnk
                            from l2
                            where rnk <= 2
                        )
                        select id, status, total, rnk, overall_rnk
                        from l3
                        order by overall_rnk asc"""));
    }

    // ---- AC-SRC-32: walk filter in next body, checks in top statement, key predicates top-only

    @TckTest
    void ac_src_32_walk_filter_in_next_body_excludes_overflow_and_cycle_rows_before_window(TckDatabase db) {
        // FOLDER_CHAIN_ROOT (10 -> 11 -> 12 -> 13) with maxDepth = 2 has 3 valid rows (levels 0, 1, 2) and 1 overflow
        // sentinel row at level 3 (id 13). RankedFolderNode orders ROW_NUMBER by level desc, id asc: because the walk
        // filter (level <= 2) runs inside RankedFolderNode's body before its window, id 13 is never ranked and the 3
        // valid rows receive ranks 1, 2, 3 (not 2, 3, 4).
        SourceQuery<FolderNode> deepChain = checkedTree(2, TckFixture.FOLDER_CHAIN_ROOT);
        SourceQuery<RankedFolderNode> rankedDeep = QRankedFolderNode.source(deepChain).build();
        ModelQuery<RankedFolderNode, Long, RankedFolderRow> pastRank3 = QRankedFolderRow.query(rankedDeep)
                .select(QRankedFolderRow.ALL)
                .where(f -> f.gt(QRankedFolderRow.RANK, 3L))
                .orderBy(QRankedFolderRow.ID.asc())
                .build();
        ModelQuery<RankedFolderNode, Long, RankedFolderRow> wrongRootRank = QRankedFolderRow.query(rankedDeep)
                .select(QRankedFolderRow.ALL)
                .where(f -> f.eq(QRankedFolderRow.LEVEL, 0).ne(QRankedFolderRow.RANK, 3L))
                .orderBy(QRankedFolderRow.ID.asc())
                .build();

        // FOLDER_CYCLE_ROOT (100 -> 101 -> 100) has 2 valid rows (levels 0 and 1) and 1 cycle-marked row at level 2.
        // Because not mark runs inside RankedFolderNode's body before its window, the cycle row at level 2 is never
        // ranked and the 2 valid rows receive ranks 1 and 2.
        SourceQuery<FolderNode> cyclic = checkedTree(5, TckFixture.FOLDER_CYCLE_ROOT);
        SourceQuery<RankedFolderNode> rankedCyclic = QRankedFolderNode.source(cyclic).build();
        ModelQuery<RankedFolderNode, Long, RankedFolderRow> pastRank2 = QRankedFolderRow.query(rankedCyclic)
                .select(QRankedFolderRow.ALL)
                .where(f -> f.gt(QRankedFolderRow.RANK, 2L))
                .orderBy(QRankedFolderRow.ID.asc())
                .build();
        ModelQuery<RankedFolderNode, Long, RankedFolderRow> wrongCycleRootRank = QRankedFolderRow.query(rankedCyclic)
                .select(QRankedFolderRow.ALL)
                .where(f -> f.eq(QRankedFolderRow.LEVEL, 0).ne(QRankedFolderRow.RANK, 2L))
                .orderBy(QRankedFolderRow.ID.asc())
                .build();

        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedFolderNode.class, executor -> {
            assertThat(executor.list(pastRank3, Limit.unlimited())).isEmpty();
            assertThat(executor.list(wrongRootRank, Limit.unlimited())).isEmpty();
            assertThat(executor.list(pastRank2, Limit.unlimited())).isEmpty();
            assertThat(executor.list(wrongCycleRootRank, Limit.unlimited())).isEmpty();
        }));
    }

    @TckTest
    void ac_src_32_checks_live_in_top_statement_on_every_statement_kind_including_filtered_groups(TckDatabase db) {
        SourceQuery<FolderNode> deep = checkedTree(2, TckFixture.FOLDER_CHAIN_ROOT);
        SourceQuery<FolderNode> cyclic = checkedTree(5, TckFixture.FOLDER_CYCLE_ROOT);
        SourceQuery<FolderNode> overlap = checkedTree(3, 1L, 2L);

        // When SubtreeSize's HAVING drops every group, list returns empty ("no row, no check", R-SRC-14), but count
        // still raises MQ2701 / MQ2702 / MQ2703 because the top statement selects the check sub-selects over the inner
        // recursive CTE directly.
        ModelQuery<SubtreeSize, Integer, SubtreeSizeRow> emptyDeepGroups = QSubtreeSizeRow.query(
                QSubtreeSize.source(deep).having(h -> h.gt(QSubtreeSize.NODES, 999L)).build())
                .select(QSubtreeSizeRow.ALL).orderBy(QSubtreeSizeRow.LEVEL.asc()).build();
        ModelQuery<SubtreeSize, Integer, SubtreeSizeRow> emptyCyclicGroups = QSubtreeSizeRow.query(
                QSubtreeSize.source(cyclic).having(h -> h.gt(QSubtreeSize.NODES, 999L)).build())
                .select(QSubtreeSizeRow.ALL).orderBy(QSubtreeSizeRow.LEVEL.asc()).build();
        ModelQuery<SubtreeSize, Integer, SubtreeSizeRow> emptyOverlapGroups = QSubtreeSizeRow.query(
                QSubtreeSize.source(overlap).having(h -> h.gt(QSubtreeSize.NODES, 999L)).build())
                .select(QSubtreeSizeRow.ALL).orderBy(QSubtreeSizeRow.LEVEL.asc()).build();

        // When SubtreeSize's HAVING drops level 1 (nodes == 4, where the duplicate copy of node 2 is reached),
        // keeping levels 0 (nodes == 2) and 2 (nodes == 3), every statement kind on the outer query still raises
        // MQ2703 because the top statement checks the inner recursive CTE directly.
        SourceQuery<SubtreeSize> survivingGroupOverOverlap = QSubtreeSize.source(overlap)
                .having(h -> h.ne(QSubtreeSize.NODES, 4L))
                .build();
        ModelQuery<SubtreeSize, Integer, SubtreeSizeRow> keysetOverlap = QSubtreeSizeRow.query(
                survivingGroupOverOverlap)
                .select(QSubtreeSizeRow.ALL).orderBy(QSubtreeSizeRow.LEVEL.asc()).keyset().build();
        ModelQuery<SubtreeSize, Integer, SubtreeSizeRow> pkFirstOverlap = QSubtreeSizeRow.query(
                survivingGroupOverOverlap)
                .select(QSubtreeSizeRow.ALL).orderBy(QSubtreeSizeRow.LEVEL.asc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();

        SqlSnapshots.capture(db, ds -> withExecutor(ds, SubtreeSize.class, executor -> {
            assertThat(executor.list(emptyDeepGroups, Limit.unlimited())).isEmpty();
            assertThat(executor.list(emptyCyclicGroups, Limit.unlimited())).isEmpty();
            assertThat(executor.list(emptyOverlapGroups, Limit.unlimited())).isEmpty();

            assertThat(assertCode(() -> executor.count(emptyDeepGroups), MqCode.MQ2701))
                    .hasMessageContaining("SubtreeSizeRow").hasMessageContaining("maxDepth(2)");
            assertThat(assertCode(() -> executor.count(emptyCyclicGroups), MqCode.MQ2702))
                    .hasMessageContaining("SubtreeSizeRow").hasMessageContaining("100");
            assertThat(assertCode(() -> executor.count(emptyOverlapGroups), MqCode.MQ2703))
                    .hasMessageContaining("SubtreeSizeRow").hasMessageContaining("id=2");

            List<Runnable> statementKinds = List.of(
                    () -> executor.list(keysetOverlap, Limit.unlimited()),
                    () -> executor.count(keysetOverlap),
                    () -> executor.byKeys(keysetOverlap, List.of(0)),
                    () -> executor.one(keysetOverlap, 0),
                    () -> executor.page(keysetOverlap, KeysetSpec.first(2)),
                    () -> executor.page(pkFirstOverlap, PageSpec.ofOffset(0, 1), CountMode.NO_COUNT),
                    () -> executor.page(pkFirstOverlap, PageSpec.ofOffset(1, 1), CountMode.NO_COUNT),
                    () -> executor.export(keysetOverlap, ExportOptions.of(1), p -> p, r -> {}),
                    () -> executor.export(pkFirstOverlap, ExportOptions.of(1), p -> p, r -> {}));
            for (Runnable kind : statementKinds) {
                assertThat(assertCode(kind, MqCode.MQ2703))
                        .hasMessageContaining("SubtreeSizeRow")
                        .hasMessageContaining("id=2");
            }
        }));
    }

    @TckTest
    void ac_src_32_key_predicates_apply_only_to_top_statement_so_ranks_see_full_source(TckDatabase db) {
        SourceQuery<FolderNode> tree = checkedTree(3, TckFixture.FOLDER_TREE_ROOT);
        SourceQuery<RankedFolderNode> ranked = QRankedFolderNode.source(tree).build();

        ModelQuery<RankedFolderNode, Long, RankedFolderRow> keysetQuery = QRankedFolderRow.query(ranked)
                .select(QRankedFolderRow.ALL)
                .orderBy(QRankedFolderRow.ID.asc())
                .keyset()
                .build();
        ModelQuery<RankedFolderNode, Long, RankedFolderRow> pkFirstQuery = QRankedFolderRow.query(ranked)
                .select(QRankedFolderRow.ALL)
                .orderBy(QRankedFolderRow.ID.asc())
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                .build();

        SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedFolderNode.class, executor -> {
            // In the full tree (6 nodes), node 1 (level 0) has rank 6 and node 3 (level 1) has rank 5.
            // If key predicates entered RankedFolderNode's body, their ranks would be recomputed over the subset.
            assertThat(executor.one(keysetQuery, 1L)).contains(new RankedFolderRow(1L, null, "node-1", 0, 6L));
            assertThat(rankedFolders(new ArrayList<>(executor.byKeys(keysetQuery, List.of(1L, 3L)).values())))
                    .containsExactlyInAnyOrder("1|null|node-1|0|6", "3|1|node-3|1|5");

            KeysetSlice<RankedFolderRow> first = executor.page(keysetQuery, KeysetSpec.first(2));
            KeysetSlice<RankedFolderRow> second = executor.page(
                    keysetQuery, KeysetSpec.after(first.nextCursor().orElseThrow(), 2));
            KeysetSlice<RankedFolderRow> third = executor.page(
                    keysetQuery, KeysetSpec.after(second.nextCursor().orElseThrow(), 2));
            assertThat(rankedFolders(first.content())).containsExactly("1|null|node-1|0|6", "2|1|node-2|1|4");
            assertThat(rankedFolders(second.content())).containsExactly("3|1|node-3|1|5", "4|2|node-4|2|1");
            assertThat(rankedFolders(third.content())).containsExactly("5|2|node-5|2|2", "6|3|node-6|2|3");

            Slice<RankedFolderRow> step2Page = executor.page(
                    pkFirstQuery, PageSpec.ofOffset(2, 2), CountMode.NO_COUNT);
            assertThat(rankedFolders(step2Page.content())).containsExactly("3|1|node-3|1|5", "4|2|node-4|2|1");

            List<RankedFolderRow> keysetExported = new ArrayList<>();
            executor.export(keysetQuery, ExportOptions.of(2), p -> p, keysetExported::add);
            assertThat(rankedFolders(keysetExported)).containsExactly(
                    "1|null|node-1|0|6", "2|1|node-2|1|4", "3|1|node-3|1|5",
                    "4|2|node-4|2|1", "5|2|node-5|2|2", "6|3|node-6|2|3");

            List<RankedFolderRow> offsetExported = new ArrayList<>();
            executor.export(pkFirstQuery, ExportOptions.of(2), p -> p, offsetExported::add);
            assertThat(rankedFolders(offsetExported)).containsExactly(
                    "1|null|node-1|0|6", "2|1|node-2|1|4", "3|1|node-3|1|5",
                    "4|2|node-4|2|1", "5|2|node-5|2|2", "6|3|node-6|2|3");
        }));
    }

    // ---- AC-FLT-35: CTE bodies under inline value handling

    /**
     * Under inline value handling the filter values of each CTE body, a chained source's inner and outer bodies, a
     * recursive walk's seed and a source's over a walk, bind on the statement that registers the CTE, a list's and a
     * count's alike, and read the default setting's rows (api/12 R-FLT-08; TCK AC-FLT-35).
     */
    @TckTest
    void ac_src_26_ac_flt_35_under_inline_value_handling_each_cte_bodys_filter_values_bind(TckDatabase db) {
        SourceQuery<OrderSource> inner = QOrderSource.source()
                .where(f -> f.in(QOrderSource.STATUS, List.of("PAID", "SHIPPED"))).build();
        var ranked = QRankedOrderFeed.query(QRankedOrderSource.source(inner)
                        .where(f -> f.gte(QRankedOrderSource.ORDER_TOTAL, new BigDecimal("50.00"))).build())
                .select(QRankedOrderFeed.ALL).orderBy(QRankedOrderFeed.STATUS.asc(), QRankedOrderFeed.RANK.asc())
                .build();
        // The whole chain, so no node is past maxDepth
        SourceQuery<FolderNode> chain = walk(TckFixture.FOLDER_CHAIN_ROOT, TckFixture.FOLDER_CHAIN_NODES);
        var walked = QFolderRow.query(chain).select(QFolderRow.ALL).orderBy(QFolderRow.ID.asc()).build();
        var rankedWalk = QRankedFolderRow.query(QRankedFolderNode.source(chain).build()).select(QRankedFolderRow.ALL)
                .orderBy(QRankedFolderRow.ID.asc()).build();

        assertReadsAsDefault(db, RankedOrderSource.class, ranked, "PAID", "SHIPPED", new BigDecimal("50.00"));
        assertReadsAsDefault(db, FolderNode.class, walked, TckFixture.FOLDER_CHAIN_ROOT);
        assertReadsAsDefault(db, RankedFolderNode.class, rankedWalk, TckFixture.FOLDER_CHAIN_ROOT);
    }

    /** {@code query}'s list and count under inline value handling, {@code inputs} watched, are the default's. */
    private static <E> void assertReadsAsDefault(TckDatabase db, Class<E> root, ModelQuery<E, ?, ?> query,
            Object... inputs) {
        List<Object> expected = new ArrayList<>();
        withExecutor(JoinTestSupport.dataSource(db), root, executor -> expected.addAll(reads(executor, query)));
        assertThat((List<?>) expected.get(0)).isNotEmpty();
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            inline.watch(inputs);
            List<Object> read = new ArrayList<>();
            inline.factory().inSession(em -> read.addAll(reads(ModelQueryExecutor.create(em, root,
                    ModelQueryConfig.defaults()), query)));
            assertThat(read).isEqualTo(expected);
            assertThat(inline.statements()).isNotEmpty();
        }
    }

    private static <E> List<Object> reads(ModelQueryExecutor<E> executor, ModelQuery<E, ?, ?> query) {
        return List.of(executor.list(query, Limit.unlimited()), executor.count(query));
    }

    // ---- Helpers

    private static SourceQuery<FolderNode> walk(long root, int maxDepth) {
        return QFolderNode.source().where(f -> f.eq(QFolderNode.ID, root))
                .recursive(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
    }

    private static SourceQuery<FolderNode> checkedTree(int maxDepth, Long... seeds) {
        return QFolderNode.source().where(f -> f.in(QFolderNode.ID, List.of(seeds)))
                .tree(step -> step.childKey(QFolderNode.PARENT_ID).parentKey(QFolderNode.ID), maxDepth).build();
    }

    private static ModelQueryException assertCode(Runnable work, MqCode code) {
        Throwable refusal = catchThrowable(work::run);
        assertThat(refusal).isInstanceOf(ModelQueryException.class);
        assertThat(((ModelQueryException) refusal).code()).isEqualTo(code);
        return (ModelQueryException) refusal;
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }

    private static List<String> rankedOrders(List<RankedOrderFeed> rows) {
        return rows.stream().map(r -> normalised(r.id()) + "|" + r.status() + "|"
                + normalised(r.total()) + "|" + normalised(r.rank())).toList();
    }

    private static List<String> activities(List<RankedActivityRow> rows) {
        return rows.stream().map(r -> r.branch() + "|" + normalised(r.id()) + "|"
                + normalised(r.label()) + "|" + normalised(r.rank())).toList();
    }

    private static List<String> rankedFolders(List<RankedFolderRow> rows) {
        return rows.stream().map(r -> normalised(r.id()) + "|" + normalised(r.parentId()) + "|"
                + normalised(r.name()) + "|" + normalised(r.level()) + "|" + normalised(r.rank())).toList();
    }

    private static List<String> subtreeSizes(List<SubtreeSizeRow> rows) {
        return rows.stream().map(r -> normalised(r.level()) + "|" + normalised(r.nodes())).toList();
    }

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

    private static String normalised(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }
}
