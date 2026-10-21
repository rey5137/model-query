package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.SourceFeature;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.fch.FetchTestSupport;
import com.rey.modelquery.tck.harness.InlineValueHandling;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.time.Duration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * A source built into a CTE at run time and read by an outer model (spec api/17 R-SRC-01, R-SRC-02, R-SRC-03,
 * R-SRC-10, R-SRC-12). Each case reads the rows the equivalent hand-written SQL reads, and pins the SQL per vendor.
 */
class SourceQueryExecutionTest {

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(ORDERS, "items", JoinType.INNER);

    /**
     * A hand-written column over the to-many join: a generated source field cannot cross one (that is the processor's
     * {@code MQ3031}), so this is the shape in which a source's {@code where} crosses a collection (R-SRC-03).
     */
    private static final ColumnField<OrderSource, OrderItemEntity, String> ITEM_CODE =
            ColumnField.of(OrderSource.class, ORDER_ITEMS, "productCode", String.class);

    // ---- AC-SRC-01

    @TckTest
    void ac_src_01_a_plain_source_reads_the_rows_its_hand_written_sql_reads(TckDatabase db) throws SQLException {
        var feed = QOrderFeed.query(QOrderSource.source().build()).select(QOrderFeed.ALL)
                .orderBy(QOrderFeed.ID.asc()).build();
        List<OrderFeed> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-01-plain-cte", ds -> withExecutor(ds, OrderSource.class,
                executor -> rows.addAll(executor.list(feed, Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(rows).hasSize(5_000);
        assertThat(orders(rows)).isEqualTo(rows(db, "select id, status, total from orders order by id"));
    }

    @TckTest
    void ac_src_01_under_inline_value_handling_a_source_where_value_renders_as_before(TckDatabase db) {
        var feed = QOrderFeed.query(QOrderSource.source().where(f -> f.eq(QOrderSource.STATUS, "PAID")).build())
                .select(QOrderFeed.ALL).where(f -> f.gt(QOrderFeed.TOTAL, new BigDecimal("100.00")))
                .orderBy(QOrderFeed.ID.asc()).build();
        List<OrderFeed> expected = new ArrayList<>();
        withExecutor(db, OrderSource.class, executor -> expected.addAll(executor.list(feed, Limit.unlimited())));
        assertThat(expected).isNotEmpty();

        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            // The outer statement's value binds; the CTE body's parameters would sit in a context no statement binds,
            // so its value is left to the factory, which inlines it as before R-FLT-08 (M30.6 binds it)
            inline.watch(100);
            List<OrderFeed> rows = new ArrayList<>();
            inline.factory().inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderSource.class,
                    ModelQueryConfig.defaults()).list(feed, Limit.unlimited())));
            assertThat(rows).isEqualTo(expected);
            assertThat(inline.statements()).singleElement().asString().contains("'paid'").contains("?");
        }
    }

    // ---- AC-SRC-02

    @TckTest
    void ac_src_02_a_grouped_source_builds_the_cte_its_hand_written_sql_builds(TckDatabase db) throws SQLException {
        SourceQuery<QuantitySource> source = QQuantitySource.source()
                .where(f -> f.gte(QQuantitySource.QUANTITY, 5))
                .having(h -> h.gt(QQuantitySource.LINE_COUNT, 100L))
                .build();
        var feed = QQuantityFeed.query(source).select(QQuantityFeed.ALL).orderBy(QQuantityFeed.QUANTITY.asc())
                .build();
        List<QuantityFeed> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "src-02-grouped-cte", ds -> withExecutor(ds,
                QuantitySource.class, executor -> rows.addAll(executor.list(feed, Limit.unlimited()))));

        assertThat(sql).isNotEmpty();
        assertThat(rows).hasSize(5);
        assertThat(groups(rows)).isEqualTo(rows(db, "select quantity, count(*), sum(unit_price) from order_items "
                + "where quantity >= 5 group by quantity having count(*) > 100 order by quantity"));
    }

    // ---- AC-SRC-03

    @TckTest
    void ac_src_03_a_keyset_export_over_a_grouped_source_visits_every_group_exactly_once(TckDatabase db) {
        var feed = QQuantityFeed.query(QQuantitySource.source().build()).select(QQuantityFeed.ALL)
                .orderBy(QQuantityFeed.QUANTITY.asc()).keyset().build();
        List<QuantityFeed> rows = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, QuantitySource.class,
                executor -> assertThat(executor.export(feed, ExportOptions.of(4), page -> {
                    pageSizes.add(page.size());
                    return page;
                }, rows::add)).isEqualTo(9)));

        assertThat(pageSizes).hasSize(3);
        assertThat(rows).extracting(QuantityFeed::quantity).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        // Every page's statement registers the source again, so each reads the CTE's body over order_items
        // (R-SRC-12).
        assertThat(sql).hasSize(3).allSatisfy(statement ->
                assertThat(statement).containsIgnoringCase("order_items"));
    }

    // ---- AC-SRC-04

    @TckTest
    void ac_src_04_a_source_where_crossing_a_to_many_join_is_mq1906(TckDatabase db) {
        var crossing = QOrderFeed.query(QOrderSource.source().where(f -> f.eq(ITEM_CODE, "P007")).build())
                .select(QOrderFeed.ALL).orderBy(QOrderFeed.ID.asc()).build();

        assertThatThrownBy(() -> withExecutor(db, OrderSource.class, executor -> executor.count(crossing)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining(MqCode.MQ1906.code())
                .hasMessageContaining("Filters.exists");
    }

    @TckTest
    void ac_src_04_the_exists_form_reads_the_rows_the_hand_written_sql_reads(TckDatabase db) throws SQLException {
        SourceQuery<OrderSource> source = QOrderSource.source()
                .where(f -> f.exists(ORDER_ITEMS, item -> item.eq(ITEM_CODE, "P007"))).build();
        var feed = QOrderFeed.query(source).select(QOrderFeed.ALL).orderBy(QOrderFeed.ID.asc()).build();
        List<OrderFeed> rows = new ArrayList<>();
        withExecutor(db, OrderSource.class, executor -> rows.addAll(executor.list(feed, Limit.unlimited())));

        assertThat(rows).isNotEmpty();
        assertThat(orders(rows)).isEqualTo(rows(db, "select id, status, total from orders where id in "
                + "(select order_id from order_items where product_code = 'P007') order by id"));
    }

    // ---- AC-SRC-15

    @TckTest
    void ac_src_15_content_count_and_both_primary_key_first_steps_register_the_source(TckDatabase db) {
        var feedBuilder = QOrderFeed.query(QOrderSource.source().build()).select(QOrderFeed.ALL)
                .orderBy(QOrderFeed.ID.asc());
        var feed = feedBuilder.build();
        var twoStep = feedBuilder.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        List<OrderFeed> content = new ArrayList<>();
        long[] count = new long[1];
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderSource.class, executor -> {
            content.addAll(executor.list(feed, Limit.of(10)));
            count[0] = executor.count(feed);
            // An offset past the threshold runs both steps of a primary-key-first page.
            executor.page(twoStep, PageSpec.of(5, 10), CountMode.NO_COUNT);
        }));

        // The content statement, the count, and the primary-key-first query's two steps each register the source.
        assertThat(sql).hasSize(4).allSatisfy(statement ->
                assertThat(statement).containsIgnoringCase("orders"));
        assertThat(content).hasSize(10);
        assertThat(count[0]).isEqualTo(5_000L);
    }

    // ---- R-SRC-10: a JPA-converted column read through a source is converted exactly once

    @TckTest
    void r_src_10_a_jpa_converted_column_read_through_a_source_converts_once(TckDatabase db) throws SQLException {
        var feed = QCodeFeed.query(QCodeSource.source().build()).select(QCodeFeed.ALL).orderBy(QCodeFeed.ID.asc())
                .build();
        List<CodeFeed> rows = new ArrayList<>();
        withExecutor(db, CodeSource.class, executor -> rows.addAll(executor.list(feed, Limit.unlimited())));

        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().map(row -> normalised(row.id()) + "|" + row.status().name()).toList())
                .isEqualTo(rows(db, "select id, status from orders order by id"));
    }

    // ---- R-SRC-11: a row lock over a source

    @TckTest
    void r_src_11_a_row_lock_over_a_source_is_mq1903(TckDatabase db) {
        var locked = QOrderFeed.query(QOrderSource.source().build()).select(QOrderFeed.ALL)
                .orderBy(QOrderFeed.ID.asc()).build().withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));

        assertThatThrownBy(() -> withExecutor(db, OrderSource.class, executor -> executor.list(locked, Limit.of(10))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1903))
                .hasMessageContaining("withLock");
    }

    // ---- R-SRC-12: byKeys registers the source on every chunk

    @TckTest
    void r_src_12_by_keys_registers_the_source_on_every_chunk(TckDatabase db) {
        var feed = QQuantityFeed.query(QQuantitySource.source().build()).select(QQuantityFeed.ALL)
                .orderBy(QQuantityFeed.QUANTITY.asc()).build();
        List<Integer> keys = List.of(1, 2, 3, 4, 5);
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(FetchTestSupport.limited(db, 2)));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, QuantitySource.class, config,
                executor -> assertThat(executor.byKeys(feed, keys).keySet()).containsExactlyElementsOf(keys)));

        // Five keys in IN lists of at most two values are three statements, each registering the source again.
        assertThat(sql).hasSize(3).allSatisfy(statement ->
                assertThat(statement).containsIgnoringCase("order_items"));
    }

    // ---- R-SRC-12: the parent read of a fetch plan registers the source and loads its children

    @TckTest
    void r_src_12_a_fetch_plan_over_a_source_loads_its_children(TckDatabase db) throws SQLException {
        var plan = FetchPlan.of(QOrderItemsFeed.ALL).child(QOrderItemsFeed.ITEMS, FetchPlan.of(QOrderItemLine.ALL));
        var feed = QOrderItemsFeed.query(QOrderSource.source().build())
                .orderBy(QOrderItemsFeed.ID.asc()).fetch(plan).build();
        List<OrderItemsFeed> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderSource.class,
                executor -> rows.addAll(executor.list(feed, Limit.of(20)))));

        // The parent read is a statement over the source, so it registers the CTE, whose body reads orders (R-SRC-12;
        // a vendor may inline it, as H2 does). The child load is a second statement, over the child's entity.
        assertThat(sql).hasSize(2);
        assertThat(sql.get(0)).containsIgnoringCase("orders");

        assertThat(rows).hasSize(20);
        assertThat(orderValues(rows)).isEqualTo(
                rows(db, "select id, status, total from orders where id <= 20 order by id"));
        assertThat(rows).allSatisfy(row -> assertThat(row.items()).hasSize(4));
        assertThat(childValues(rows)).isEqualTo(rows(db, "select i.order_id, i.product_code from order_items i "
                + "where i.order_id <= 20 order by i.order_id, i.id"));
    }

    // ---- R-SRC-12: a QueryCustomizer's first-run phase check builds over the source too

    @TckTest
    void r_src_12_a_customizer_on_a_source_query_passes_the_first_run_phase_check(TckDatabase db) {
        var feed = QOrderFeed.query(QOrderSource.source().build()).select(QOrderFeed.ALL)
                .orderBy(QOrderFeed.ID.asc())
                .customize((spec, joins, query, cb, phase) -> { })
                .build();
        List<OrderFeed> rows = new ArrayList<>();
        withExecutor(db, OrderSource.class, executor -> rows.addAll(executor.list(feed, Limit.of(10))));

        assertThat(rows).hasSize(10);
        assertThat(rows.get(0).id()).isNotNull();
    }

    // ---- AC-SRC-16

    @TckTest
    void ac_src_16_a_source_without_provider_support_is_mq4014_before_any_statement(TckDatabase db) {
        var feed = QOrderFeed.query(QOrderSource.source().build()).select(QOrderFeed.ALL)
                .orderBy(QOrderFeed.ID.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> JoinTestSupport.withoutServices(() ->
                assertThatThrownBy(() -> withExecutor(ds, OrderSource.class,
                        executor -> executor.list(feed, Limit.of(10))))
                        .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                        .hasMessageContaining("SourceRenderer")));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_16_a_vendor_without_the_cte_feature_is_mq4015_before_any_statement(TckDatabase db) {
        var feed = QOrderFeed.query(QOrderSource.source().build()).select(QOrderFeed.ALL)
                .orderBy(QOrderFeed.ID.asc()).build();
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(withoutSources(db)));
        List<String> sql = SqlSnapshots.capture(db, ds -> assertThatThrownBy(() -> withExecutor(ds, OrderSource.class,
                config, executor -> executor.list(feed, Limit.of(10))))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015))
                .hasMessageContaining("SourceFeature.CTE"));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_16_a_vendor_without_the_window_feature_is_mq4015_before_any_statement(TckDatabase db) {
        var feed = QWindowFeed.query(QWindowSource.source().build()).select(QWindowFeed.ALL)
                .orderBy(QWindowFeed.ID.asc()).build();
        var config = ModelQueryConfig.defaults()
                .vendorProfiles(List.of(withoutSourceFeature(db, SourceFeature.WINDOW)));
        List<String> sql = SqlSnapshots.capture(db, ds -> assertThatThrownBy(() -> withExecutor(ds, WindowSource.class,
                config, executor -> executor.list(feed, Limit.of(10))))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015))
                .hasMessageContaining("SourceFeature.WINDOW"));

        assertThat(sql).isEmpty();
    }

    /**
     * {@code db}'s profile with every source feature refused, as one written before the constants were added: it
     * answers {@code sources(...)} with {@code false} and delegates the interface's abstract methods, every optional
     * hook being the interface's default (R-VND-21, R-SRC-11).
     */
    private static VendorProfile withoutSources(TckDatabase db) {
        return sources(db, feature -> false);
    }

    /**
     * {@code db}'s profile refusing {@code refused} alone, as one written when only the other constants existed: the
     * registration of the CTE succeeds and the feature the profile does not know fails safe (R-VND-21, R-SRC-11).
     */
    private static VendorProfile withoutSourceFeature(TckDatabase db, SourceFeature refused) {
        return sources(db, feature -> feature != refused);
    }

    private static VendorProfile sources(TckDatabase db, Predicate<SourceFeature> answer) {
        VendorProfile delegate = FetchTestSupport.profile(db);
        return new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return delegate.vendor();
            }

            @Override
            public int maxInListSize() {
                return delegate.maxInListSize();
            }

            @Override
            public int maxBindParameters() {
                return delegate.maxBindParameters();
            }

            @Override
            public void applyTimeout(Query query, Duration timeout) {
                delegate.applyTimeout(query, timeout);
            }

            @Override
            public NullOrdering defaultAscendingNullOrdering() {
                return delegate.defaultAscendingNullOrdering();
            }

            @Override
            public boolean sources(SourceFeature feature) {
                return answer.test(feature);
            }
        };
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

    /** The orders an outer model read, as {@code id|status|total} texts, comparable with a hand-written SQL's. */
    private static List<String> orders(List<OrderFeed> rows) {
        return rows.stream().map(row -> normalised(row.id()) + "|" + normalised(row.status()) + "|"
                + normalised(row.total())).toList();
    }

    /** The orders a source-rooted parent read, as {@code id|status|total} texts, matching a hand-written SQL's. */
    private static List<String> orderValues(List<OrderItemsFeed> rows) {
        return rows.stream().map(row -> normalised(row.id()) + "|" + normalised(row.status()) + "|"
                + normalised(row.total())).toList();
    }

    /**
     * Every loaded child, as {@code orderId|productCode} texts in parent order and, within a parent, in child order,
     * comparable with a hand-written SQL's.
     */
    private static List<String> childValues(List<OrderItemsFeed> rows) {
        List<String> values = new ArrayList<>();
        for (OrderItemsFeed row : rows) {
            for (OrderItemLine item : row.items()) {
                values.add(normalised(row.id()) + "|" + normalised(item.productCode()));
            }
        }
        return values;
    }

    /** The groups an outer model read, as {@code quantity|lineCount|revenue} texts. */
    private static List<String> groups(List<QuantityFeed> rows) {
        return rows.stream().map(row -> normalised(row.quantity()) + "|" + normalised(row.lineCount()) + "|"
                + normalised(row.revenue())).toList();
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
