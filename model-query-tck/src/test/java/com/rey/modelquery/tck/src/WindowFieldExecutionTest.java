package com.rey.modelquery.tck.src;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * The window fields of a source, read by an outer model (spec api/17 R-SRC-04). Each case reads the rows the
 * hand-written SQL reads — with the source key appended where R-SRC-04 appends it — and pins the SQL per vendor, and
 * the top-N case pages its rows by keyset and by primary-key-first over a partition whose timestamp ties.
 */
class WindowFieldExecutionTest {

    /**
     * {@link WindowSource}'s CTE, written by hand: the same window per function and frame, with the source key appended
     * to the order of those R-SRC-04 makes total. The order-sensitivity is readable off each line: the key appears
     * after {@code placed_at} exactly where the window's values need it, and the {@code idOrder} window orders by the
     * key alone, which is not repeated.
     */
    private static final String WINDOWS_SQL = "select id,"
            + " row_number() over (partition by status order by placed_at, id),"
            + " row_number() over (partition by status order by id),"
            + " rank() over (partition by status order by placed_at),"
            + " dense_rank() over (partition by status order by placed_at),"
            + " lag(total) over (partition by status order by placed_at, id),"
            + " lead(total) over (partition by status order by placed_at, id),"
            + " first_value(total) over (partition by status order by placed_at, id"
            + " rows between unbounded preceding and unbounded following),"
            + " last_value(total) over (partition by status order by placed_at, id"
            + " rows between unbounded preceding and unbounded following),"
            + " sum(total) over (partition by status order by placed_at, id"
            + " rows between unbounded preceding and current row),"
            + " sum(total) over (partition by status order by placed_at"
            + " range between unbounded preceding and current row),"
            + " avg(id) over (partition by status order by placed_at, id"
            + " rows between unbounded preceding and unbounded following),"
            + " min(total) over (partition by status order by placed_at"
            + " range between unbounded preceding and unbounded following),"
            + " max(total) over (partition by status order by placed_at"
            + " range between unbounded preceding and unbounded following),"
            + " count(total) over (partition by status order by placed_at, id"
            + " rows between current row and unbounded following)"
            + " from orders order by id";

    /** The first three rows of each partition, as the CTE's own window orders them (R-SRC-04). */
    private static final String TOP_N_SQL = "select id, sort_int, rk from (select id, sort_int,"
            + " row_number() over (partition by sort_int order by sort_ts desc, id) rk from nullable_sort_rows"
            + " where sort_int is not null and sort_ts is not null) t where rk <= 3 order by sort_int, rk";

    private static final int PAGE = 5;

    // ---- AC-SRC-05

    @TckTest
    void ac_src_05_every_window_function_reads_the_values_its_hand_written_sql_reads(TckDatabase db)
            throws SQLException {
        var feed = QWindowFeed.query(QWindowSource.source().build()).select(QWindowFeed.ALL)
                .orderBy(QWindowFeed.ID.asc()).build();
        List<WindowFeed> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-05-window-functions", ds -> withExecutor(ds,
                WindowSource.class, executor -> rows.addAll(executor.list(feed, Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(rows).hasSize(5_000);
        assertThat(windows(rows)).isEqualTo(rows(db, WINDOWS_SQL));
    }

    @TckTest
    void ac_src_05_a_grouped_source_ranks_its_groups_by_the_aggregate_its_window_names(TckDatabase db)
            throws SQLException {
        var feed = QRankedQuantityFeed.query(QRankedQuantitySource.source().build()).select(QRankedQuantityFeed.ALL)
                .orderBy(QRankedQuantityFeed.QUANTITY.asc()).build();
        List<RankedQuantityFeed> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-05-grouped-window", ds -> withExecutor(ds,
                RankedQuantitySource.class, executor -> rows.addAll(executor.list(feed, Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(rows).hasSize(9);
        assertThat(places(rows)).isEqualTo(rows(db, "select quantity, count(id), sum(unit_price),"
                + " rank() over (order by sum(unit_price) desc) from order_items group by quantity order by quantity"));
    }

    // ---- AC-SRC-06

    @TckTest
    void ac_src_06_a_top_n_source_over_tied_timestamps_reads_the_rows_its_hand_written_sql_reads(TckDatabase db)
            throws SQLException {
        var feed = topN().build();
        List<RankedSort> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-06-top-n", ds -> withExecutor(ds, RankedSortRow.class,
                executor -> rows.addAll(executor.list(feed, Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(values(rows)).isEqualTo(rows(db, TOP_N_SQL));
        // Every row of a partition ties on the timestamp the window orders by, so the rank of a row is decided by the
        // source key alone: without it appended, the three rows a partition keeps would differ between runs.
        assertThat(rows.stream().collect(groupingBy(RankedSort::sortInt, mapping(RankedSort::sortTs, toSet()))))
                .allSatisfy((partition, timestamps) -> assertThat(timestamps).hasSize(1));
        Set<Integer> partitions = rows.stream().map(RankedSort::sortInt).collect(toSet());
        assertThat(partitions).isNotEmpty();
        assertThat(rows).hasSize(3 * partitions.size());
    }

    @TckTest
    void ac_src_06_a_top_n_source_paged_by_keyset_and_by_primary_key_first_loses_and_repeats_no_row(TckDatabase db) {
        var keyset = topN().keyset().build();
        var twoStep = topN().primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        List<RankedSort> all = new ArrayList<>();
        List<RankedSort> exported = new ArrayList<>();
        List<RankedSort> paged = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, RankedSortRow.class, executor -> {
            all.addAll(executor.list(keyset, Limit.unlimited()));
            executor.export(keyset, ExportOptions.of(PAGE), page -> page, exported::add);
        }));

        assertThat(all).isNotEmpty();
        assertThat(exported).extracting(RankedSort::id).containsExactlyInAnyOrderElementsOf(ids(all));
        assertThat(values(exported)).isEqualTo(values(all));
        // A second read walks the same rows a page at a time, again through primary-key-first paging: a page that
        // repeated a row of the page before is MQ2205 (R-PAG-14), and every page's rows are compared to the whole
        // read's, so a row lost or repeated between pages fails here.
        int pages = (all.size() + PAGE - 1) / PAGE;
        withExecutor(db, RankedSortRow.class, executor -> {
            for (int page = 0; page < pages; page++) {
                paged.addAll(executor.page(twoStep, PageSpec.of(page, PAGE), CountMode.NO_COUNT).content());
            }
        });

        assertThat(values(paged)).isEqualTo(values(all));
        assertThat(sql).isNotEmpty()
                .allSatisfy(statement -> assertThat(statement).containsIgnoringCase("nullable_sort_rows"));
    }

    /** The top-N read: the first three rows of each partition, ordered by the partition and the rank. */
    private static ModelQuery.Builder<RankedSortRow, Long, RankedSort> topN() {
        SourceQuery<RankedSortRow> source = QRankedSortRow.source()
                .where(f -> f.isNotNull(QRankedSortRow.SORT_INT).isNotNull(QRankedSortRow.SORT_TS)).build();
        return QRankedSort.query(source).select(QRankedSort.ALL)
                .where(f -> f.lte(QRankedSort.RANKING, Long.valueOf(3)))
                .orderBy(QRankedSort.SORT_INT.asc(), QRankedSort.RANKING.asc());
    }

    // ---- helpers

    private static List<Long> ids(List<RankedSort> rows) {
        return rows.stream().map(RankedSort::id).toList();
    }

    /** Every field of {@link WindowFeed} as {@code id} plus its window values, comparable with a hand-written SQL's. */
    private static List<String> windows(List<WindowFeed> rows) {
        return rows.stream().map(row -> text(row.id(), row.rowIndex(), row.idOrder(), row.ranking(), row.denseRanking(),
                row.previousTotal(), row.nextTotal(), row.firstTotal(), row.lastTotal(), row.runningTotal(),
                row.rangeTotal(), row.averageId(), row.smallestTotal(), row.largestTotal(), row.remaining())).toList();
    }

    /** The groups {@link RankedQuantitySource} read: the quantity, its two aggregates and its rank. */
    private static List<String> places(List<RankedQuantityFeed> rows) {
        return rows.stream().map(row -> text(row.quantity(), row.lineCount(), row.revenue(), row.ranking())).toList();
    }

    /** The top-N rows as {@code id|sortInt|ranking} texts, the order the window and the query agree on. */
    private static List<String> values(List<RankedSort> rows) {
        return rows.stream().map(row -> text(row.id(), row.sortInt(), row.ranking())).toList();
    }

    private static String text(Object... values) {
        StringBuilder row = new StringBuilder();
        for (Object value : values) {
            row.append(row.isEmpty() ? "" : "|").append(normalised(value));
        }
        return row.toString();
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

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        withExecutor(JoinTestSupport.dataSource(db), root, work);
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        withExecutor(ds, root, ModelQueryConfig.defaults(), work);
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, config)));
        }
    }
}
