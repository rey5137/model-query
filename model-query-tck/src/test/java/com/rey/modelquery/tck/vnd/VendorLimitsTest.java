package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * The IN-list and bind-parameter limits of the resolved profile, through the executor: user lists are chunked
 * (api/12 R-FLT-09) and primary-key-first step 2 is batched within them (engine/21 R-PAG-07, vendor/41 §2).
 */
class VendorLimitsTest {

    record ItemRow(Long id, String product) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemRow.class, ITEMS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(ItemRow.class, ITEMS, "productCode", String.class);

    /** Every order item by id; its product code is shared by 400 rows. */
    private static final ModelQuery.Builder<OrderItemEntity, Long, ItemRow> BY_ID = ModelQuery
            .builder(ITEMS, row -> new ItemRow(row.get(ITEM_ID), row.get(ITEM_PRODUCT)))
            .columns(ColumnSet.of(ITEM_ID, ITEM_PRODUCT))
            .primaryKey(PrimaryKey.of(ITEM_ID))
            .orderBy(ITEM_ID.asc());

    /** Ordered by a key 400 rows share, so step 2 must re-apply the order and place rows by key (R-PAG-08). */
    private static final ModelQuery.Builder<OrderItemEntity, Long, ItemRow> BY_PRODUCT =
            BY_ID.orderBy(ITEM_PRODUCT.desc());

    private static final Comparator<ItemRow> PRODUCT_DESC_THEN_ID =
            Comparator.comparing(ItemRow::product).reversed().thenComparing(ItemRow::id);

    /** The {@code OTHER} profile: 1 000 values per IN list and 2 000 binds per statement (R-VND-06). */
    private static final ModelQueryConfig OTHER = ModelQueryConfig.defaults().vendor(DatabaseVendor.OTHER);
    private static final int OTHER_IN_LIST = 1_000;
    private static final int OTHER_BINDS = 2_000;

    private static final Pattern IN_LIST = Pattern.compile("\\bin \\(");

    // ---- AC-PRF-02, AC-FLT-08

    @TckTest
    void ac_prf_02_an_in_list_at_just_below_and_just_above_the_limit_returns_identical_rows(TckDatabase db) {
        int limit;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            limit = VendorResolver.resolve(sf, Optional.<DatabaseVendor>empty()).profile().maxInListSize();
        }
        assertThat(limit).isLessThan(TckFixture.ORDER_ITEMS);
        List<List<Long>> lists = List.of(ids(limit - 1), ids(limit), ids(limit + 1));
        List<List<ItemRow>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, ModelQueryConfig.defaults(), executor -> {
            for (List<Long> ids : lists) {
                results.add(executor.list(BY_ID.where(f -> f.in(ITEM_ID, ids)).build(), Limit.unlimited()));
            }
        }));
        assertThat(sql).hasSize(3);
        assertThat(sql).extracting(VendorLimitsTest::inLists).containsExactly(1L, 1L, 2L);
        for (int i = 0; i < lists.size(); i++) {
            assertThat(results.get(i)).extracting(ItemRow::id).isEqualTo(lists.get(i));
        }
    }

    @TckTest
    void ac_flt_08_an_in_list_the_other_profile_splits_returns_the_rows_of_the_unsplit_list(TckDatabase db) {
        // 1 500 ids, every third one: two chunks under OTHER, one list under the database's own profile.
        List<Long> ids = LongStream.iterate(3, id -> id + 3).limit(1_500).boxed().toList();
        var q = BY_ID.where(f -> f.in(ITEM_ID, ids)).build();
        List<List<ItemRow>> results = new ArrayList<>();
        List<String> split = SqlSnapshots.capture(db, ds -> withExecutor(ds, OTHER,
                executor -> results.add(executor.list(q, Limit.unlimited()))));
        List<String> whole = SqlSnapshots.capture(db, ds -> withExecutor(ds, ModelQueryConfig.defaults(),
                executor -> results.add(executor.list(q, Limit.unlimited()))));
        assertThat(split).extracting(VendorLimitsTest::inLists).containsExactly(2L);
        assertThat(whole).extracting(VendorLimitsTest::inLists).containsExactly(1L);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(results.get(0)).extracting(ItemRow::id).isEqualTo(ids);
    }

    @TckTest
    void ac_flt_08_a_customized_query_is_checked_without_the_portable_limits(TckDatabase db) {
        // Above OTHER's 2 000 binds, within every Tier-1 profile's: the phase check must not refuse it (MQ1306).
        List<Long> ids = ids(OTHER_BINDS + 1);
        QueryCustomizer none = (spec, joins, query, cb, phase) -> {};
        var q = BY_ID.where(f -> f.in(ITEM_ID, ids)).customize(none).build();
        withExecutor(db, ModelQueryConfig.defaults(), executor ->
                assertThat(executor.list(q, Limit.unlimited())).extracting(ItemRow::id).isEqualTo(ids));
    }

    // ---- AC-PRF-03

    @TckTest
    void ac_prf_03_a_step_two_batch_needing_more_binds_than_the_limit_is_split_and_stays_ordered(TckDatabase db) {
        // The query binds 1 500 values of its own, so the page's 1 000 keys fit one IN list but not the 2 000 binds:
        // step 2 reads them 500 at a time.
        var listed = BY_PRODUCT.where(f -> f.in(ITEM_ID, ids(1_500)));
        var twoStep = listed.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        var deep = new PageSpec(100, OTHER_IN_LIST - 1);
        List<Slice<ItemRow>> slices = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OTHER,
                executor -> slices.add(executor.page(twoStep, deep, CountMode.NO_COUNT))));
        assertThat(sql).as("the key statement and two step-2 statements").hasSize(3);
        assertThat(sql).allSatisfy(statement -> assertThat(placeholders(statement)).isLessThanOrEqualTo(OTHER_BINDS));
        Slice<ItemRow> slice = slices.get(0);
        withExecutor(db, OTHER, executor -> {
            Slice<ItemRow> expected = executor.page(listed.build(), deep, CountMode.NO_COUNT);
            assertThat(slice.content()).hasSize(OTHER_IN_LIST - 1).isEqualTo(expected.content());
            assertThat(slice.hasNext()).isEqualTo(expected.hasNext()).isTrue();
        });
        assertThat(slice.content()).isSortedAccordingTo(PRODUCT_DESC_THEN_ID);
    }

    @TckTest
    void ac_prf_03_one_in_list_needing_more_binds_than_the_limit_throws_mq1306(TckDatabase db) {
        List<Long> atLimit = ids(OTHER_BINDS);
        List<Long> aboveLimit = ids(OTHER_BINDS + 1);
        withExecutor(db, OTHER, executor -> {
            assertThat(executor.list(BY_ID.where(f -> f.in(ITEM_ID, atLimit)).build(), Limit.unlimited()))
                    .hasSize(OTHER_BINDS);
            assertThatThrownBy(() -> executor.list(BY_ID.where(f -> f.in(ITEM_ID, aboveLimit)).build(),
                    Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1306))
                    .hasMessage(MqCode.MQ1306.code() + ": ItemRow.id: in(...) received 2001 values, more than the "
                            + "2000 bind parameters one statement takes");
            assertThatThrownBy(() -> executor.list(BY_ID.where(f -> f.notIn(ITEM_ID, aboveLimit)).build(),
                    Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1306))
                    .hasMessageStartingWith(MqCode.MQ1306.code() + ": ItemRow.id: notIn(...)");
        });
    }

    // ---- AC-PAG-09

    @TckTest
    void ac_pag_09_a_configured_step_two_batch_size_splits_the_page_but_never_passes_the_profile_clamp(
            TckDatabase db) {
        var plain = BY_PRODUCT.build();
        var twoStep = BY_PRODUCT.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        // 2 500 keys, with the hasNext probe: 300 a statement is nine statements; 5 000 is clamped to OTHER's 1 000.
        var deep = new PageSpec(1_234, 2_499);
        List<Slice<ItemRow>> slices = new ArrayList<>();
        List<String> small = SqlSnapshots.capture(db, ds -> withExecutor(ds, OTHER.primaryKeyFirstBatchSize(300),
                executor -> slices.add(executor.page(twoStep, deep, CountMode.NO_COUNT))));
        List<String> large = SqlSnapshots.capture(db, ds -> withExecutor(ds, OTHER.primaryKeyFirstBatchSize(5_000),
                executor -> slices.add(executor.page(twoStep, deep, CountMode.NO_COUNT))));
        assertThat(small).as("the key statement and nine step-2 statements").hasSize(10);
        assertThat(large).as("the key statement and three step-2 statements").hasSize(4);
        assertThat(large.subList(1, large.size())).allSatisfy(statement -> assertThat(placeholders(statement))
                .isLessThanOrEqualTo(OTHER_IN_LIST));
        withExecutor(db, OTHER, executor -> {
            List<ItemRow> expected = executor.page(plain, deep, CountMode.NO_COUNT).content();
            assertThat(expected).hasSize(2_499).isSortedAccordingTo(PRODUCT_DESC_THEN_ID);
            assertThat(slices).allSatisfy(slice -> assertThat(slice.content()).isEqualTo(expected));
        });
    }

    /** The ids 1 to {@code count}, each an order item of the fixture. */
    private static List<Long> ids(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }

    private static long inLists(String statement) {
        return IN_LIST.matcher(statement.toLowerCase(Locale.ROOT)).results().count();
    }

    private static long placeholders(String statement) {
        return statement.chars().filter(c -> c == '?').count();
    }

    private static void withExecutor(TckDatabase db, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<OrderItemEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderItemEntity.class, config)));
        }
    }

    private static void withExecutor(DataSource ds, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<OrderItemEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderItemEntity.class, config)));
        }
    }
}
