package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CompositeNodeEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.portability.LegacyRecursiveProviderSupport;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * A query rooted at {@code orders} joining a grouped source of its lines in a quantity band, twice: the join in every
 * statement, {@code LEFT} keeping and {@code INNER} dropping an order with no line in the band, a keyset over a joined
 * column, and the refusals before any statement (spec api/17 R-SRC-21, R-SRC-22, AC-SRC-42 to AC-SRC-47, api/13
 * AC-AGG-29; RFC 0020 Part A).
 */
class SourceJoinExecutionTest {

    /** The source's model: one row per order, its lines in a quantity band counted and their prices summed. */
    static final class Band {}

    record Bands(Long id, Long highLines, BigDecimal highRevenue, Long lowLines) {}

    record Matched(Long id, Long matched) {}

    record Node(Integer tenantId, Long id) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEM = TableField.root(OrderItemEntity.class);
    private static final ColumnField<Band, ?, Long> B_ORDER = ColumnField.of(Band.class,
            TableField.join(ITEM, "order", JoinType.INNER), "id", Long.class).named("orderId");
    private static final ColumnField<Band, OrderItemEntity, Integer> B_QUANTITY =
            ColumnField.of(Band.class, ITEM, "quantity", Integer.class);
    private static final AggregateField<Band, Long> B_LINES = Agg.<Band>count(ITEM).named("lineCount");
    private static final AggregateField<Band, BigDecimal> B_REVENUE =
            Agg.sum(ColumnField.of(Band.class, ITEM, "unitPrice", BigDecimal.class)).named("revenue");

    private static final TableField<OrderEntity, OrderEntity> ORDER = TableField.root(OrderEntity.class);
    private static final OrderedColumnField<Bands, OrderEntity, Long> ID =
            ColumnField.of(Bands.class, ORDER, "id", Long.class).named("id");
    private static final ColumnField<Bands, OrderEntity, Integer> VERSION =
            ColumnField.of(Bands.class, ORDER, "version", Integer.class);
    private static final SourceJoin<Bands, Band> HIGH = band("high", JoinKind.LEFT);
    private static final SourceJoin<Bands, Band> LOW = band("low", JoinKind.LEFT);
    private static final SourceJoin<Bands, Band> HIGH_ONLY = band("high", JoinKind.INNER);
    private static final OrderedColumnField<Bands, Band, Long> HIGH_LINES =
            ColumnField.over(Bands.class, HIGH.root(), B_LINES).named("highLines");
    private static final OrderedColumnField<Bands, Band, BigDecimal> HIGH_REVENUE =
            ColumnField.over(Bands.class, HIGH.root(), B_REVENUE).named("highRevenue");
    private static final OrderedColumnField<Bands, Band, Long> LOW_LINES =
            ColumnField.over(Bands.class, LOW.root(), B_LINES).named("lowLines");

    /** The orders the joining reads keep: few enough to compare row by row, with orders in and out of each band. */
    private static final long LAST_ORDER = 40;

    /** The hand-written SQL of {@link #bands()}: each band a derived table, joined on the order's id. */
    private static final String BANDS_SQL = """
            select o.id, h.n, h.r, l.n from orders o
            left join (select order_id, count(*) n, sum(unit_price) r from order_items where quantity >= 8
                group by order_id) h on h.order_id = o.id
            left join (select order_id, count(*) n from order_items where quantity <= 2
                group by order_id) l on l.order_id = o.id
            where o.id <= 40 order by o.id""";

    private static SourceJoin<Bands, Band> band(String name, JoinKind kind) {
        return SourceJoin.of(name, Band.class, kind, List.of(SourceJoin.on(ID, B_ORDER)));
    }

    /** Every order's lines of quantity {@code min} to {@code max}: both bounds are the body's parameters. */
    private static SourceQuery<Band> lines(int min, int max) {
        return SourceQuery.builder(Band.class, ITEM, SelectSet.of(B_ORDER, B_LINES, B_REVENUE),
                PrimaryKey.of(B_ORDER), SelectSet.of(B_ORDER))
                .where(f -> f.gte(B_QUANTITY, min).lte(B_QUANTITY, max)).build();
    }

    /** Orders 1 to {@link #LAST_ORDER} with their high band (quantity 8 or 9) and low band (1 or 2), both LEFT. */
    private static ModelQuery.Builder<OrderEntity, Long, Bands> bands() {
        return ModelQuery.builder(ORDER, row -> new Bands(row.get(ID), row.get(HIGH_LINES), row.get(HIGH_REVENUE),
                        row.get(LOW_LINES)))
                .joinSource(HIGH, lines(8, 9)).joinSource(LOW, lines(1, 2))
                .select(SelectSet.of(ID, HIGH_LINES, HIGH_REVENUE, LOW_LINES)).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.lte(ID, LAST_ORDER)).orderBy(ID.asc());
    }

    /** Every order with a high line, INNER, ordered by its high band's revenue, highest first. */
    private static ModelQuery.Builder<OrderEntity, Long, Bands> highOnly() {
        var revenue = ColumnField.over(Bands.class, HIGH_ONLY.root(), B_REVENUE).named("highRevenue");
        return ModelQuery.builder(ORDER, row -> new Bands(row.get(ID), null, row.get(revenue), null))
                .joinSource(HIGH_ONLY, lines(8, 9)).select(SelectSet.of(ID, revenue)).primaryKey(PrimaryKey.of(ID))
                .orderBy(revenue.desc(), ID.asc());
    }

    // ---- AC-SRC-42

    @TckTest
    void ac_src_42_two_left_joins_of_one_source_model_return_the_hand_written_sql_s_rows(TckDatabase db)
            throws SQLException {
        List<Bands> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-42-left-joins", ds -> withExecutor(ds,
                executor -> rows.addAll(executor.list(bands().build(), Limit.unlimited()))));

        assertThat(sql).singleElement().satisfies(statement -> assertThat(occurrences(statement, "left join"))
                .as(statement).isEqualTo(2));
        List<String> expected = rows(db, BANDS_SQL);
        assertThat(rows.stream().map(row -> text(row.id(), row.highLines(), row.highRevenue(), row.lowLines())))
                .containsExactlyElementsOf(expected);
        // Both bands keep an order with no line in them, which reads NULL, and they stay apart.
        assertThat(rows).hasSize((int) LAST_ORDER).anyMatch(row -> row.highLines() == null)
                .anyMatch(row -> row.lowLines() == null).anyMatch(row -> row.highLines() != null)
                .anyMatch(row -> !Objects.equals(row.highLines(), row.lowLines()));
    }

    @TckTest
    void ac_agg_29_a_count_of_a_left_joined_source_is_0_for_an_order_it_does_not_match(TckDatabase db)
            throws SQLException {
        var id = ColumnField.of(Matched.class, ORDER, "id", Long.class).named("id");
        var high = SourceJoin.of("high", Band.class, JoinKind.LEFT, List.of(SourceJoin.on(id, B_ORDER)));
        AggregateField<Matched, Long> matched = Agg.count(high.root());
        var perOrder = ModelQuery.builder(ORDER, row -> new Matched(row.get(id), row.get(matched)))
                .joinSource(high, lines(8, 9)).select(SelectSet.of(id, matched)).groupBy(SelectSet.of(id))
                .where(f -> f.lte(id, LAST_ORDER)).orderBy(id.asc()).build();
        var total = ModelQuery.builder(ORDER, row -> new Matched(null, row.get(matched)))
                .joinSource(high, lines(8, 9)).select(SelectSet.of(matched)).where(f -> f.lte(id, LAST_ORDER))
                .build();
        List<Matched> perOrderRows = new ArrayList<>();
        List<Matched> totalRows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            perOrderRows.addAll(executor.list(perOrder, Limit.unlimited()));
            totalRows.addAll(executor.list(total, Limit.unlimited()));
        }));

        List<String> expected = rows(db, """
                select o.id, count(h.order_id) from orders o
                left join (select order_id from order_items where quantity >= 8 group by order_id) h
                    on h.order_id = o.id
                where o.id <= 40 group by o.id order by o.id""");
        assertThat(perOrderRows.stream().map(row -> text(row.id(), row.matched())))
                .containsExactlyElementsOf(expected);
        assertThat(perOrderRows).extracting(Matched::matched).contains(0L, 1L);
        assertThat(totalRows).singleElement().extracting(Matched::matched)
                .isEqualTo(perOrderRows.stream().filter(row -> row.matched() == 1L).count());
    }

    // ---- AC-SRC-43

    @TckTest
    void ac_src_43_every_statement_carries_both_joins_and_binds_each_body_s_parameters_once(TckDatabase db) {
        var keyset = bands().keyset().build();
        var pkFirst = bands().primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0L)).build();
        List<Long> expected = new ArrayList<>();
        for (long id = 1; id <= LAST_ORDER; id++) {
            expected.add(id);
        }
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            var page = executor.page(bands().build(), PageSpec.of(0, 10), CountMode.COUNT);
            assertThat(page.total()).hasValue(LAST_ORDER);
            assertThat(executor.page(pkFirst, PageSpec.ofOffset(10, 5), CountMode.NO_COUNT).content())
                    .extracting(Bands::id).containsExactly(11L, 12L, 13L, 14L, 15L);
            assertThat(executor.page(keyset, KeysetSpec.first(5)).content()).extracting(Bands::id)
                    .containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(executor.byKeys(bands().build(), List.of(3L, 1L))).containsOnlyKeys(1L, 3L);
            assertThat(executor.one(bands().build(), 7L)).get().extracting(Bands::id).isEqualTo(7L);
            assertThat(executor.count(bands().build())).isEqualTo(LAST_ORDER);
            for (var query : List.of(bands().build(), keyset, pkFirst)) {
                List<Long> exported = new ArrayList<>();
                executor.export(query, ExportOptions.of(16), p -> p, row -> exported.add(row.id()));
                assertThat(exported).containsExactlyElementsOf(expected);
            }
        }));

        // content and count, the key step and its model step, keyset, byKeys, one, count, three exports of 3 pages
        assertThat(sql).hasSizeGreaterThanOrEqualTo(15).allSatisfy(statement -> {
            assertThat(occurrences(statement, "left join")).as(statement).isEqualTo(2);
            // Each band's body once, so each of its two bounds once per band.
            assertThat(matches(statement, "quantity ?>= ?\\?")).as(statement).isEqualTo(2);
            assertThat(matches(statement, "quantity ?<= ?\\?")).as(statement).isEqualTo(2);
        });
    }

    // ---- AC-SRC-44

    @TckTest
    void ac_src_44_inner_drops_an_unmatched_order_in_content_and_count_alike(TckDatabase db) throws SQLException {
        var inner = highOnly().where(f -> f.lte(ID, LAST_ORDER)).build();
        List<Bands> rows = new ArrayList<>();
        long[] counted = new long[1];
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            rows.addAll(executor.list(inner, Limit.unlimited()));
            counted[0] = executor.count(inner);
        }));

        List<String> matched = rows(db, """
                select o.id from orders o
                join (select order_id from order_items where quantity >= 8 group by order_id) h on h.order_id = o.id
                where o.id <= 40 order by o.id""");
        assertThat(rows.stream().map(row -> text(row.id()))).containsExactlyInAnyOrderElementsOf(matched);
        assertThat(rows).hasSizeLessThan((int) LAST_ORDER);
        assertThat(counted[0]).isEqualTo(matched.size());
    }

    @TckTest
    void ac_src_44_keyset_pages_over_a_joined_order_key_lose_no_row(TckDatabase db) {
        var keyset = highOnly().where(f -> f.lte(ID, 400L)).keyset().build();
        List<Bands> listed = new ArrayList<>();
        List<Bands> paged = new ArrayList<>();
        List<Bands> exported = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            listed.addAll(executor.list(keyset, Limit.unlimited()));
            KeysetSpec next = KeysetSpec.first(7);
            while (true) {
                var page = executor.page(keyset, next);
                paged.addAll(page.content());
                if (!page.hasNext()) {
                    break;
                }
                next = KeysetSpec.after(page.nextCursor().orElseThrow(), 7);
            }
            executor.export(keyset, ExportOptions.of(7), p -> p, exported::add);
        }));

        assertThat(listed).hasSizeGreaterThan(100);
        assertThat(listed).extracting(Bands::highRevenue).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(paged).extracting(Bands::id).containsExactlyElementsOf(listed.stream().map(Bands::id).toList());
        assertThat(exported).extracting(Bands::id)
                .containsExactlyElementsOf(listed.stream().map(Bands::id).toList());
    }

    // ---- AC-SRC-45

    @TckTest
    void ac_src_45_a_binding_that_misses_the_key_or_a_source_that_is_not_unique_is_mq1910_before_sql(
            TckDatabase db) {
        var nodes = TableField.root(CompositeNodeEntity.class);
        var nodeId = ColumnField.of(Node.class, nodes, "id", Long.class);
        var partial = SourceJoin.of("walk", CompositeNode.class, JoinKind.LEFT,
                List.of(SourceJoin.on(nodeId, QCompositeNode.ID)));
        SourceQuery<CompositeNode> tree = QCompositeNode.source().where(f -> f.eq(QCompositeNode.ID, 1L))
                .tree(step -> step.childKey(QCompositeNode.PARENT_ID).parentKey(QCompositeNode.ID), 3).build();
        SourceQuery<CompositeNode> recursive = QCompositeNode.source().where(f -> f.eq(QCompositeNode.ID, 1L))
                .recursive(step -> step.childKey(QCompositeNode.PARENT_ID).parentKey(QCompositeNode.ID), 3).build();
        var tenant = ColumnField.of(Node.class, nodes, "tenantId", Integer.class);
        var full = SourceJoin.of("walk", CompositeNode.class, JoinKind.LEFT,
                List.of(SourceJoin.on(tenant, QCompositeNode.TENANT_ID), SourceJoin.on(nodeId, QCompositeNode.ID)));
        var extra = SourceJoin.of("extra", Band.class, JoinKind.LEFT,
                List.of(SourceJoin.on(ID, B_ORDER), SourceJoin.on(VERSION, B_QUANTITY)));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            assertThat(mq1910(() -> node(partial, tree))).hasMessageContaining("misses the key field(s) [tenantId]");
            assertThat(mq1910(() -> node(full, recursive))).hasMessageContaining("no unique key")
                    .hasMessageContaining("use tree(...)");
            assertThat(mq1910(() -> bands().joinSource(HIGH, lines(3, 4)).build()))
                    .hasMessageContaining("'high' is bound twice");
            assertThat(mq1910(() -> bands().joinSource(extra, lines(3, 4)).build()))
                    .hasMessageContaining("names quantity, which is not a key field");
            assertThat(mq1910(() -> bands().joinSource(band("both", JoinKind.LEFT),
                    lines(1, 2).unionAll(lines(8, 9))).build())).hasMessageContaining("no unique key")
                    .hasMessageNotContaining("tree(...)");
        }));

        assertThat(sql).isEmpty();
        // The same join over a tree, on the full key, builds.
        assertThat(node(full, tree).joinsSource()).isTrue();
    }

    private static ModelQuery<CompositeNodeEntity, ?, Node> node(SourceJoin<Node, CompositeNode> join,
            SourceQuery<CompositeNode> source) {
        var nodes = TableField.root(CompositeNodeEntity.class);
        var tenant = ColumnField.of(Node.class, nodes, "tenantId", Integer.class);
        var id = ColumnField.of(Node.class, nodes, "id", Long.class);
        return ModelQuery.builder(nodes, row -> new Node(row.get(tenant), row.get(id))).joinSource(join, source)
                .select(SelectSet.of(tenant, id)).build();
    }

    // ---- AC-SRC-46

    @TckTest
    void ac_src_46_a_row_lock_on_a_query_that_joins_a_source_is_mq1903_before_any_statement(TckDatabase db) {
        var locked = bands().build().withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));
        Throwable[] refusal = new Throwable[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> withTransaction(ds,
                executor -> refusal[0] = catchThrowable(() -> executor.list(locked, Limit.of(10)))));

        assertThat(refusal[0]).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1903));
        assertThat(refusal[0]).hasMessageContaining("joins a source").hasMessageContaining("withLock");
        assertThat(sql).isEmpty();
    }

    // ---- AC-SRC-47

    @TckTest
    void ac_src_47_a_vendor_without_the_cte_feature_is_mq4015_before_any_statement(TckDatabase db) {
        var config = ModelQueryConfig.defaults()
                .vendorProfiles(List.of(SourceQueryExecutionTest.withoutSources(db)));
        Throwable[] refusal = new Throwable[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, config, executor -> {
            refusal[0] = catchThrowable(() -> executor.list(bands().build(), Limit.of(10)));
            assertThat(catchThrowable(() -> executor.count(bands().build()))).isInstanceOf(refusal[0].getClass());
        }));

        assertThat(refusal[0]).isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015));
        assertThat(refusal[0]).hasMessageContaining("SourceFeature.CTE");
        assertThat(sql).isEmpty();
    }

    // ---- AC-SRC-43, a provider without the hook

    @TckTest
    void ac_src_43_a_provider_without_the_join_hook_is_mq4014_before_any_statement(TckDatabase db) {
        List<Throwable> refusals = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            // A renderer written before the join hook, and no renderer at all.
            LegacyRecursiveProviderSupport.serving(false, () -> withExecutor(ds, executor ->
                    refusals.add(catchThrowable(() -> executor.list(bands().build(), Limit.of(10))))));
            JoinTestSupport.withoutServices(() -> withExecutor(ds, executor ->
                    refusals.add(catchThrowable(() -> executor.list(bands().build(), Limit.of(10))))));
        });

        assertThat(refusals).hasSize(2).allSatisfy(refusal -> assertThat(refusal)
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014)));
        assertThat(sql).isEmpty();
    }

    // ---- helpers

    private static ModelQueryDefinitionException mq1910(Runnable build) {
        Throwable thrown = catchThrowable(build::run);
        assertThat(thrown).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1910));
        return (ModelQueryDefinitionException) thrown;
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        withExecutor(ds, ModelQueryConfig.defaults(), work);
    }

    private static void withExecutor(DataSource ds, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class, config)));
        }
    }

    /** As {@link #withExecutor(DataSource, Consumer)}, in a transaction, as a lock needs. */
    private static void withTransaction(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.fromTransaction(em -> {
                work.accept(ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()));
                return null;
            });
        }
    }

    static int occurrences(String sql, String part) {
        int count = 0;
        String lower = sql.toLowerCase(Locale.ROOT);
        for (int at = lower.indexOf(part); at >= 0; at = lower.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    private static int matches(String sql, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(sql.toLowerCase(Locale.ROOT));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** {@code sql}'s rows, each as its values joined with {@code |} and {@link #text}'s normalisation. */
    static List<String> rows(TckDatabase db, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                Object[] values = new Object[columns];
                for (int column = 1; column <= columns; column++) {
                    values[column - 1] = result.getObject(column);
                }
                rows.add(text(values));
            }
        }
        return rows;
    }

    /** Values as text joined with {@code |}: numbers without trailing zeros, so vendors and types compare alike. */
    static String text(Object... values) {
        StringBuilder row = new StringBuilder();
        for (int at = 0; at < values.length; at++) {
            Object value = values[at];
            row.append(at == 0 ? "" : "|").append(value instanceof Number number
                    ? new BigDecimal(number.toString()).stripTrailingZeros().toPlainString()
                    : String.valueOf(value));
        }
        return row.toString();
    }
}
