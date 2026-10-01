package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.binds;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.execute;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.inRolledBackTransaction;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.markedIds;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.readIds;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.versions;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ChunkTransactions;
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
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.wrt.BulkWriteTest.ItemPatch;
import com.rey.modelquery.tck.wrt.BulkWriteTest.OrderPatch;
import com.rey.modelquery.tck.wrt.BulkWriteTest.RefConverter;
import com.rey.modelquery.tck.wrt.BulkWriteTest.TenantItemPatch;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Query;
import jakarta.persistence.RollbackException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Chunked bulk writes: rounds of a key select in key order and a write over the keys it chose, in the caller's
 * transaction, or each in a new transaction through a {@link ChunkTransactions} (spec api/14 R-WRT-15, R-WRT-17,
 * R-WRT-19, R-WRT-20, D-62, D-63). A write in the caller's transaction is rolled back; a write that commits each chunk
 * runs on rows the test inserts and removes again, or restores what it changed, so the shared fixture stays as seeded.
 */
class ChunkedWriteTest {

    /** A customer model whose key is {@code #42} in the model and {@code 42} in the entity. */
    record CustomerRef(String id) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER = TableField.join(ORDERS, "referrer", LEFT);
    private static final ColumnField<OrderPatch, OrderEntity, Long> ID =
            ColumnField.of(OrderPatch.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderPatch, OrderEntity, String> STATUS =
            ColumnField.of(OrderPatch.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderPatch, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderPatch.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<OrderPatch, CustomerEntity, String> CUSTOMER_COUNTRY =
            ColumnField.of(OrderPatch.class, CUSTOMER, "country", String.class);
    private static final ColumnField<OrderPatch, CustomerEntity, String> REFERRER_COUNTRY =
            ColumnField.of(OrderPatch.class, REFERRER, "country", String.class);

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemPatch, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemPatch.class, ITEMS, "id", Long.class);

    private static final TableField<CompositeKeyItemEntity, CompositeKeyItemEntity> TENANT_ITEMS =
            TableField.root(CompositeKeyItemEntity.class);
    private static final ColumnField<TenantItemPatch, CompositeKeyItemEntity, Integer> TENANT_ID =
            ColumnField.of(TenantItemPatch.class, TENANT_ITEMS, "tenantId", Integer.class);
    private static final ColumnField<TenantItemPatch, CompositeKeyItemEntity, Integer> ITEM_NO =
            ColumnField.of(TenantItemPatch.class, TENANT_ITEMS, "itemNo", Integer.class);
    private static final ColumnField<TenantItemPatch, CompositeKeyItemEntity, String> LABEL =
            ColumnField.of(TenantItemPatch.class, TENANT_ITEMS, "label", String.class);

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<CustomerRef, CustomerEntity, String> CUSTOMER_REF = ColumnField.of(
            CustomerRef.class, CUSTOMERS, "id", String.class, Long.class, RefConverter.INSTANCE);
    private static final ColumnField<CustomerRef, CustomerEntity, String> NAME =
            ColumnField.of(CustomerRef.class, CUSTOMERS, "name", String.class);

    /** The ids above which a test inserts customers and orders of its own, and removes them again. */
    private static final long TEMPORARY = 100_000;

    /** The temporary customers, by their model key. */
    private static final UnaryOperator<Filters<CustomerRef>> TEMPORARY_CUSTOMERS =
            f -> f.gt(CUSTOMER_REF, "#" + TEMPORARY);

    /** The plain-JPA callback of R-WRT-19: a resource-local {@code EntityManager} per chunk, begin, commit, close. */
    private static class ResourceLocal implements ChunkTransactions {

        int chunks;

        @Override
        public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
            chunks++;
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                T result;
                try {
                    result = chunk.apply(em);
                } catch (RuntimeException e) {
                    em.getTransaction().rollback();
                    throw e;
                }
                commit(em.getTransaction());
                return result;
            } finally {
                em.close();
            }
        }

        void commit(EntityTransaction transaction) {
            transaction.commit();
        }
    }

    // ---- AC-WRT-12

    @TckTest
    void ac_wrt_12_a_chunked_update_that_leaves_its_rows_matching_terminates_and_writes_each_row_once(
            TckDatabase db) {
        UnaryOperator<Filters<OrderPatch>> where = f -> f.eq(STATUS, "SHIPPED").lt(ID, 300L);
        var expected = new HashSet<>(readIds(db, where));
        // The rows still match once written, so only the cursor moves the rounds on; the size is the configured one.
        var update = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).set(TOTAL, BigDecimal.TEN).where(where)
                .chunked(ChunkOptions.defaultSize()).build();
        var config = ModelQueryConfig.defaults().bulkWriteChunkSize(7);
        var before = new LinkedHashMap<Long, Integer>();
        var after = new LinkedHashMap<Long, Integer>();
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            before.putAll(versions(em));
            written[0] = ModelQueryExecutor.create(em, OrderEntity.class, config).update(update);
            after.putAll(versions(em));
        }));

        assertThat(expected).hasSizeGreaterThan(14);
        assertThat(written[0]).isEqualTo(expected.size());
        before.forEach((id, version) -> assertThat(after.get(id)).as("version of order %d", id)
                .isEqualTo(expected.contains(id) ? version + 1 : version));
        // Rounds stop on a select returning fewer than 7 keys.
        assertThat(keySelects(sql)).hasSize(expected.size() / 7 + 1);
        List<String> updates = writes(sql, "update");
        assertThat(updates).hasSize((expected.size() + 6) / 7);
        // Seven keys, the SET value, the version increment and the two root terms.
        assertThat(binds(updates.get(0))).isEqualTo(7 + 2 + 2);
    }

    @TckTest
    void ac_wrt_12_a_chunked_delete_crosses_the_vendor_in_list_limit_with_a_single_key(TckDatabase db) {
        var delete = ModelDelete.builder(ITEMS).primaryKey(PrimaryKey.of(ITEM_ID)).all()
                .chunked(ChunkOptions.size(15_000)).build();
        long[] written = new long[1];
        long[] left = new long[1];

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults())
                    .delete(delete);
            left[0] = em.createQuery("select count(i) from OrderItemEntity i", Long.class).getSingleResult();
        }));

        // Every Tier-1 profile takes 10 000 values per IN list: two full rounds, then a select returning no key.
        assertThat(written[0]).isEqualTo(TckFixture.ORDER_ITEMS);
        assertThat(left[0]).isZero();
        assertThat(keySelects(sql)).hasSize(3);
        assertThat(writes(sql, "delete")).extracting(BulkWriteTest::binds).containsExactly(10_000L, 10_000L);
    }

    @TckTest
    void ac_wrt_12_a_chunked_composite_delete_counts_its_own_binds_against_the_vendor_bind_limit(TckDatabase db) {
        // 15 binds a statement: the tenant bound takes one, so a statement takes (15 - 1) / 2 = 7 two-column keys.
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 1_000, 15)));
        var delete = ModelDelete.builder(TENANT_ITEMS).primaryKey(PrimaryKey.composite(TENANT_ID, ITEM_NO))
                .where(f -> f.lt(TENANT_ID, 3)).chunked(ChunkOptions.size(1_000)).build();
        long[] written = new long[1];
        var left = new ArrayList<Object[]>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, CompositeKeyItemEntity.class, config).delete(delete);
            left.addAll(em.createQuery("select min(i.tenantId), count(i) from CompositeKeyItemEntity i",
                    Object[].class).getResultList());
        }));

        assertThat(written[0]).isEqualTo(200);
        assertThat(left.get(0)).containsExactly(3, (long) TckFixture.COMPOSITE_KEY_ITEMS - 200);
        List<String> deletes = writes(sql, "delete");
        assertThat(deletes).hasSize(29);
        assertThat(deletes.subList(0, 28)).extracting(BulkWriteTest::binds).containsOnly(15L);
        assertThat(binds(deletes.get(28))).isEqualTo(4 * 2 + 1);
    }

    @TckTest
    void ac_wrt_12_a_chunked_composite_update_selects_after_the_last_key_by_or_expansion(TckDatabase db) {
        var update = ModelUpdate.builder(TENANT_ITEMS).primaryKey(PrimaryKey.composite(TENANT_ID, ITEM_NO))
                .set(LABEL, "CHUNKED").where(f -> f.eq(TENANT_ID, 1).lt(ITEM_NO, 8))
                .chunked(ChunkOptions.size(3)).build();
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.assertMatches(db, "wrt-17-chunked-composite-update", ds ->
                inRolledBackTransaction(ds, em -> written[0] = ModelQueryExecutor.create(em,
                        CompositeKeyItemEntity.class, ModelQueryConfig.defaults()).update(update)));

        assertThat(written[0]).isEqualTo(7);
        // Two binds a key, the SET value and the two root terms.
        assertThat(writes(sql, "update")).extracting(BulkWriteTest::binds)
                .containsExactly(3L * 2 + 3, 3L * 2 + 3, 1L * 2 + 3);
    }

    // ---- AC-WRT-07, chunked (the parity over every Filters fixture is M6.9's)

    @TckTest
    void ac_wrt_07_a_chunked_joined_update_writes_the_rows_the_read_returns(TckDatabase db) {
        UnaryOperator<Filters<OrderPatch>> where = f -> f
                .eq(CUSTOMER_COUNTRY, "VN")
                .not(g -> g.eq(REFERRER_COUNTRY, "VN"))
                .lt(ID, 600L);
        List<Long> expected = readIds(db, where);
        var update = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED").where(where)
                .chunked(ChunkOptions.size(7)).build();
        var marked = new ArrayList<Long>();
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()).update(update);
            marked.addAll(markedIds(em));
        }));

        assertThat(expected).hasSizeGreaterThan(7);
        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(marked).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(keySelects(sql)).hasSize(expected.size() / 7 + 1);
        // The write re-checks the whole tree where the database reads its target in a sub-query, else the root terms.
        boolean wholeTree = builtIn(db).targetTableInSubquery();
        assertThat(writes(sql, "update")).hasSize((expected.size() + 6) / 7)
                .allSatisfy(statement -> assertThat(statement.contains("exists")).isEqualTo(wholeTree));
    }

    @TckTest
    void ac_wrt_18_a_chunked_lock_keys_write_over_a_left_joined_tree_locks_and_writes_on_every_vendor(TckDatabase db) {
        UnaryOperator<Filters<OrderPatch>> where = f -> f
                .eq(CUSTOMER_COUNTRY, "VN")
                .not(g -> g.eq(REFERRER_COUNTRY, "VN"))
                .lt(ID, 600L);
        List<Long> expected = readIds(db, where);
        var update = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED").where(where)
                .chunked(ChunkOptions.size(7).lockKeys()).build();
        var marked = new ArrayList<Long>();
        long[] written = new long[1];

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            written[0] = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults()).update(update);
            marked.addAll(markedIds(em));
        });

        assertThat(expected).hasSizeGreaterThan(7);
        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(marked).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---- AC-WRT-10, commitEachChunk

    @TckTest
    void ac_wrt_10_a_commit_each_chunk_write_outside_a_transaction_runs_without_flushing(TckDatabase db) {
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF)).set(NAME, "Chunked")
                .where(TEMPORARY_CUSTOMERS).chunked(ChunkOptions.size(2).commitEachChunk()).build();
        var transactions = new ResourceLocal();
        long[] written = new long[1];
        boolean[] managed = new boolean[1];

        try {
            insertCustomers(db, TEMPORARY + 1, TEMPORARY + 5);
            withoutTransaction(JoinTestSupport.dataSource(db), em -> {
                // A flush outside a transaction would throw; the write runs none, and the rename is never written.
                CustomerEntity customer = em.find(CustomerEntity.class, 7L);
                customer.rename("Not flushed");
                written[0] = customers(em, ModelQueryConfig.defaults().chunkTransactions(transactions))
                        .update(update);
                managed[0] = em.contains(customer);
            });

            assertThat(written[0]).isEqualTo(5);
            assertThat(transactions.chunks).isEqualTo(3);
            assertThat(managed[0]).isFalse();
            assertThat(queryStrings(db, "select name from customers where id = 7")).doesNotContain("Not flushed");
            assertThat(queryStrings(db, "select name from customers where id > " + TEMPORARY))
                    .hasSize(5).containsOnly("Chunked");
        } finally {
            removeTemporaryRows(db);
        }
    }

    // ---- AC-WRT-14

    @TckTest
    void ac_wrt_14_commit_each_chunk_with_no_chunk_transactions_throws_mq4004_and_runs_no_sql(TckDatabase db) {
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF)).set(NAME, "x")
                .where(TEMPORARY_CUSTOMERS).chunked(ChunkOptions.defaultSize().commitEachChunk()).build();
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF))
                .where(TEMPORARY_CUSTOMERS).chunked(ChunkOptions.defaultSize().commitEachChunk()).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> withoutTransaction(ds, em -> {
            assertThatThrownBy(() -> customers(em, ModelQueryConfig.defaults()).update(update))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4004))
                    .hasMessageContaining("bulk update with commitEachChunk()");
            assertThatThrownBy(() -> customers(em, ModelQueryConfig.defaults()).delete(delete))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4004))
                    .hasMessageContaining("bulk delete with commitEachChunk()");
        }));

        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_wrt_14_a_chunk_transactions_that_cannot_serve_the_factory_throws_mq4004_before_the_flush(
            TckDatabase db) {
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF)).set(NAME, "x")
                .where(TEMPORARY_CUSTOMERS).chunked(ChunkOptions.defaultSize().commitEachChunk()).build();
        var refusing = new ResourceLocal() {
            @Override
            public void checkServes(EntityManagerFactory emf) {
                throw new IllegalStateException("no transaction manager for this factory");
            }
        };

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            // A pending rename only the write's flush would write.
            em.setFlushMode(FlushModeType.COMMIT);
            em.find(CustomerEntity.class, 7L).rename("Not flushed");
            assertThatThrownBy(() -> customers(em, ModelQueryConfig.defaults().chunkTransactions(refusing))
                    .update(update))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4004))
                    .hasMessageContaining("no transaction manager for this factory")
                    .hasCauseInstanceOf(IllegalStateException.class);
        }));

        assertThat(writes(sql, "update")).isEmpty();
        assertThat(refusing.chunks).isZero();
    }

    @TckTest
    void ac_wrt_14_with_a_plain_jpa_resource_local_callback_each_chunk_commits_separately(TckDatabase db) {
        var update = ModelUpdate.builder(TENANT_ITEMS).primaryKey(PrimaryKey.composite(TENANT_ID, ITEM_NO))
                .set(LABEL, "CHUNKED").where(f -> f.eq(TENANT_ID, 1))
                .chunked(ChunkOptions.size(30).commitEachChunk()).build();
        String chunkedRows = "select count(*) from composite_key_items where label = 'CHUNKED'";
        var visibleAfterCommit = new ArrayList<Long>();
        var transactions = new ResourceLocal() {
            @Override
            void commit(EntityTransaction transaction) {
                super.commit(transaction);
                // Read on a connection of its own: what each commit made visible to every other transaction.
                visibleAfterCommit.add(queryLongs(db, chunkedRows).get(0));
            }
        };
        Map<Integer, String> labels = tenantOneLabels(db);
        long[] written = new long[1];

        try {
            withoutTransaction(JoinTestSupport.dataSource(db), em -> written[0] = ModelQueryExecutor.create(em,
                    CompositeKeyItemEntity.class, ModelQueryConfig.defaults().chunkTransactions(transactions))
                    .update(update));
        } finally {
            restoreTenantOneLabels(db, labels);
        }

        assertThat(written[0]).isEqualTo(TckFixture.COMPOSITE_ITEMS_PER_TENANT);
        assertThat(visibleAfterCommit).containsExactly(30L, 60L, 90L, 100L);
        assertThat(queryLongs(db, chunkedRows)).containsExactly(0L);
    }

    // ---- AC-WRT-15

    @TckTest
    void ac_wrt_15_a_per_chunk_delete_whose_third_chunk_fails_keeps_the_first_two_and_resumes_after_the_last_key(
            TckDatabase db) {
        var options = ChunkOptions.size(10).commitEachChunk();
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF))
                .where(TEMPORARY_CUSTOMERS).chunked(options).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];
        long[] resumed = new long[1];

        try {
            insertCustomers(db, TEMPORARY + 1, TEMPORARY + 30);
            // An order of the 25th customer: the third chunk's delete breaks its foreign key.
            insertOrder(db, TEMPORARY + 1, TEMPORARY + 25);
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    customers(em, config).delete(delete)));

            assertThat(failure[0].code()).isEqualTo(MqCode.MQ2502);
            assertThat(failure[0]).hasCauseInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("the 2 chunks before it stay committed, 20 rows");
            assertThat(failure[0].committedRows()).isEqualTo(20);
            assertThat(failure[0].lastCommittedKey()).contains("#100020");
            assertThat(failure[0].inDoubtKeys()).isEmpty();
            assertThat(temporaryCustomers(db)).containsExactlyElementsOf(range(TEMPORARY + 21, TEMPORARY + 30));

            execute(db, "delete from orders where id > " + TEMPORARY);
            var resume = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF))
                    .where(TEMPORARY_CUSTOMERS)
                    .chunked(options, (String) failure[0].lastCommittedKey().orElseThrow()).build();
            withoutTransaction(JoinTestSupport.dataSource(db), em -> resumed[0] = customers(em, config).delete(resume));

            assertThat(resumed[0]).isEqualTo(10);
            assertThat(temporaryCustomers(db)).isEmpty();
        } finally {
            removeTemporaryRows(db);
        }
    }

    @TckTest
    void ac_wrt_15_a_first_chunk_failure_throws_mq2502_with_zero_rows_and_start_after_skips_the_keys_up_to_it(
            TckDatabase db) {
        var options = ChunkOptions.size(10).commitEachChunk();
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF))
                .where(TEMPORARY_CUSTOMERS).chunked(options).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];
        long[] resumed = new long[1];

        try {
            insertCustomers(db, TEMPORARY + 1, TEMPORARY + 30);
            insertOrder(db, TEMPORARY + 1, TEMPORARY + 3);
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    customers(em, config).delete(delete)));

            assertThat(failure[0].code()).isEqualTo(MqCode.MQ2502);
            assertThat(failure[0]).hasCauseInstanceOf(ConstraintViolationException.class);
            assertThat(failure[0].committedRows()).isZero();
            assertThat(failure[0].lastCommittedKey()).isEmpty();
            assertThat(failure[0].inDoubtKeys()).isEmpty();
            assertThat(temporaryCustomers(db)).hasSize(30);

            // Starting after the 20th key leaves the first 20, the blocked customer among them, as they are.
            var rest = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF))
                    .where(TEMPORARY_CUSTOMERS).chunked(options, "#" + (TEMPORARY + 20)).build();
            withoutTransaction(JoinTestSupport.dataSource(db), em -> resumed[0] = customers(em, config).delete(rest));

            assertThat(resumed[0]).isEqualTo(10);
            assertThat(temporaryCustomers(db)).containsExactlyElementsOf(range(TEMPORARY + 1, TEMPORARY + 20));
        } finally {
            removeTemporaryRows(db);
        }
    }

    @TckTest
    void ac_wrt_15_a_failed_chunk_commit_lists_that_chunk_keys_as_model_keys_in_in_doubt_keys(TckDatabase db) {
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF))
                .where(TEMPORARY_CUSTOMERS).chunked(ChunkOptions.size(10).commitEachChunk()).build();
        var failingSecondCommit = new ResourceLocal() {
            @Override
            void commit(EntityTransaction transaction) {
                if (chunks == 2) {
                    transaction.rollback();
                    throw new RollbackException("the commit of chunk 2 failed");
                }
                super.commit(transaction);
            }
        };
        var config = ModelQueryConfig.defaults().chunkTransactions(failingSecondCommit);
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            insertCustomers(db, TEMPORARY + 1, TEMPORARY + 30);
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    customers(em, config).delete(delete)));

            assertThat(failure[0]).hasCauseInstanceOf(RollbackException.class).hasMessageContaining("unknown");
            assertThat(failure[0].committedRows()).isEqualTo(10);
            assertThat(failure[0].lastCommittedKey()).contains("#100010");
            assertThat(failure[0].inDoubtKeys()).containsExactlyElementsOf(
                    range(TEMPORARY + 11, TEMPORARY + 20).stream().map(id -> "#" + id).toList());
        } finally {
            removeTemporaryRows(db);
        }
    }

    @TckTest
    void ac_wrt_15_a_where_keys_write_reports_the_last_key_of_the_last_committed_run_in_the_order_given(
            TckDatabase db) {
        // Descending: the database may return a run's keys in any order, the caller's last key is what resumes.
        List<String> keys = range(TEMPORARY + 1, TEMPORARY + 30).stream()
                .sorted(Comparator.reverseOrder()).map(id -> "#" + id).toList();
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF)).whereKeys(keys)
                .chunked(ChunkOptions.size(10).commitEachChunk()).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            insertCustomers(db, TEMPORARY + 1, TEMPORARY + 30);
            // The third run holds customers 10 down to 1: the delete of customer 5 breaks its foreign key.
            insertOrder(db, TEMPORARY + 1, TEMPORARY + 5);
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    customers(em, config).delete(delete)));

            assertThat(failure[0].code()).isEqualTo(MqCode.MQ2502);
            assertThat(failure[0].committedRows()).isEqualTo(20);
            assertThat(failure[0].lastCommittedKey()).contains("#100011");
            assertThat(temporaryCustomers(db)).containsExactlyElementsOf(range(TEMPORARY + 1, TEMPORARY + 10));
        } finally {
            removeTemporaryRows(db);
        }
    }

    @TckTest
    void ac_wrt_15_a_where_keys_run_that_matched_no_row_still_moves_the_last_committed_key(TckDatabase db) {
        var keys = new ArrayList<String>();
        range(TEMPORARY + 1, TEMPORARY + 10).forEach(id -> keys.add("#" + id));
        // The second run names ten customers that do not exist.
        range(TEMPORARY + 101, TEMPORARY + 110).forEach(id -> keys.add("#" + id));
        range(TEMPORARY + 11, TEMPORARY + 20).forEach(id -> keys.add("#" + id));
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF)).whereKeys(keys)
                .chunked(ChunkOptions.size(10).commitEachChunk()).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            insertCustomers(db, TEMPORARY + 1, TEMPORARY + 20);
            insertOrder(db, TEMPORARY + 1, TEMPORARY + 15);
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    customers(em, config).delete(delete)));

            assertThat(failure[0].committedRows()).isEqualTo(10);
            assertThat(failure[0].lastCommittedKey()).contains("#100110");
            assertThat(temporaryCustomers(db)).containsExactlyElementsOf(range(TEMPORARY + 11, TEMPORARY + 20));
        } finally {
            removeTemporaryRows(db);
        }
    }

    @TckTest
    void ac_wrt_15_a_chunk_transactions_that_skips_the_chunk_throws_mq2502_with_the_committed_rows(TckDatabase db) {
        var delete = ModelDelete.builder(CUSTOMERS).primaryKey(PrimaryKey.of(CUSTOMER_REF))
                .where(TEMPORARY_CUSTOMERS).chunked(ChunkOptions.size(10).commitEachChunk()).build();
        var skippingSecond = new ResourceLocal() {
            @Override
            public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
                return chunks == 1 ? null : super.inNewTransaction(emf, chunk);
            }
        };
        var config = ModelQueryConfig.defaults().chunkTransactions(skippingSecond);
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            insertCustomers(db, TEMPORARY + 1, TEMPORARY + 30);
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    customers(em, config).delete(delete)));

            assertThat(failure[0].code()).isEqualTo(MqCode.MQ2502);
            assertThat(failure[0]).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(failure[0].committedRows()).isEqualTo(10);
            assertThat(failure[0].lastCommittedKey()).contains("#100010");
            assertThat(failure[0].inDoubtKeys()).isEmpty();
        } finally {
            removeTemporaryRows(db);
        }
    }

    @TckTest
    void ac_wrt_15_start_after_a_composite_key_resumes_after_it_in_key_order(TckDatabase db) {
        var update = ModelUpdate.builder(TENANT_ITEMS).primaryKey(PrimaryKey.composite(TENANT_ID, ITEM_NO))
                .set(LABEL, "RESUMED").where(f -> f.lt(TENANT_ID, 3))
                .chunked(ChunkOptions.size(3), List.of(1, 98)).build();
        long[] written = new long[1];
        var resumed = new ArrayList<Object[]>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            written[0] = ModelQueryExecutor.create(em, CompositeKeyItemEntity.class, ModelQueryConfig.defaults())
                    .update(update);
            resumed.addAll(em.createQuery("select i.tenantId, i.itemNo from CompositeKeyItemEntity i"
                    + " where i.label = 'RESUMED' order by i.tenantId, i.itemNo", Object[].class).getResultList());
        });

        // (1, 99), (1, 100), then every item of tenant 2.
        assertThat(written[0]).isEqualTo(2 + TckFixture.COMPOSITE_ITEMS_PER_TENANT);
        assertThat(resumed.get(0)).containsExactly(1, 99);
        assertThat(resumed.get(1)).containsExactly(1, 100);
        assertThat(resumed.get(2)).containsExactly(2, 1);
    }

    private static ModelQueryExecutor<CustomerEntity> customers(EntityManager em, ModelQueryConfig config) {
        return ModelQueryExecutor.create(em, CustomerEntity.class, config);
    }

    /** The {@link ChunkedWriteException} {@code write} throws. */
    private static ChunkedWriteException catchChunked(Runnable write) {
        ChunkedWriteException[] thrown = new ChunkedWriteException[1];
        assertThatThrownBy(write::run).isInstanceOfSatisfying(ChunkedWriteException.class, e -> thrown[0] = e);
        return thrown[0];
    }

    /** The key selects of {@code sql}: its selects in key order. */
    private static List<String> keySelects(List<String> sql) {
        return sql.stream().filter(statement -> statement.startsWith("select") && statement.contains(" order by "))
                .toList();
    }

    /** Runs {@code work} on a session of a factory over {@code ds} with no transaction. */
    private static void withoutTransaction(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory factory = JoinTestSupport.sessionFactory(ds)) {
            factory.inSession(work::accept);
        }
    }

    /** The built-in profile of {@code db}'s vendor. */
    private static VendorProfile builtIn(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            return VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile();
        }
    }

    /** {@code db}'s built-in profile with the IN-list and bind-parameter limits given (R-VND-03). */
    private static VendorProfile limited(TckDatabase db, int maxInListSize, int maxBindParameters) {
        VendorProfile builtIn = builtIn(db);
        return new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return builtIn.vendor();
            }

            @Override
            public int maxInListSize() {
                return maxInListSize;
            }

            @Override
            public int maxBindParameters() {
                return maxBindParameters;
            }

            @Override
            public void applyStreaming(Query query, int fetchSize) {
                builtIn.applyStreaming(query, fetchSize);
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
            public boolean targetTableInSubquery() {
                return builtIn.targetTableInSubquery();
            }
        };
    }

    private static List<Long> range(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    /** Commits customers {@code from} to {@code to}, which no order references, on a connection of its own. */
    private static void insertCustomers(TckDatabase db, long from, long to) {
        try (Connection c = db.getConnection(); PreparedStatement insert = c.prepareStatement("insert into customers"
                + " (id, name, email, country, vip, created_at) values (?, ?, ?, 'VN', ?, ?)")) {
            for (long id = from; id <= to; id++) {
                insert.setLong(1, id);
                insert.setString(2, "Temporary " + id);
                insert.setString(3, id + "@test");
                insert.setBoolean(4, false);
                insert.setTimestamp(5, Timestamp.valueOf("2020-01-01 00:00:00"));
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Commits order {@code id} of customer {@code customerId}, on a connection of its own. */
    private static void insertOrder(TckDatabase db, long id, long customerId) {
        try (Connection c = db.getConnection(); PreparedStatement insert = c.prepareStatement("insert into orders"
                + " (id, customer_id, status, total, placed_at) values (?, ?, 'NEW', 0, ?)")) {
            insert.setLong(1, id);
            insert.setLong(2, customerId);
            insert.setTimestamp(3, Timestamp.valueOf("2020-01-01 00:00:00"));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void removeTemporaryRows(TckDatabase db) {
        execute(db, "delete from orders where id > " + TEMPORARY);
        execute(db, "delete from customers where id > " + TEMPORARY);
    }

    private static List<Long> temporaryCustomers(TckDatabase db) {
        return queryLongs(db, "select id from customers where id > " + TEMPORARY + " order by id");
    }

    private static List<Long> queryLongs(TckDatabase db, String sql) {
        var values = new ArrayList<Long>();
        query(db, sql, rows -> values.add(rows.getLong(1)));
        return values;
    }

    private static List<String> queryStrings(TckDatabase db, String sql) {
        var values = new ArrayList<String>();
        query(db, sql, rows -> values.add(rows.getString(1)));
        return values;
    }

    /** Each label of tenant 1's items, by item number. */
    private static Map<Integer, String> tenantOneLabels(TckDatabase db) {
        var labels = new LinkedHashMap<Integer, String>();
        query(db, "select item_no, label from composite_key_items where tenant_id = 1",
                rows -> labels.put(rows.getInt(1), rows.getString(2)));
        return labels;
    }

    private static void restoreTenantOneLabels(TckDatabase db, Map<Integer, String> labels) {
        try (Connection c = db.getConnection(); PreparedStatement restore = c.prepareStatement(
                "update composite_key_items set label = ? where tenant_id = 1 and item_no = ?")) {
            for (Map.Entry<Integer, String> label : labels.entrySet()) {
                restore.setString(1, label.getValue());
                restore.setInt(2, label.getKey());
                restore.addBatch();
            }
            restore.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A row of a JDBC result. */
    private interface RowReader {
        void read(ResultSet rows) throws SQLException;
    }

    /** Runs {@code sql} on a connection of its own, reading each row with {@code reader}. */
    private static void query(TckDatabase db, String sql, RowReader reader) {
        try (Connection c = db.getConnection(); PreparedStatement select = c.prepareStatement(sql);
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                reader.read(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
