package com.rey.modelquery.tck.col;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** The presence key of a {@code presentBy} join (spec api/10 R-COL-15, api/11 R-QRY-04, D-38). */
class PresenceKeyTest {

    record ReferrerView(Long id, String name) {}

    record OrderView(Long id, Long referrerId) {}

    /** What a mapper of nested models learns: whether each join matched, and the columns read through it. */
    record ItemView(Long id, boolean orderPresent, Long orderReferrerId, boolean referrerPresent,
            String referrerName) {}

    // The referrer, as a model of its own.
    private static final TableField<CustomerEntity, CustomerEntity> REFERRER_ROOT =
            TableField.root(CustomerEntity.class);
    private static final ColumnField<ReferrerView, CustomerEntity, Long> REFERRER_ID =
            ColumnField.of(ReferrerView.class, REFERRER_ROOT, "id", Long.class);
    private static final ColumnField<ReferrerView, CustomerEntity, String> REFERRER_NAME =
            ColumnField.of(ReferrerView.class, REFERRER_ROOT, "name", String.class);
    private static final PrimaryKey<ReferrerView, Long> REFERRER_KEY = PrimaryKey.of(REFERRER_ID);

    // The order, which nests its referrer: two thirds of the orders have none.
    private static final TableField<OrderEntity, OrderEntity> ORDER_ROOT = TableField.root(OrderEntity.class);
    private static final ColumnField<OrderView, OrderEntity, Long> ORDER_ID =
            ColumnField.of(OrderView.class, ORDER_ROOT, "id", Long.class);
    private static final ColumnField<OrderView, OrderEntity, Long> ORDER_REFERRER_ID =
            ColumnField.of(OrderView.class, ORDER_ROOT, "referrerId", Long.class);
    private static final PrimaryKey<OrderView, Long> ORDER_KEY = PrimaryKey.of(ORDER_ID);
    private static final TableField<OrderEntity, CustomerEntity> ORDER_REFERRER =
            TableField.<OrderEntity, CustomerEntity>join(ORDER_ROOT, "referrer", LEFT).presentBy(REFERRER_KEY);

    // The item, which nests its order two levels deep.
    private static final TableField<OrderItemEntity, OrderItemEntity> ROOT = TableField.root(OrderItemEntity.class);
    private static final TableField<OrderItemEntity, OrderEntity> ORDER =
            TableField.<OrderItemEntity, OrderEntity>join(ROOT, "order", LEFT).presentBy(ORDER_KEY);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER = ORDER_REFERRER.withParent(ORDER);

    private static final ColumnField<ItemView, OrderItemEntity, Long> ID =
            ColumnField.of(ItemView.class, ROOT, "id", Long.class);
    private static final ColumnField<ItemView, OrderEntity, Long> ITEM_ORDER_REFERRER_ID =
            ORDER_REFERRER_ID.withTable(ItemView.class, ORDER);
    private static final ColumnField<ItemView, CustomerEntity, String> ITEM_REFERRER_NAME =
            REFERRER_NAME.withTable(ItemView.class, REFERRER);
    private static final ColumnField<ItemView, CustomerEntity, Long> ITEM_REFERRER_ID =
            REFERRER_ID.withTable(ItemView.class, REFERRER);

    /** Reads each nested model through its scope, and takes a non-null key to mean the join matched. */
    private static final RowMapper<ItemView> MAPPER = row -> {
        Row order = row.scoped(ORDER);
        Row referrer = order.scoped(ORDER_REFERRER);
        return new ItemView(row.get(ID), order.get(ORDER_ID) != null, order.get(ORDER_REFERRER_ID),
                referrer.get(REFERRER_ID) != null, referrer.get(REFERRER_NAME));
    };

    private static final ModelQuery.Builder<OrderItemEntity, Long, ItemView> ITEMS = ModelQuery.builder(ROOT, MAPPER)
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc())
            .where(f -> f.lte(ID, 1_200L));

    @TckTest
    void ac_col_12_the_presence_key_of_a_present_by_join_is_selected_with_a_column_of_the_join(TckDatabase db) {
        // No key is named: each is selected because a column is read through its join, or through one below it.
        var orderOnly = ITEMS.columns(ColumnSet.of(ID, ITEM_ORDER_REFERRER_ID)).build();
        var referrerOnly = ITEMS.columns(ColumnSet.of(ID, ITEM_REFERRER_NAME)).build();
        // A key the ColumnSet already holds is selected once.
        var keyNamed = ITEMS.columns(ColumnSet.of(ID, ITEM_REFERRER_ID, ITEM_REFERRER_NAME)).build();
        var unjoined = ITEMS.columns(ColumnSet.of(ID)).build();
        List<ItemView> viaOrder = new ArrayList<>();
        List<ItemView> viaReferrer = new ArrayList<>();
        List<ItemView> viaNamedKey = new ArrayList<>();
        List<ItemView> viaNothing = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "col-12-presence-key", ds -> withExecutor(ds, executor -> {
            viaOrder.addAll(executor.list(orderOnly, Limit.unlimited()));
            viaReferrer.addAll(executor.list(referrerOnly, Limit.unlimited()));
            viaNamedKey.addAll(executor.list(keyNamed, Limit.unlimited()));
            viaNothing.addAll(executor.list(unjoined, Limit.unlimited()));
        }));

        // The order's key is read, the referrer's is not: no column of the referrer join was selected.
        assertThat(viaOrder).hasSize(1_200).allSatisfy(item -> {
            assertThat(item.orderPresent()).isTrue();
            assertThat(item.referrerPresent()).isFalse();
        });
        // A column two joins down brings the key of each join on the way.
        assertThat(viaReferrer).hasSize(1_200).allSatisfy(item -> assertThat(item.orderPresent()).isTrue());
        assertThat(viaReferrer).filteredOn(ItemView::referrerPresent).isNotEmpty()
                .allSatisfy(item -> assertThat(item.referrerName()).startsWith("Customer "));
        assertThat(viaNamedKey).isEqualTo(viaReferrer);
        assertThat(viaNothing).hasSize(1_200).allSatisfy(item -> {
            assertThat(item.orderPresent()).isFalse();
            assertThat(item.referrerPresent()).isFalse();
        });
    }

    @TckTest
    void ac_col_12_an_absent_row_is_told_from_a_match_whose_columns_are_all_null(TckDatabase db) {
        var q = ITEMS.columns(ColumnSet.of(ID, ITEM_ORDER_REFERRER_ID, ITEM_REFERRER_NAME)).build();
        List<ItemView> items = new ArrayList<>();
        withExecutor(JoinTestSupport.dataSource(db), executor -> items.addAll(executor.list(q, Limit.unlimited())));

        List<ItemView> unreferred = items.stream().filter(item -> item.orderReferrerId() == null).toList();
        assertThat(unreferred).isNotEmpty().hasSizeLessThan(items.size());
        // Both joins read NULL in every selected column, and only the keys tell them apart: the order matched, the
        // referrer did not.
        assertThat(unreferred).allSatisfy(item -> {
            assertThat(item.referrerName()).isNull();
            assertThat(item.orderPresent()).isTrue();
            assertThat(item.referrerPresent()).isFalse();
        });
        assertThat(items).filteredOn(item -> item.orderReferrerId() != null).allSatisfy(item -> {
            assertThat(item.orderPresent()).isTrue();
            assertThat(item.referrerPresent()).isTrue();
            assertThat(item.referrerName()).isNotNull();
        });
    }

    @TckTest
    void ac_col_12_every_model_phase_selects_the_presence_key(TckDatabase db) {
        var columns = ColumnSet.of(ID, ITEM_ORDER_REFERRER_ID, ITEM_REFERRER_NAME);
        var plain = ITEMS.columns(columns).build();
        var twoStep = ITEMS.columns(columns).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
        var keyset = ITEMS.columns(columns).keyset().build();
        List<ItemView> expected = new ArrayList<>();
        List<ItemView> paged = new ArrayList<>();
        List<ItemView> exported = new ArrayList<>();
        List<ItemView> streamed = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inTransaction(em -> {
                var executor = ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults());
                expected.addAll(executor.list(plain, Limit.unlimited()));
                for (int page = 0; page < 3; page++) {
                    paged.addAll(executor.page(twoStep, PageSpec.of(page, 400), CountMode.NO_COUNT).content());
                }
                executor.export(keyset, ExportOptions.of(500), page -> page, exported::add);
                streamed.addAll(executor.stream(plain, Limit.unlimited(), rows -> rows.toList()));
            });
        }

        assertThat(expected).hasSize(1_200).allSatisfy(item -> {
            assertThat(item.orderPresent()).isTrue();
            assertThat(item.referrerPresent()).isEqualTo(item.orderReferrerId() != null);
        });
        assertThat(expected).extracting(ItemView::referrerPresent).contains(true, false);
        assertThat(paged).isEqualTo(expected);
        assertThat(exported).isEqualTo(expected);
        assertThat(streamed).isEqualTo(expected);
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderItemEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults())));
        }
    }
}
