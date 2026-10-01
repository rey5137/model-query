package com.rey.modelquery.tck.wrt;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Bulk updates and deletes rendered as one statement (spec api/14 R-WRT-07, R-WRT-10, R-WRT-13, R-WRT-14,
 * R-WRT-16, D-61). Every write runs in a transaction that is rolled back, so the shared fixture stays as seeded. A
 * write whose tree needs a join reads its target table in a sub-query, which MySQL refuses; those run on H2 and
 * PostgreSQL until MySQL's key-first path lands (M6.4).
 */
class BulkWriteTest {

    /** The update model; also read back as a one-column query model. */
    record OrderPatch(Long id) {}

    record CustomerPatch(Long id) {}

    record ItemPatch(Long id) {}

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

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<CustomerPatch, CustomerEntity, Long> CUSTOMER_ID =
            ColumnField.of(CustomerPatch.class, CUSTOMERS, "id", Long.class);
    private static final ColumnField<CustomerPatch, CustomerEntity, String> NAME =
            ColumnField.of(CustomerPatch.class, CUSTOMERS, "name", String.class);
    private static final ColumnField<CustomerPatch, CustomerEntity, String> EMAIL =
            ColumnField.of(CustomerPatch.class, CUSTOMERS, "email", String.class);

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

    // ---- AC-WRT-06 (MQ1608 only; key splitting and duplicates are M6.3's)

    @TckTest
    void ac_wrt_06_a_primary_key_that_is_not_the_entity_id_throws_mq1608_before_any_statement(TckDatabase db) {
        var update = UPDATE.primaryKey(PrimaryKey.of(STATUS)).set(TOTAL, BigDecimal.ONE).whereKey("NEW").build();
        var delete = ModelDelete.builder(ORDERS).primaryKey(PrimaryKey.of(STATUS)).whereKey("NEW").build();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            assertThatThrownBy(() -> orders(em).update(update))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1608));
            assertThatThrownBy(() -> orders(em).delete(delete))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1608));
        }));

        assertThat(sql).isEmpty();
    }

    // ---- AC-WRT-07 (one statement on H2 and PostgreSQL; the parity over every Filters fixture is M6.4's)

    @TckTest
    void ac_wrt_07_a_joined_where_renders_whole_in_one_exists_and_writes_the_rows_the_read_returns(TckDatabase db) {
        db.assumeTargetTableInSubquery();
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
        db.assumeTargetTableInSubquery();
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
