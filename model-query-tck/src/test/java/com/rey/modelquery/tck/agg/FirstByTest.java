package com.rey.modelquery.tck.agg;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateFeature;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.FirstByRowEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.flt.OperatorProviderSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.VendorAggregates;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * {@code Agg.firstBy} on every target: the same value on PostgreSQL and H2 under ties and NULL keys, a converted value
 * read through its converter, an empty group, a filtered one whose first row fails the filter, and the refusals before
 * any statement: a vendor without the feature ({@code MQ4015}), a provider without the renderer ({@code MQ4014}) and a
 * value read through a to-many join ({@code MQ1412}) (spec api/13 R-AGG-16, AC-AGG-18, AC-AGG-19). The rows are
 * {@code first_by_rows}: group 1 holds a tie on its greatest key and a NULL key, group 2 only NULL keys, group 3 a
 * NULL value.
 */
class FirstByTest {

    /** A group and the value its first row holds. */
    record Pick(Integer group, Object value) {}

    private static final TableField<FirstByRowEntity, FirstByRowEntity> ROWS = TableField.root(FirstByRowEntity.class);
    private static final ColumnField<Pick, FirstByRowEntity, Integer> GRP =
            ColumnField.of(Pick.class, ROWS, "grp", Integer.class);
    private static final ColumnField<Pick, FirstByRowEntity, Integer> KEY =
            ColumnField.of(Pick.class, ROWS, "key", Integer.class);
    private static final ColumnField<Pick, FirstByRowEntity, String> VALUE =
            ColumnField.of(Pick.class, ROWS, "value", String.class);
    private static final ColumnField<Pick, FirstByRowEntity, ConstantStatus> STATE =
            ColumnField.of(Pick.class, ROWS, "state", ConstantStatus.class);

    /** The letter a row stores, read as a {@code Character}: not the attribute's type, so a skipped converter fails. */
    private static final ColumnField<Pick, FirstByRowEntity, Character> LETTER = ColumnField.of(Pick.class, ROWS,
            "value", Character.class, String.class, new ColumnConverter<Character, String>() {
                @Override
                public Character toModel(String attribute) {
                    return attribute.charAt(0);
                }

                @Override
                public String toAttribute(Character model) {
                    return String.valueOf(model);
                }
            });

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);
    private static final ColumnField<Pick, OrderEntity, Integer> ORDER_GROUP =
            ColumnField.of(Pick.class, ORDERS, "version", Integer.class);
    private static final ColumnField<Pick, OrderItemEntity, String> ITEM_CODE =
            ColumnField.of(Pick.class, ITEMS, "productCode", String.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER =
            TableField.join(ORDERS, "customer", INNER);
    private static final ColumnField<Pick, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(Pick.class, CUSTOMER, "name", String.class);
    private static final ColumnField<Pick, OrderEntity, Long> ORDER_ID =
            ColumnField.of(Pick.class, ORDERS, "id", Long.class);

    // ---- AC-AGG-18

    @TckTest
    void ac_agg_18_the_first_row_is_the_same_under_ties_and_null_keys_on_every_vendor(TckDatabase db) {
        assumeFirstBy(db);
        Map<String, Map<Integer, Object>> picks = new LinkedHashMap<>();
        SqlSnapshots.assertMatches(db, "agg-18-first-by", ds -> executor(ds, executor -> {
            picks.put("desc nulls last", picks(executor, Agg.firstBy(VALUE, KEY.desc().nullsLast())));
            picks.put("desc nulls first", picks(executor, Agg.firstBy(VALUE, KEY.desc().nullsFirst())));
            picks.put("asc nulls last", picks(executor, Agg.firstBy(VALUE, KEY.asc().nullsLast())));
            picks.put("asc nulls first", picks(executor, Agg.firstBy(VALUE, KEY.asc().nullsFirst())));
        }));

        // Group 1's greatest key ties on rows b and c: the root's id breaks it, ascending. Group 2's keys are all
        // NULL, so its first row is its lowest id whatever the precedence; group 3's value is NULL.
        assertThat(picks.get("desc nulls last")).containsExactly(Map.entry(1, "b"), Map.entry(2, "e"),
                entryOfNull(3));
        assertThat(picks.get("desc nulls first")).containsExactly(Map.entry(1, "d"), Map.entry(2, "e"),
                entryOfNull(3));
        assertThat(picks.get("asc nulls last")).containsExactly(Map.entry(1, "a"), Map.entry(2, "e"),
                entryOfNull(3));
        assertThat(picks.get("asc nulls first")).containsExactly(Map.entry(1, "d"), Map.entry(2, "e"),
                entryOfNull(3));
    }

    @TckTest
    void ac_agg_18_a_converted_value_is_read_through_its_converter(TckDatabase db) {
        assumeFirstBy(db);
        // The state is stored as a letter: a read that skipped the converter would fail on it, or return the letter.
        Map<Integer, Object> picks = onRows(db, executor -> picks(executor, Agg.firstBy(STATE,
                KEY.desc().nullsLast())));

        assertThat(picks).containsExactly(Map.entry(1, ConstantStatus.CLOSED), Map.entry(2, ConstantStatus.CLOSED),
                entryOfNull(3));
    }

    @TckTest
    void ac_agg_18_a_model_query_converter_maps_the_value_read_and_the_having_operand(TckDatabase db) {
        assumeFirstBy(db);
        var first = Agg.firstBy(LETTER, KEY.asc().nullsLast());

        Map<Integer, Object> picks = onRows(db, executor -> picks(executor, first));
        var having = ModelQuery.builder(ROWS, row -> new Pick(row.get(GRP), row.get(first)))
                .select(SelectSet.of(GRP, first)).groupBy(GRP).having(h -> h.eq(first, 'a')).build();
        List<Pick> kept = onRows(db, executor -> executor.list(having, Limit.unlimited()));

        assertThat(picks).containsExactly(Map.entry(1, 'a'), Map.entry(2, 'e'), entryOfNull(3));
        assertThat(kept).containsExactly(new Pick(1, 'a'));
    }

    @TckTest
    void ac_agg_18_a_group_with_no_row_is_null(TckDatabase db) {
        assumeFirstBy(db);
        var first = Agg.firstBy(VALUE, KEY.desc());
        var query = ModelQuery.builder(ROWS, row -> new Pick(null, row.get(first))).select(SelectSet.of(first))
                .where(f -> f.eq(GRP, 99)).build();

        List<Pick> rows = onRows(db, executor -> executor.list(query, Limit.unlimited()));

        assertThat(rows).containsExactly(new Pick(null, null));
    }

    @TckTest
    void ac_agg_18_a_filtered_first_by_skips_a_first_row_that_fails_the_filter(TckDatabase db) {
        assumeFirstBy(db);
        var unfiltered = Agg.firstBy(VALUE, KEY.desc().nullsLast());
        var filtered = unfiltered.filter(f -> f.ne(VALUE, "b"));
        Map<Integer, Object> picks = new LinkedHashMap<>();

        SqlSnapshots.assertMatches(db, "agg-18-first-by-filtered", ds -> executor(ds, executor ->
                picks.putAll(picks(executor, filtered))));

        // The CASE form would yield NULL for group 1, whose first row b fails the filter; FILTER skips to c.
        assertThat(picks).containsExactly(Map.entry(1, "c"), Map.entry(2, "e"), entryOfNull(3));
        Map<Integer, Object> all = onRows(db, executor -> picks(executor, unfiltered));
        assertThat(all).containsEntry(1, "b");
    }

    @TckTest
    void ac_agg_18_a_value_read_through_a_to_many_join_is_mq1412(TckDatabase db) {
        assumeFirstBy(db);
        var first = Agg.firstBy(ITEM_CODE, ORDER_ID.asc());
        var query = ModelQuery.builder(ORDERS, row -> new Pick(row.get(ORDER_GROUP), row.get(first)))
                .select(SelectSet.of(ORDER_GROUP, first)).groupBy(ORDER_GROUP).build();

        onOrders(db, executor -> assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1412))
                .hasMessageContaining("items"));
    }

    /** Runs {@code first} after a {@code count} over the to-many {@code items}: the cached join must still be refused. */
    private static void assertToManyRefused(TckDatabase db, AggregateField<Pick, ?> first) {
        AggregateField<Pick, Long> counted = Agg.count(ITEM_CODE);
        var query = ModelQuery.builder(ORDERS, row -> new Pick(row.get(ORDER_GROUP), row.get(first)))
                .select(SelectSet.of(ORDER_GROUP, counted, first)).groupBy(ORDER_GROUP).build();

        onOrders(db, executor -> assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1412))
                .hasMessageContaining("items"));
    }

    @TckTest
    void ac_agg_30_an_expression_over_a_to_many_join_already_joined_by_a_count_is_mq1412(TckDatabase db) {
        assumeFirstBy(db);

        assertToManyRefused(db, Agg.firstBy(Expr.concat(ITEM_CODE, "x"), ORDER_ID.asc()));
    }

    @TckTest
    void ac_agg_30_a_value_read_through_a_to_many_join_already_joined_by_a_count_is_mq1412(TckDatabase db) {
        assumeFirstBy(db);

        assertToManyRefused(db, Agg.firstBy(ITEM_CODE, ORDER_ID.asc()));
    }

    @TckTest
    void ac_agg_30_an_order_key_read_through_a_to_many_join_already_joined_by_a_count_is_mq1412(TckDatabase db) {
        assumeFirstBy(db);

        assertToManyRefused(db, Agg.firstBy(ORDER_ID, ITEM_CODE.asc()));
    }

    @TckTest
    void ac_agg_30_a_to_one_value_after_a_count_over_a_to_many_join_is_accepted(TckDatabase db) {
        assumeFirstBy(db);
        AggregateField<Pick, Long> counted = Agg.count(ITEM_CODE);
        var first = Agg.firstBy(CUSTOMER_NAME, ORDER_ID.asc());
        var query = ModelQuery.builder(ORDERS, row -> new Pick(row.get(ORDER_GROUP), row.get(first)))
                .select(SelectSet.of(ORDER_GROUP, counted, first)).groupBy(ORDER_GROUP).build();

        onOrders(db, executor -> assertThat(executor.list(query, Limit.unlimited())).isNotNull());
    }

    // ---- AC-AGG-19

    @TckTest
    void ac_agg_19_a_vendor_without_the_feature_is_mq4015_before_any_statement(TckDatabase db) {
        var first = Agg.firstBy(VALUE, KEY.desc());
        var query = ModelQuery.builder(ROWS, row -> new Pick(null, row.get(first))).select(SelectSet.of(first)).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> run(ds, false, executor ->
                assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015))
                        .hasMessageContaining("FIRST_BY").hasMessageContaining("firstBy(value)")
                        .hasMessageContaining("VendorProfile#aggregates")));

        assertThat(sql).noneMatch(s -> s.contains("first_by_rows"));
    }

    @TckTest
    void ac_agg_19_the_target_vendors_own_answer_refuses_first_by_where_it_has_no_array_aggregate(TckDatabase db) {
        Assumptions.assumeFalse(VendorAggregates.servesFirstBy(db), "PostgreSQL and H2 render it; MySQL has no form");
        var first = Agg.firstBy(VALUE, KEY.desc());
        var query = ModelQuery.builder(ROWS, row -> new Pick(null, row.get(first))).select(SelectSet.of(first)).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> executor(ds, executor ->
                assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015))
                        .hasMessageContaining("FIRST_BY")));

        assertThat(sql).noneMatch(s -> s.contains("first_by_rows"));
    }

    @TckTest
    void ac_agg_19_a_provider_without_the_renderer_is_mq4014_for_a_plain_and_a_filtered_first_by(TckDatabase db) {
        var plain = Agg.firstBy(VALUE, KEY.desc());
        var filtered = plain.filter(f -> f.ne(VALUE, "b")).as("filtered");

        List<String> sql = SqlSnapshots.capture(db, ds -> {
            // The boot's provider support is a Hibernate one without the aggregate renderer, found only in serving.
            OperatorProviderSupport.serving(feature -> true, null, () -> run(ds, true, executor -> {
                for (AggregateField<Pick, ?> field : List.of(plain, filtered)) {
                    var query = ModelQuery.builder(ROWS, row -> new Pick(null, row.get(field)))
                            .select(SelectSet.of(field)).build();
                    assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
                }
            }));
        });

        assertThat(sql).noneMatch(s -> s.contains("first_by_rows"));
    }

    // ---- support

    private static void assumeFirstBy(TckDatabase db) {
        Assumptions.assumeTrue(VendorAggregates.servesFirstBy(db), "MySQL has no array aggregate: MQ4015 (AC-AGG-19)");
    }

    private static Map.Entry<Integer, Object> entryOfNull(int group) {
        return new java.util.AbstractMap.SimpleEntry<>(group, null);
    }

    /** The field's value per group, in group order. */
    private static Map<Integer, Object> picks(ModelQueryExecutor<FirstByRowEntity> executor,
            AggregateField<Pick, ?> field) {
        var query = ModelQuery.builder(ROWS, row -> new Pick(row.get(GRP), row.get(field)))
                .select(SelectSet.of(GRP, field)).groupBy(GRP).orderBy(GRP.asc()).build();
        Map<Integer, Object> picks = new LinkedHashMap<>();
        executor.list(query, Limit.unlimited()).forEach(pick -> picks.put(pick.group(), pick.value()));
        return picks;
    }

    private static <R> R onRows(TckDatabase db, Function<ModelQueryExecutor<FirstByRowEntity>, R> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            return sf.fromSession(em -> work.apply(
                    ModelQueryExecutor.create(em, FirstByRowEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static void onOrders(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static void executor(DataSource ds, Consumer<ModelQueryExecutor<FirstByRowEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, FirstByRowEntity.class, ModelQueryConfig.defaults())));
        }
    }

    /** As {@link #executor}, on a profile that answers {@code aggregates(FIRST_BY)} as {@code vendorServes} says. */
    private static void run(DataSource ds, boolean vendorServes, Consumer<ModelQueryExecutor<FirstByRowEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            ModelQueryConfig config = ModelQueryConfig.defaults().vendorProfiles(List.of(profile(sf, vendorServes)));
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, FirstByRowEntity.class, config)));
        }
    }

    /** The factory's built-in profile, with its {@code aggregates} answer replaced (a custom profile). */
    private static VendorProfile profile(SessionFactory sf, boolean firstBy) {
        VendorProfile builtIn = VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile();
        return new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return builtIn.vendor();
            }

            @Override
            public int maxInListSize() {
                return builtIn.maxInListSize();
            }

            @Override
            public int maxBindParameters() {
                return builtIn.maxBindParameters();
            }

            @Override
            public void applyTimeout(Query query, Duration timeout) {
                builtIn.applyTimeout(query, timeout);
            }

            @Override
            public NullOrdering defaultAscendingNullOrdering() {
                return builtIn.defaultAscendingNullOrdering();
            }

            @Override
            public boolean aggregates(AggregateFeature feature) {
                return firstBy;
            }
        };
    }
}
