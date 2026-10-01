package com.rey.modelquery.tck.wrt;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.PersistenceContextMode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.CompositeKeyItemEntity;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.Cache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Bulk updates and deletes rendered as one statement, or one per run of keys (spec api/14 R-WRT-07, R-WRT-08,
 * R-WRT-10, R-WRT-11, R-WRT-13, R-WRT-14, R-WRT-15, R-WRT-16, R-WRT-18, D-61, D-62, D-63). Every write runs in a
 * transaction that is rolled back, so the shared fixture stays as seeded. A write that reads its target table in a
 * sub-query runs as one statement where the profile allows it and key-first elsewhere, MySQL among the Tier-1
 * databases (R-WRT-11).
 */
class BulkWriteTest {

    /** The update model; also read back as a one-column query model. */
    record OrderPatch(Long id) {}

    record CustomerPatch(Long id) {}

    record ItemPatch(Long id) {}

    record TenantItemPatch(Integer tenantId, Integer itemNo) {}

    /** {@code #42} in the model, {@code 42} in the entity. */
    static final class RefConverter implements ColumnConverter<String, Long> {
        static final RefConverter INSTANCE = new RefConverter();

        @Override
        public String toModel(Long attribute) {
            return "#" + attribute;
        }

        @Override
        public Long toAttribute(String model) {
            return Long.valueOf(model.substring(1));
        }
    }

    static final class StatusConverter implements ColumnConverter<OrderStatus, String> {
        static final StatusConverter INSTANCE = new StatusConverter();

        @Override
        public OrderStatus toModel(String attribute) {
            return OrderStatus.valueOf(attribute);
        }

        @Override
        public String toAttribute(OrderStatus model) {
            return model.name();
        }
    }

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER = TableField.join(ORDERS, "referrer", LEFT);

    private static final ColumnField<OrderPatch, OrderEntity, Long> ID =
            ColumnField.of(OrderPatch.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderPatch, OrderEntity, String> STATUS =
            ColumnField.of(OrderPatch.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderPatch, OrderEntity, OrderStatus> STATUS_CODE = ColumnField.of(
            OrderPatch.class, ORDERS, "status", OrderStatus.class, String.class, StatusConverter.INSTANCE);
    private static final ColumnField<OrderPatch, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderPatch.class, ORDERS, "total", BigDecimal.class);
    /** The to-one {@code referrer}, written by id (R-WRT-14). */
    private static final ColumnField<OrderPatch, OrderEntity, Long> REFERRER_ID =
            ColumnField.of(OrderPatch.class, ORDERS, "referrer", Long.class);
    /** The to-one {@code customer}, written by an id that a converter turns from {@code #42} into 42. */
    private static final ColumnField<OrderPatch, OrderEntity, String> CUSTOMER_REF =
            ColumnField.of(OrderPatch.class, ORDERS, "customer", String.class, Long.class, RefConverter.INSTANCE);
    /** A hand-written column over the {@code @Version} attribute. */
    private static final ColumnField<OrderPatch, OrderEntity, Integer> VERSION =
            ColumnField.of(OrderPatch.class, ORDERS, "version", Integer.class);
    private static final ColumnField<OrderPatch, CustomerEntity, String> CUSTOMER_COUNTRY =
            ColumnField.of(OrderPatch.class, CUSTOMER, "country", String.class);
    private static final ColumnField<OrderPatch, CustomerEntity, String> REFERRER_COUNTRY =
            ColumnField.of(OrderPatch.class, REFERRER, "country", String.class);

    private static final ModelUpdate.Start<OrderEntity> UPDATE = ModelUpdate.builder(ORDERS);
    /** The orders of an order's customer: an {@code exists(...)} over it reads the orders table again. */
    private static final TableField<CustomerEntity, OrderEntity> CUSTOMER_ORDERS =
            TableField.join(CUSTOMER, "orders", INNER);
    private static final ColumnField<OrderPatch, OrderEntity, String> CUSTOMER_ORDER_STATUS =
            ColumnField.of(OrderPatch.class, CUSTOMER_ORDERS, "status", String.class);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(ORDERS, "items", INNER);
    private static final ColumnField<OrderPatch, OrderItemEntity, Integer> ITEM_QUANTITY =
            ColumnField.of(OrderPatch.class, ORDER_ITEMS, "quantity", Integer.class);


    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<CustomerPatch, CustomerEntity, Long> CUSTOMER_ID =
            ColumnField.of(CustomerPatch.class, CUSTOMERS, "id", Long.class);
    private static final ColumnField<CustomerPatch, CustomerEntity, String> NAME =
            ColumnField.of(CustomerPatch.class, CUSTOMERS, "name", String.class);
    private static final ColumnField<CustomerPatch, CustomerEntity, String> EMAIL =
            ColumnField.of(CustomerPatch.class, CUSTOMERS, "email", String.class);

    private static final TableField<CompositeKeyItemEntity, CompositeKeyItemEntity> TENANT_ITEMS =
            TableField.root(CompositeKeyItemEntity.class);
    private static final ColumnField<TenantItemPatch, CompositeKeyItemEntity, Integer> TENANT_ID =
            ColumnField.of(TenantItemPatch.class, TENANT_ITEMS, "tenantId", Integer.class);
    private static final ColumnField<TenantItemPatch, CompositeKeyItemEntity, Integer> ITEM_NO =
            ColumnField.of(TenantItemPatch.class, TENANT_ITEMS, "itemNo", Integer.class);
    private static final ColumnField<TenantItemPatch, CompositeKeyItemEntity, String> LABEL =
            ColumnField.of(TenantItemPatch.class, TENANT_ITEMS, "label", String.class);

    /** The {@code OTHER} profile: 1 000 values per IN list and 2 000 binds per statement (R-VND-06). */
    private static final ModelQueryConfig OTHER = ModelQueryConfig.defaults().vendor(DatabaseVendor.OTHER);

    /**
     * An {@code OTHER} profile taking 7 values per IN list: it cannot read the target table in a sub-query, so a
     * joined write runs key-first, in rounds of 7 keys (R-WRT-11, D-63).
     */
    private static final ModelQueryConfig SEVEN_KEYS = OTHER.vendorProfiles(List.of(new VendorProfile() {
        @Override
        public DatabaseVendor vendor() {
            return DatabaseVendor.OTHER;
        }

        @Override
        public int maxInListSize() {
            return 7;
        }

        @Override
        public int maxBindParameters() {
            return 2_000;
        }

        @Override
        public void applyStreaming(Query query, int fetchSize) {}

        @Override
        public void applyTimeout(Query query, Duration timeout) {}

        @Override
        public NullOrdering defaultAscendingNullOrdering() {
            return NullOrdering.UNKNOWN;
        }
    }));

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final TableField<OrderItemEntity, OrderEntity> ITEM_ORDER = TableField.join(ITEMS, "order", INNER);
    private static final ColumnField<ItemPatch, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemPatch.class, ITEMS, "id", Long.class);
    private static final ColumnField<ItemPatch, OrderEntity, String> ITEM_ORDER_STATUS =
            ColumnField.of(ItemPatch.class, ITEM_ORDER, "status", String.class);

    /** A change set over {@link OrderPatch}, as a generated one behaves: set columns in the order set. */
    static final class OrderPatchChanges implements Changes<OrderPatch> {
        private final Map<ColumnField<OrderPatch, ?, ?>, Assignment<OrderPatch, ?>> set = new LinkedHashMap<>();

        <C> OrderPatchChanges with(ColumnField<OrderPatch, ?, C> column, C value) {
            set.put(column, value == null ? Assignment.ofNull(column) : Assignment.of(column, value));
            return this;
        }

        @Override
        public boolean isSet(ColumnField<OrderPatch, ?, ?> column) {
            return set.containsKey(column);
        }

        @Override
        public OrderPatchChanges unset(ColumnField<OrderPatch, ?, ?> column) {
            set.remove(column);
            return this;
        }

        @Override
        public boolean isEmpty() {
            return set.isEmpty();
        }

        @Override
        public List<Assignment<OrderPatch, ?>> assignments() {
            return List.copyOf(set.values());
        }
    }

    /** One order as stored. */
    record StoredOrder(String status, BigDecimal total, Long customerId, Long referrerId, Integer version) {}

    // ---- AC-WRT-01

    @TckTest
    void ac_wrt_01_a_change_set_writes_only_its_set_columns_and_null_where_it_was_set_to_null(TckDatabase db) {
        // Order 3 has a referrer (every third order does) and is CANCELLED; total is left unset.
        var changes = new OrderPatchChanges().with(STATUS, "PAID").with(REFERRER_ID, null);
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(changes).whereKey(3L).build();
        var rows = new ArrayList<StoredOrder>();
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.assertMatches(db, "wrt-01-change-set", ds -> inRolledBackTransaction(ds,
                em -> {
                    rows.add(stored(em, 3L));
                    written[0] = orders(em).update(update);
                    rows.add(stored(em, 3L));
                }));

        assertThat(written[0]).isEqualTo(1);
        StoredOrder before = rows.get(0);
        StoredOrder after = rows.get(1);
        assertThat(before.referrerId()).isNotNull();
        assertThat(after).isEqualTo(new StoredOrder("PAID", before.total(), before.customerId(), null,
                before.version() + 1));
        String write = sql.stream().filter(s -> s.startsWith("update")).findFirst().orElseThrow();
        assertThat(write).contains("status=?").contains("referrer_id=null").doesNotContain("total");
    }

    @TckTest
    void ac_wrt_04_a_hand_written_column_over_the_version_attribute_throws_mq1605_before_any_statement(
            TckDatabase db) {
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(VERSION, 7).whereKey(3L).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em ->
                assertThatThrownBy(() -> orders(em).update(update))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1605))));

        assertThat(sql).isEmpty();
    }

    // ---- AC-WRT-05

    @TckTest
    void ac_wrt_05_an_empty_change_set_runs_no_sql_and_with_expect_version_a_stale_version_throws(TckDatabase db) {
        var empty = UPDATE.primaryKey(PrimaryKey.of(ID)).set(new OrderPatchChanges()).whereKey(5L).build();
        var expecting = UPDATE.primaryKey(PrimaryKey.of(ID)).set(new OrderPatchChanges()).whereKey(5L)
                .expectVersion(0).build();
        long[] written = new long[2];
        var versions = new ArrayList<Integer>();

        List<String> none = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds,
                em -> written[0] = orders(em).update(empty)));
        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            written[1] = orders(em).update(expecting);
            versions.add(stored(em, 5L).version());
            // The version is 1 now, so expecting 0 again writes nothing and fails loudly.
            assertThatThrownBy(() -> orders(em).update(expecting)).isInstanceOf(OptimisticLockException.class);
        });

        assertThat(written).containsExactly(0, 1);
        assertThat(none).isEmpty();
        assertThat(versions).containsExactly(1);
    }

    // ---- AC-WRT-06

    @TckTest
    void ac_wrt_06_where_keys_past_the_in_list_limit_updates_every_distinct_key_once(TckDatabase db) {
        // 2 500 distinct keys, the first 100 given twice: three statements under OTHER's 1 000-value IN lists.
        List<Long> keys = new ArrayList<>(LongStream.rangeClosed(1, 2_500).boxed().toList());
        keys.addAll(LongStream.rangeClosed(1, 100).boxed().toList());
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "KEYED").whereKeys(keys).build();
        var before = new LinkedHashMap<Long, Integer>();
        var after = new LinkedHashMap<Long, Integer>();
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            before.putAll(versions(em));
            written[0] = ModelQueryExecutor.create(em, OrderEntity.class, OTHER).update(update);
            after.putAll(versions(em));
        }));

        assertThat(written[0]).isEqualTo(2_500);
        assertThat(writes(sql, "update")).extracting(BulkWriteTest::binds).containsExactly(1_002L, 1_002L, 502L);
        before.forEach((id, version) -> assertThat(after.get(id)).as("version of order %d", id)
                .isEqualTo(id <= 2_500 ? version + 1 : version));
    }

    @TckTest
    void ac_wrt_06_composite_where_keys_count_the_statement_own_binds_and_update_every_distinct_key_once(
            TckDatabase db) {
        // 1 999 distinct keys of two binds each, the first 50 given twice. The SET value takes one of OTHER's 2 000
        // binds, so a statement takes 999 keys, not 1 000: three statements, not two.
        List<List<Object>> keys = new ArrayList<>();
        for (int i = 0; i < 1_999; i++) {
            keys.add(List.of(i / 100 + 1, i % 100 + 1));
        }
        keys.addAll(keys.subList(0, 50));
        var update = ModelUpdate.builder(TENANT_ITEMS).primaryKey(PrimaryKey.composite(TENANT_ID, ITEM_NO))
                .set(LABEL, "KEYED").whereKeys(keys).build();
        long[] written = new long[1];
        var labelled = new ArrayList<Object[]>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, CompositeKeyItemEntity.class, OTHER).update(update);
            labelled.addAll(em.createQuery("select i.tenantId, i.itemNo from CompositeKeyItemEntity i"
                    + " where i.label = 'KEYED'", Object[].class).getResultList());
        }));

        assertThat(written[0]).isEqualTo(1_999);
        assertThat(writes(sql, "update")).extracting(BulkWriteTest::binds).containsExactly(1_999L, 1_999L, 3L);
        // Every item but the last, (20, 100).
        assertThat(labelled).hasSize(1_999).noneMatch(row -> row[0].equals(20) && row[1].equals(100));
    }

    @TckTest
    void ac_wrt_06_where_keys_with_no_keys_runs_no_sql_and_a_duplicate_key_is_deleted_once(TckDatabase db) {
        var noUpdate = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "NONE").whereKeys(List.of()).build();
        var noDelete = ModelDelete.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).whereKeys(List.of()).build();
        var twice = ModelDelete.builder(TENANT_ITEMS).primaryKey(PrimaryKey.composite(TENANT_ID, ITEM_NO))
                .whereKeys(List.of(List.of(3, 7), List.of(3, 8), List.of(3, 7))).build();
        long[] written = new long[3];

        List<String> none = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = orders(em).update(noUpdate);
            written[1] = orders(em).delete(noDelete);
        }));
        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> written[2] =
                ModelQueryExecutor.create(em, CompositeKeyItemEntity.class, ModelQueryConfig.defaults())
                        .delete(twice)));

        assertThat(none).isEmpty();
        assertThat(written).containsExactly(0, 0, 2);
        assertThat(writes(sql, "delete")).extracting(BulkWriteTest::binds).containsExactly(4L);
    }

    @TckTest
    void ac_wrt_06_a_primary_key_that_is_not_the_entity_id_throws_mq1608_before_any_statement(TckDatabase db) {
        var update = UPDATE.primaryKey(PrimaryKey.of(STATUS)).set(TOTAL, BigDecimal.ONE).whereKey("NEW").build();
        var delete = ModelDelete.builder(ORDERS).primaryKey(PrimaryKey.of(STATUS)).whereKey("NEW").build();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            // A pending change the flush would write: the check comes before the flush.
            em.setFlushMode(FlushModeType.COMMIT);
            em.find(CustomerEntity.class, 7L).rename("Pending");
            assertThatThrownBy(() -> orders(em).update(update))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1608));
            assertThatThrownBy(() -> orders(em).delete(delete))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1608));
        }));

        assertThat(sql).noneMatch(statement -> statement.startsWith("update") || statement.startsWith("delete"));
    }

    // ---- AC-WRT-07 (one statement on H2 and PostgreSQL, key-first on MySQL; chunked: M6.5)

    @TckTest
    void ac_wrt_07_a_joined_where_renders_whole_in_one_exists_and_writes_the_rows_the_read_returns(TckDatabase db) {
        // not(...) over a LEFT-joined column: an order with no referrer is excluded by the read, so by the write too.
        UnaryOperator<Filters<OrderPatch>> where = f -> f
                .eq(CUSTOMER_COUNTRY, "VN")
                .not(g -> g.eq(REFERRER_COUNTRY, "VN"))
                .lt(ID, 600L);
        var read = ModelQuery.builder(ORDERS, row -> new OrderPatch(row.get(ID)))
                .columns(ColumnSet.of(ID)).primaryKey(PrimaryKey.of(ID)).where(where).build();
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED").where(where).build();
        var expected = new ArrayList<Long>();
        var marked = new ArrayList<Long>();
        long[] written = new long[1];
        inRolledBackTransaction(JoinTestSupport.dataSource(db), em ->
                orders(em).list(read, Limit.unlimited()).forEach(order -> expected.add(order.id())));

        SqlSnapshots.assertMatches(db, "wrt-07-joined-update-exists", ds -> inRolledBackTransaction(ds, em -> {
            written[0] = orders(em).update(update);
            marked.addAll(em.createQuery("select o.id from OrderEntity o where o.status = 'MARKED'", Long.class)
                    .getResultList());
        }));

        assertThat(expected).isNotEmpty();
        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(marked).containsExactlyInAnyOrderElementsOf(expected);
    }

    @TckTest
    void ac_wrt_07_a_joined_delete_renders_one_exists_and_deletes_the_rows_the_read_returns(TckDatabase db) {
        UnaryOperator<Filters<ItemPatch>> where = f -> f.eq(ITEM_ORDER_STATUS, "CANCELLED").lte(ITEM_ID, 400L);
        var read = ModelQuery.builder(ITEMS, row -> new ItemPatch(row.get(ITEM_ID)))
                .columns(ColumnSet.of(ITEM_ID)).primaryKey(PrimaryKey.of(ITEM_ID)).where(where).build();
        var delete = ModelDelete.builder(ITEMS).primaryKey(PrimaryKey.of(ITEM_ID)).where(where).build();
        var expected = new ArrayList<Long>();
        var left = new ArrayList<Long>();
        long[] deleted = new long[1];
        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> ModelQueryExecutor
                .create(em, OrderItemEntity.class, ModelQueryConfig.defaults()).list(read, Limit.unlimited())
                .forEach(item -> expected.add(item.id())));

        SqlSnapshots.assertMatches(db, "wrt-07-joined-delete-exists", ds -> inRolledBackTransaction(ds, em -> {
            deleted[0] = ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults())
                    .delete(delete);
            left.addAll(em.createQuery("select i.id from OrderItemEntity i where i.id <= 400", Long.class)
                    .getResultList());
        }));

        assertThat(expected).isNotEmpty();
        assertThat(deleted[0]).isEqualTo(expected.size());
        assertThat(left).hasSize(400 - expected.size()).doesNotContainAnyElementsOf(expected);
    }

    @TckTest
    void ac_wrt_07_an_exists_path_back_to_the_root_entity_writes_the_rows_the_read_returns(TckDatabase db) {
        // The sub-query reads orders again, through the customer: one statement where the database allows it, else
        // key-first.
        UnaryOperator<Filters<OrderPatch>> where = f -> f
                .exists(CUSTOMER_ORDERS, g -> g.eq(CUSTOMER_ORDER_STATUS, "CANCELLED"))
                .lt(ID, 300L);
        assertWritesTheRowsTheReadReturns(db, "wrt-07-exists-back-to-root", ModelQueryConfig.defaults(), where);
    }

    @TckTest
    void ac_wrt_07_an_exists_path_away_from_the_root_runs_as_one_statement_on_every_vendor(TckDatabase db) {
        UnaryOperator<Filters<OrderPatch>> where = f -> f.exists(ORDER_ITEMS, g -> g.eq(ITEM_QUANTITY, 9)).lt(ID, 300L);
        List<String> sql = assertWritesTheRowsTheReadReturns(db, "wrt-07-exists-away-from-root",
                ModelQueryConfig.defaults(), where);

        assertThat(sql).hasSize(2).last().asString().startsWith("select");
    }

    @TckTest
    void ac_wrt_07_key_first_selects_keys_in_rounds_of_the_clamp_and_writes_the_rows_the_read_returns(
            TckDatabase db) {
        // OTHER cannot read the target table in a sub-query, and this profile takes 7 values per IN list.
        UnaryOperator<Filters<OrderPatch>> where = f -> f
                .eq(CUSTOMER_COUNTRY, "VN")
                .not(g -> g.eq(REFERRER_COUNTRY, "VN"))
                .lt(ID, 600L);
        List<Long> expected = readIds(db, where);
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED").where(where).build();
        var marked = new ArrayList<Long>();
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, OrderEntity.class, SEVEN_KEYS).update(update);
            marked.addAll(markedIds(em));
        }));

        assertThat(expected).hasSizeGreaterThan(7);
        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(marked).containsExactlyInAnyOrderElementsOf(expected);
        // Rounds stop on a select returning fewer than 7 keys; each write takes one round's keys, root terms only.
        assertThat(keySelects(sql)).hasSize(expected.size() / 7 + 1);
        List<String> updates = writes(sql, "update");
        assertThat(updates).hasSize((expected.size() + 6) / 7).allSatisfy(statement -> assertThat(statement)
                .doesNotContain("exists").doesNotContain("customers"));
        assertThat(updates).extracting(BulkWriteTest::binds).first().isEqualTo(7L + 3);
    }

    @TckTest
    void ac_wrt_07_key_first_where_keys_with_a_joined_where_writes_only_the_keys_the_read_returns(TckDatabase db) {
        List<Long> keys = new ArrayList<>(LongStream.rangeClosed(1, 60).boxed().toList());
        keys.addAll(LongStream.rangeClosed(1, 10).boxed().toList());
        List<Long> expected = readIds(db, f -> f.in(ID, keys).eq(CUSTOMER_COUNTRY, "VN"));
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED").whereKeys(keys)
                .where(f -> f.eq(CUSTOMER_COUNTRY, "VN")).build();
        var marked = new ArrayList<Long>();
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, OrderEntity.class, SEVEN_KEYS).update(update);
            marked.addAll(markedIds(em));
        }));

        assertThat(expected).isNotEmpty();
        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(marked).containsExactlyInAnyOrderElementsOf(expected);
        // 60 distinct keys in runs of 7: one key select per run, one write per run that selected a key.
        assertThat(keySelects(sql)).hasSize(9);
        assertThat(writes(sql, "update")).hasSizeLessThanOrEqualTo(9)
                .allSatisfy(statement -> assertThat(statement).doesNotContain("customers"));
    }

    @TckTest
    void r_wrt_17_a_key_select_repeating_a_key_of_the_round_before_throws_mq2205(TckDatabase db) {
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED")
                .where(f -> f.eq(CUSTOMER_COUNTRY, "VN")).build();
        List<Object> firstRound = new ArrayList<>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            // Every key select after the first returns the first one's rows, as a cursor that did not survive being
            // bound would.
            EntityManager replaying = intercepting(em, rows -> {
                if (firstRound.isEmpty()) {
                    firstRound.addAll(rows);
                    return rows;
                }
                return firstRound;
            });
            assertThatThrownBy(() -> ModelQueryExecutor.create(replaying, OrderEntity.class, SEVEN_KEYS)
                    .update(update))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2205));
        });
        assertThat(firstRound).hasSize(7);
    }

    // ---- AC-WRT-18 (where the database cannot read the target table in a sub-query, so a joined write is key-first)

    @TckTest
    void ac_wrt_18_a_row_that_stops_matching_on_a_root_column_after_the_key_select_is_not_written(TckDatabase db)
            throws SQLException {
        assumeKeyFirst(db);
        UnaryOperator<Filters<OrderPatch>> where = f -> f.eq(CUSTOMER_COUNTRY, "VN").eq(STATUS, "SHIPPED").lt(ID, 400L);
        List<Long> expected = readIds(db, where);
        long moved = expected.get(0);
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED").where(where).build();
        var marked = new ArrayList<Long>();
        long[] written = new long[1];
        List<Long> paid = new ArrayList<>();

        try {
            inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
                // Another transaction moves a selected row off the root term between the key select and the write.
                EntityManager racing = intercepting(em, rows -> {
                    setStatus(db, moved, "PAID");
                    return rows;
                });
                written[0] = orders(racing).update(update);
                marked.addAll(markedIds(em));
            });
            // Read afterwards: under MySQL's repeatable read the write's own transaction still sees its snapshot.
            inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> paid.addAll(em.createQuery(
                    "select o.id from OrderEntity o where o.status = 'PAID' and o.id = " + moved, Long.class)
                    .getResultList()));
        } finally {
            setStatus(db, moved, "SHIPPED");
        }

        assertThat(expected).hasSizeGreaterThan(1);
        assertThat(written[0]).isEqualTo(expected.size() - 1);
        assertThat(marked).containsExactlyInAnyOrderElementsOf(expected.subList(1, expected.size()));
        assertThat(paid).containsExactly(moved);
    }

    @TckTest
    void ac_wrt_18_with_lock_keys_a_concurrent_change_to_a_selected_row_waits_for_the_write(TckDatabase db)
            throws Exception {
        assumeKeyFirst(db);
        UnaryOperator<Filters<OrderPatch>> where = f -> f.eq(CUSTOMER_COUNTRY, "VN").lt(ID, 400L);
        List<Long> expected = readIds(db, where);
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED").where(where)
                .chunked(ChunkOptions.defaultSize().lockKeys()).build();
        long[] written = new long[1];
        boolean[] waited = new boolean[1];
        List<CompletableFuture<Void>> change = new ArrayList<>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            EntityManager locking = intercepting(em, rows -> {
                change.add(CompletableFuture.runAsync(() -> touchTotal(db, expected.get(0))));
                try {
                    change.get(0).get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    waited[0] = true;
                } catch (InterruptedException | ExecutionException e) {
                    throw new IllegalStateException(e);
                }
                return rows;
            });
            written[0] = orders(locking).update(update);
        });

        assertThat(waited[0]).as("the concurrent change waited for the write's transaction").isTrue();
        change.get(0).get(30, TimeUnit.SECONDS);
        assertThat(written[0]).isEqualTo(expected.size());
    }

    // ---- AC-WRT-09

    @TckTest
    void ac_wrt_09_converters_apply_to_assigned_values_and_a_to_one_set_by_id_loads_no_row(TckDatabase db) {
        var update = UPDATE.primaryKey(PrimaryKey.of(ID))
                .set(STATUS_CODE, OrderStatus.SHIPPED)
                .set(CUSTOMER_REF, "#42")
                .whereKey(10L)
                .build();
        var rows = new ArrayList<StoredOrder>();

        List<String> sql = SqlSnapshots.assertMatches(db, "wrt-09-converter-and-reference", ds ->
                inRolledBackTransaction(ds, em -> {
                    orders(em).update(update);
                    rows.add(stored(em, 10L));
                }));

        assertThat(rows.get(0).status()).isEqualTo("SHIPPED");
        assertThat(rows.get(0).customerId()).isEqualTo(42L);
        assertThat(sql).noneMatch(statement -> statement.contains("from customers"));
    }

    // ---- AC-WRT-11

    @TckTest
    void ac_wrt_11_updates_increment_the_version_keep_version_does_not_and_a_mismatch_throws(TckDatabase db) {
        var versions = new ArrayList<Integer>();
        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            ModelQueryExecutor<OrderEntity> orders = orders(em);
            // Every row a where matches is incremented: order ids 4, 8, ... below 100 are the NEW ones.
            long written = orders.update(UPDATE.primaryKey(PrimaryKey.of(ID)).set(TOTAL, BigDecimal.TEN)
                    .where(f -> f.eq(STATUS, "NEW").lt(ID, 100L)).build());
            assertThat(written).isEqualTo(24);
            assertThat(em.createQuery("select distinct o.version from OrderEntity o where o.status = 'NEW'"
                    + " and o.id < 100", Integer.class).getResultList()).containsExactly(1);
            versions.add(stored(em, 20L).version());
            orders.update(UPDATE.primaryKey(PrimaryKey.of(ID)).set(TOTAL, BigDecimal.ONE).whereKey(20L)
                    .keepVersion().build());
            versions.add(stored(em, 20L).version());
            var expectOne = UPDATE.primaryKey(PrimaryKey.of(ID)).set(TOTAL, BigDecimal.ZERO).whereKey(20L)
                    .expectVersion(1).build();
            orders.update(expectOne);
            versions.add(stored(em, 20L).version());
            assertThatThrownBy(() -> orders.update(expectOne)).isInstanceOf(OptimisticLockException.class);
        });

        assertThat(versions).containsExactly(1, 1, 2);
    }

    @TckTest
    void ac_wrt_11_expect_version_on_a_root_with_no_version_or_of_another_type_throws_mq1606_before_any_statement(
            TckDatabase db) {
        var unversioned = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_ID)).set(NAME, "x")
                .whereKey(1L).expectVersion(0).build();
        var wrongType = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "x").whereKey(1L).expectVersion(0L).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            assertThatThrownBy(() -> customers(em).update(unversioned))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1606));
            assertThatThrownBy(() -> orders(em).update(wrongType))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1606));
        }));

        assertThat(sql).isEmpty();
    }

    // ---- AC-WRT-10 (a commitEachChunk() write outside a transaction is M6.5's)

    @TckTest
    void ac_wrt_10_pending_changes_are_flushed_first_and_the_persistence_context_is_cleared_by_default(
            TckDatabase db) {
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_ID)).set(EMAIL, "new@test")
                .whereKey(7L).build();
        var stored = new ArrayList<Object[]>();
        boolean[] managed = new boolean[1];

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            // Nothing flushes the pending rename but the write itself.
            em.setFlushMode(FlushModeType.COMMIT);
            CustomerEntity customer = em.find(CustomerEntity.class, 7L);
            customer.rename("Flushed");
            customers(em).update(update);
            managed[0] = em.contains(customer);
            stored.add(em.createQuery("select c.name, c.email from CustomerEntity c where c.id = 7", Object[].class)
                    .getSingleResult());
        }));

        List<String> updates = writes(sql, "update");
        assertThat(updates).hasSize(2);
        assertThat(updates.get(0)).contains("name=?");
        assertThat(updates.get(1)).contains("email=?").doesNotContain("name=?");
        assertThat(managed[0]).isFalse();
        assertThat(stored.get(0)).containsExactly("Flushed", "new@test");
    }

    @TckTest
    void ac_wrt_10_keep_leaves_the_persistence_context_and_a_write_own_mode_wins_over_the_configured_one(
            TckDatabase db) {
        ModelUpdate.Options<CustomerEntity, Long, CustomerPatch> rename = ModelUpdate.builder(CUSTOMERS)
                .primaryKey(PrimaryKey.of(CUSTOMER_ID)).set(EMAIL, "kept@test").whereKey(7L);
        ModelQueryConfig keep = ModelQueryConfig.defaults().persistenceContextMode(PersistenceContextMode.KEEP);
        var managed = new ArrayList<Boolean>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            CustomerEntity customer = em.find(CustomerEntity.class, 7L);
            ModelQueryExecutor.create(em, CustomerEntity.class, keep).update(rename.build());
            managed.add(em.contains(customer));
            ModelQueryExecutor.create(em, CustomerEntity.class, keep)
                    .update(rename.persistenceContext(PersistenceContextMode.CLEAR).build());
            managed.add(em.contains(customer));
            customer = em.find(CustomerEntity.class, 7L);
            customers(em).update(rename.persistenceContext(PersistenceContextMode.KEEP).build());
            managed.add(em.contains(customer));
        });

        assertThat(managed).containsExactly(true, false, true);
    }

    @TckTest
    void ac_wrt_10_the_root_is_evicted_from_the_second_level_cache_after_a_write_and_after_a_failed_one(
            TckDatabase db) {
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_ID)).set(NAME, "x")
                .whereKey(7L).build();
        // Customer 1 has orders, whose foreign key refuses the delete.
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_ID)).whereKey(1L).build();
        var evicted = new ArrayList<Object>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            EntityManager recording = recordingEvictions(em, evicted);
            customers(recording).update(update);
            assertThat(evicted).containsExactly(CustomerEntity.class);
            assertThatThrownBy(() -> customers(recording).delete(delete))
                    .isInstanceOf(ConstraintViolationException.class);
        });

        assertThat(evicted).containsExactly(CustomerEntity.class, CustomerEntity.class);
    }

    // ---- AC-WRT-13

    @TckTest
    void ac_wrt_13_a_write_with_no_transaction_throws_mq2501_before_any_statement(TckDatabase db) {
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_ID)).set(NAME, "x")
                .whereKey(7L).build();
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_ID)).whereKey(7L).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = JoinTestSupport.sessionFactory(ds)) {
                factory.inSession(em -> {
                    assertThatThrownBy(() -> customers(em).update(update))
                            .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ2501))
                            .hasMessageContaining("bulk update");
                    assertThatThrownBy(() -> customers(em).delete(delete))
                            .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ2501))
                            .hasMessageContaining("bulk delete");
                });
            }
        });

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_wrt_13_a_delete_blocked_by_a_foreign_key_surfaces_the_provider_constraint_exception(TckDatabase db) {
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_ID)).whereKey(1L).build();
        boolean[] managed = new boolean[1];

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            CustomerEntity customer = em.find(CustomerEntity.class, 2L);
            assertThatThrownBy(() -> customers(em).delete(delete))
                    .isInstanceOf(ConstraintViolationException.class);
            // The persistence context is cleared even so (D-62).
            managed[0] = em.contains(customer);
        });

        assertThat(managed[0]).isFalse();
    }

    // ---- AC-WRT-19

    @TckTest
    void ac_wrt_19_set_expression_reading_a_column_a_plain_assignment_writes_reads_the_old_value(TckDatabase db) {
        // Declared after the plain assignment, rendered before it: MySQL evaluates SET left to right.
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_ID))
                .set(NAME, "Renamed")
                .setExpression(EMAIL, (email, cb) -> cb.concat(email.getParentPath().<String>get("name"), "@old.test"))
                .whereKey(7L)
                .build();
        var stored = new ArrayList<Object[]>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            customers(em).update(update);
            stored.add(em.createQuery("select c.name, c.email from CustomerEntity c where c.id = 7", Object[].class)
                    .getSingleResult());
        });

        assertThat(stored.get(0)).containsExactly("Renamed", "Customer 0007@old.test");
    }

    // ---- support

    private static ModelQueryExecutor<OrderEntity> orders(EntityManager em) {
        return ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
    }

    private static ModelQueryExecutor<CustomerEntity> customers(EntityManager em) {
        return ModelQueryExecutor.create(em, CustomerEntity.class, ModelQueryConfig.defaults());
    }

    /**
     * Updates the orders {@code where} chooses to {@code MARKED} in a rolled-back transaction under {@code config},
     * asserting the statements against snapshot {@code name} and the orders marked against the ones the matching read
     * returns (AC-WRT-07); returns the statements.
     */
    private static List<String> assertWritesTheRowsTheReadReturns(TckDatabase db, String name, ModelQueryConfig config,
            UnaryOperator<Filters<OrderPatch>> where) {
        List<Long> expected = readIds(db, where);
        var update = UPDATE.primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED").where(where).build();
        var marked = new ArrayList<Long>();
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.assertMatches(db, name, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, OrderEntity.class, config).update(update);
            marked.addAll(markedIds(em));
        }));

        assertThat(expected).isNotEmpty();
        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(marked).containsExactlyInAnyOrderElementsOf(expected);
        return sql;
    }

    /** The ids of the orders {@code where} chooses, as the read path returns them. */
    private static List<Long> readIds(TckDatabase db, UnaryOperator<Filters<OrderPatch>> where) {
        var read = ModelQuery.builder(ORDERS, row -> new OrderPatch(row.get(ID)))
                .columns(ColumnSet.of(ID)).primaryKey(PrimaryKey.of(ID)).where(where).build();
        var ids = new ArrayList<Long>();
        inRolledBackTransaction(JoinTestSupport.dataSource(db), em ->
                orders(em).list(read, Limit.unlimited()).forEach(order -> ids.add(order.id())));
        return ids;
    }

    private static List<Long> markedIds(EntityManager em) {
        return em.createQuery("select o.id from OrderEntity o where o.status = 'MARKED'", Long.class).getResultList();
    }

    /** The key selects of {@code sql}: its selects that join, as a key select over a joined tree does. */
    private static List<String> keySelects(List<String> sql) {
        return sql.stream().filter(statement -> statement.startsWith("select") && statement.contains(" join "))
                .toList();
    }

    /** Skips the calling test where the database can read a write's target table in a sub-query (R-VND-11). */
    private static void assumeKeyFirst(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            Assumptions.assumeFalse(VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW)
                    .profile().targetTableInSubquery(), "key-first runs where a write cannot read its own table");
        }
    }

    /** Commits {@code status} for order {@code id} on a connection of its own. */
    private static void setStatus(TckDatabase db, long id, String status) {
        execute(db, "update orders set status = '" + status + "' where id = " + id);
    }

    /** Writes order {@code id}'s total unchanged on a connection of its own, which waits for any lock on the row. */
    private static void touchTotal(TckDatabase db, long id) {
        execute(db, "update orders set total = total where id = " + id);
    }

    private static void execute(TckDatabase db, String sql) {
        try (Connection c = db.getConnection(); Statement statement = c.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /**
     * {@code em}, whose Criteria queries return their rows through {@code onRows}, so a test can act between a key
     * select and the write, or change what the select returned.
     */
    @SuppressWarnings("unchecked")
    private static EntityManager intercepting(EntityManager em, UnaryOperator<List<Object>> onRows) {
        return proxy(EntityManager.class, (method, args) -> {
            Object result = method.invoke(em, args);
            if (!method.getName().equals("createQuery") || !(args[0] instanceof CriteriaQuery<?>)) {
                return result;
            }
            TypedQuery<Object> query = (TypedQuery<Object>) result;
            return proxy(TypedQuery.class, (queryMethod, queryArgs) -> queryMethod.getName().equals("getResultList")
                    ? onRows.apply(query.getResultList())
                    : queryMethod.invoke(query, queryArgs));
        });
    }

    /** Every order's version, by id. */
    private static Map<Long, Integer> versions(EntityManager em) {
        var versions = new LinkedHashMap<Long, Integer>();
        em.createQuery("select o.id, o.version from OrderEntity o", Object[].class).getResultList()
                .forEach(row -> versions.put((Long) row[0], (Integer) row[1]));
        return versions;
    }

    /** The statements of {@code sql} that start with {@code verb}. */
    private static List<String> writes(List<String> sql, String verb) {
        return sql.stream().filter(statement -> statement.startsWith(verb)).toList();
    }

    /** The bind parameters of {@code statement}. */
    private static long binds(String statement) {
        return statement.chars().filter(c -> c == '?').count();
    }

    /** {@code em}, whose factory's second-level cache records each entity type evicted into {@code evicted}. */
    private static EntityManager recordingEvictions(EntityManager em, List<Object> evicted) {
        EntityManagerFactory factory = em.getEntityManagerFactory();
        Cache cache = factory.getCache();
        Cache recordingCache = proxy(Cache.class, (method, args) -> {
            if (method.getName().equals("evict") && args.length == 1) {
                evicted.add(args[0]);
            }
            return method.invoke(cache, args);
        });
        EntityManagerFactory recordingFactory = proxy(EntityManagerFactory.class, (method, args) ->
                method.getName().equals("getCache") ? recordingCache : method.invoke(factory, args));
        return proxy(EntityManager.class, (method, args) ->
                method.getName().equals("getEntityManagerFactory") ? recordingFactory : method.invoke(em, args));
    }

    /** A call on a {@link #proxy}. */
    private interface Call {
        Object invoke(Method method, Object[] args) throws ReflectiveOperationException;
    }

    /** A {@code type} whose every call goes to {@code call}, rethrowing what the target threw. */
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            try {
                return call.invoke(method, args == null ? new Object[0] : args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }));
    }

    private static StoredOrder stored(EntityManager em, long id) {
        Object[] row = em.createQuery("select o.status, o.total, o.customer.id, o.referrerId, o.version"
                + " from OrderEntity o where o.id = :id", Object[].class).setParameter("id", id).getSingleResult();
        return new StoredOrder((String) row[0], (BigDecimal) row[1], (Long) row[2], (Long) row[3], (Integer) row[4]);
    }

    /** Runs {@code work} in a transaction over {@code ds} and rolls it back, so the fixture stays as seeded. */
    private static void inRolledBackTransaction(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory factory = JoinTestSupport.sessionFactory(ds)) {
            factory.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.accept(em);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
