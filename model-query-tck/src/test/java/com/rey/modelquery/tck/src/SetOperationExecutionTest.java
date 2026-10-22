package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
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
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Set operations over sources: {@code union}, {@code unionAll}, {@code intersect} and {@code except}, the key
 * uniqueness each gives its result and the reads it decides (spec api/17 AC-SRC-07 to AC-SRC-11, R-SRC-05, R-SRC-06,
 * R-SRC-09; RFC 0013 §4, §6).
 */
class SetOperationExecutionTest {

    private static final SourceQuery<DispatchFeed> LIVE = QDispatchFeed.source().build();
    private static final SourceQuery<DispatchFeed> SHELVED = QShelvedDispatchFeed.source().build();
    /** The live branch and the shelved one: each fixes its own discriminator, so no key repeats (R-SRC-06). */
    private static final SourceQuery<DispatchFeed> DISJOINT = LIVE.union(SHELVED);

    // ---- AC-SRC-07

    @TckTest
    void ac_src_07_a_union_renders_the_distinct_sql_and_removes_the_rows_both_branches_yield(TckDatabase db)
            throws SQLException {
        List<DispatchRow> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-07-union", ds -> withExecutor(ds, DispatchFeed.class,
                executor -> rows.addAll(executor.list(rows(DISJOINT), Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(rows).hasSize(TckFixture.ORDERS + TckFixture.NULLABLE_SORT_ROWS);
        assertThat(dispatch(rows)).containsExactlyInAnyOrderElementsOf(rows(db,
                "select 'live', id, status from orders union select 'shelved', id, sort_text from nullable_sort_rows"));
    }

    @TckTest
    void ac_src_07_a_union_all_renders_the_sql_that_keeps_the_copies(TckDatabase db) throws SQLException {
        List<DispatchRow> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-07-union-all", ds -> withExecutor(ds,
                DispatchFeed.class, executor -> rows.addAll(executor.list(rows(LIVE.unionAll(SHELVED)),
                        Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(dispatch(rows)).containsExactlyInAnyOrderElementsOf(
                rows(db, "select 'live', id, status from orders "
                        + "union all select 'shelved', id, sort_text from nullable_sort_rows"));
    }

    @TckTest
    void ac_src_07_an_intersect_renders_the_sql_that_keeps_the_shared_rows(TckDatabase db) throws SQLException {
        List<DispatchRow> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-07-intersect", ds -> withExecutor(ds,
                DispatchFeed.class, executor -> rows.addAll(executor.list(rows(LIVE.intersect(SHELVED)),
                        Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        // The branches' discriminators differ, so no row of one is a row of the other.
        assertThat(rows).isEmpty();
        assertThat(rows(db, "select 'live', id, status from orders intersect "
                + "select 'shelved', id, sort_text from nullable_sort_rows")).isEmpty();
    }

    @TckTest
    void ac_src_07_an_except_renders_the_sql_that_removes_the_right_branch(TckDatabase db) throws SQLException {
        List<DispatchRow> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-07-except", ds -> withExecutor(ds, DispatchFeed.class,
                executor -> rows.addAll(executor.list(rows(LIVE.except(SHELVED)), Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(dispatch(rows)).containsExactlyInAnyOrderElementsOf(
                rows(db, "select 'live', id, status from orders "
                        + "except select 'shelved', id, sort_text from nullable_sort_rows"));
    }

    @TckTest
    void ac_src_07_a_nested_operation_renders_the_tree_in_the_order_it_was_written(TckDatabase db) throws SQLException {
        List<DispatchRow> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-07-nested", ds -> withExecutor(ds, DispatchFeed.class,
                executor -> rows.addAll(executor.list(rows(DISJOINT.except(SHELVED)), Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(dispatch(rows)).containsExactlyInAnyOrderElementsOf(rows(db,
                "(select 'live', id, status from orders union select 'shelved', id, sort_text from nullable_sort_rows)"
                        + " except select 'shelved', id, sort_text from nullable_sort_rows"));
    }

    // ---- AC-SRC-08

    @TckTest
    void ac_src_08_a_discriminated_feed_is_exported_by_keyset_without_a_repeated_key(TckDatabase db) {
        List<DispatchRow> exported = new ArrayList<>();
        long[] count = new long[1];
        withExecutor(db, DispatchFeed.class, executor -> count[0] = executor.export(
                rowsBuilder(DISJOINT).keyset().build(), ExportOptions.of(500), page -> page, exported::add));

        assertThat(count[0]).isEqualTo(TckFixture.ORDERS + TckFixture.NULLABLE_SORT_ROWS);
        assertThat(exported).doesNotHaveDuplicates();
    }

    /**
     * Under inline value handling a keyset page over the feed binds its cursor's values, the branch tag and the id,
     * while the branches' own tags stay structural literals of their CTE bodies; it reads the default setting's pages
     * (api/12 R-FLT-08; TCK AC-FLT-35's {@code source-keyset}).
     */
    @TckTest
    void ac_src_08_ac_flt_35_under_inline_value_handling_a_keyset_cursor_over_a_feed_binds_its_values(
            TckDatabase db) {
        var query = rowsBuilder(DISJOINT).keyset().build();
        List<List<DispatchRow>> expected = new ArrayList<>();
        withExecutor(db, DispatchFeed.class, executor -> expected.addAll(twoPages(executor, query, null)));
        assertThat(expected.get(1)).hasSize(5);

        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            List<List<DispatchRow>> pages = new ArrayList<>();
            List<List<String>> statements = new ArrayList<>();
            inline.factory().inSession(em -> pages.addAll(twoPages(ModelQueryExecutor.create(em, DispatchFeed.class,
                    ModelQueryConfig.defaults()), query, new PageWatch(inline, statements))));

            assertThat(pages).isEqualTo(expected);
            // Page 2's cursor (live, 7) adds no 'live': only the bodies' discriminator tags carry one
            assertThat(tags(statements.get(1))).isEqualTo(tags(statements.get(0))).isPositive();
        }
    }

    /** Watches each page's statements, the second page's cursor id as an input. */
    private record PageWatch(InlineValueHandling inline, List<List<String>> statements) {}

    /** Page 1 of 7 rows over {@code query}, then the 5 rows after its cursor. */
    private static List<List<DispatchRow>> twoPages(ModelQueryExecutor<DispatchFeed> executor,
            ModelQuery<DispatchFeed, ?, DispatchRow> query, PageWatch watch) {
        if (watch != null) {
            watch.inline().watch();
        }
        KeysetSlice<DispatchRow> first = executor.page(query, KeysetSpec.first(7));
        if (watch != null) {
            watch.statements().add(watch.inline().statements());
            watch.inline().watch(List.of(first.content().get(6).id()), List.of("'live'", "'shelved'"));
        }
        KeysetSlice<DispatchRow> second = executor.page(query, KeysetSpec.after(first.nextCursor().orElseThrow(), 5));
        if (watch != null) {
            watch.statements().add(watch.inline().statements());
        }
        return List.of(first.content(), second.content());
    }

    /** How many {@code 'live'} literals {@code statements} carry. */
    private static long tags(List<String> statements) {
        return statements.stream().mapToLong(sql -> sql.split("'live'", -1).length - 1L).sum();
    }

    // ---- AC-SRC-09

    @TckTest
    void ac_src_09_an_owned_union_shared_list_from_one_branch_model_is_paged_by_keyset(TckDatabase db) {
        SourceQuery<OrderSource> owned = QOrderSource.source().where(f -> f.eq(QOrderSource.STATUS, "PAID")).build();
        var query = QOrderFeed.query(owned.union(QOrderSource.source().build())).select(QOrderFeed.ALL)
                .orderBy(QOrderFeed.ID.asc()).keyset().build();

        List<OrderFeed> walked = new ArrayList<>();
        withExecutor(db, OrderSource.class, executor -> {
            KeysetSpec spec = KeysetSpec.first(500);
            while (true) {
                KeysetSlice<OrderFeed> page = executor.page(query, spec);
                walked.addAll(page.content());
                if (!page.hasNext()) {
                    return;
                }
                spec = KeysetSpec.after(page.nextCursor().orElseThrow(), 500);
            }
        });

        // The owned rows are the shared ones' subset with the same columns, so the distinct union is the shared list.
        assertThat(walked).hasSize(TckFixture.ORDERS);
        assertThat(walked.stream().map(OrderFeed::id).toList()).isSorted().doesNotHaveDuplicates();
    }

    // ---- AC-SRC-10

    @TckTest
    void ac_src_10_offset_pages_over_a_non_unique_union_all_with_duplicate_keys_and_ties_lose_no_row(
            TckDatabase db) {
        // Every order twice, and only four distinct status values to order by: pages end on ties.
        var query = QOrderFeed.query(twice()).select(QOrderFeed.ALL).orderBy(QOrderFeed.STATUS.asc()).build();

        List<OrderFeed> walked = new ArrayList<>();
        List<OrderFeed> all = new ArrayList<>();
        withExecutor(db, OrderSource.class, executor -> {
            int page = 0;
            Slice<OrderFeed> slice;
            do {
                slice = executor.page(query, PageSpec.of(page++, 700), CountMode.NO_COUNT);
                walked.addAll(slice.content());
            } while (slice.hasNext());
            all.addAll(executor.list(query, Limit.unlimited()));
        });

        assertThat(walked).hasSize(2 * TckFixture.ORDERS);
        assertThat(all).hasSize(2 * TckFixture.ORDERS);
        assertThat(walked).containsExactlyInAnyOrderElementsOf(all);
        // Every order is read exactly twice across the pages: no row lost to a tie, none repeated by one.
        assertThat(walked.stream().collect(java.util.stream.Collectors.groupingBy(OrderFeed::id,
                java.util.stream.Collectors.counting()))).hasSize(TckFixture.ORDERS)
                .allSatisfy((id, times) -> assertThat(times).isEqualTo(2L));
    }

    // ---- AC-SRC-11

    @TckTest
    void ac_src_11_mq1904_is_raised_before_any_statement(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            SourceQuery<DispatchFeed> live = QDispatchFeed.source().build();
            assertThatThrownBy(() -> live.unionAll(live))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1904))
                    .hasMessageContaining("live");
        });

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_11_mq1901_is_raised_before_any_statement(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> assertThatThrownBy(() -> QOrderFeed.query(twice())
                .select(QOrderFeed.ALL).orderBy(QOrderFeed.ID.asc()).keyset().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1901))
                .hasMessageContaining("no unique key"));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_11_mq2212_is_raised_before_any_statement(TckDatabase db) {
        var feed = QOrderFeed.query(twice()).select(QOrderFeed.ALL).orderBy(QOrderFeed.ID.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderSource.class, executor ->
                assertThatThrownBy(() -> executor.byKeys(feed, List.of(1L, 2L)))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2212))
                        .hasMessageContaining("byKeys")));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_11_mq2212_is_raised_for_one_by_key_and_export_before_any_statement(TckDatabase db) {
        var feed = QOrderFeed.query(twice()).select(QOrderFeed.ALL).orderBy(QOrderFeed.ID.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderSource.class, executor -> {
            assertThatThrownBy(() -> executor.one(feed, 1L))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2212));
            assertThatThrownBy(() -> executor.export(feed, ExportOptions.of(500), page -> page, row -> { }))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2212));
        }));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_11_mq2212_is_raised_for_a_fetch_plan_with_a_child_over_a_non_unique_source(TckDatabase db) {
        var plan = FetchPlan.of(QOrderItemsFeed.ALL).child(QOrderItemsFeed.ITEMS, FetchPlan.of(QOrderItemLine.ALL));
        var feed = QOrderItemsFeed.query(twice()).orderBy(QOrderItemsFeed.ID.asc()).fetch(plan).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderSource.class, executor -> {
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

    // ---- support

    /** Two reads of one model as one {@code unionAll}: every row appears twice, so its key repeats (R-SRC-05). */
    private static SourceQuery<OrderSource> twice() {
        return QOrderSource.source().build().unionAll(QOrderSource.source().build());
    }

    /** The outer model's query over {@code source}, ordered by the source's key. */
    private static ModelQuery.Builder<DispatchFeed, ?, DispatchRow> rowsBuilder(SourceQuery<DispatchFeed> source) {
        return QDispatchRow.query(source).select(QDispatchRow.ALL)
                .orderBy(QDispatchRow.BRANCH.asc(), QDispatchRow.ID.asc());
    }

    private static ModelQuery<DispatchFeed, ?, DispatchRow> rows(SourceQuery<DispatchFeed> source) {
        return rowsBuilder(source).build();
    }

    /** The feed rows as {@code branch|id|label} texts, comparable with a hand-written SQL's. */
    private static List<String> dispatch(List<DispatchRow> rows) {
        return rows.stream().map(row -> row.branch() + "|" + normalised(row.id()) + "|" + normalised(row.label()))
                .toList();
    }

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        withExecutor(JoinTestSupport.dataSource(db), root, work);
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
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
