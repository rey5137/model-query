package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.execute;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.inRolledBackTransaction;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.recordingEvictions;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PersistenceContextMode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.IdGeneration;
import com.rey.modelquery.tck.col.CompositeKeyCopyEntity;
import com.rey.modelquery.tck.col.CompositeKeyItemEntity;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderArchiveEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.StampedOrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.ins.InsIdentityEntity;
import com.rey.modelquery.tck.vnd.ins.InsSequenceEntity;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.RollbackException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Insert-select on every Tier-1 vendor: the rows the equivalent read returns, joined and to-many source columns
 * included, unchunked and key-first in chunks over the distinct source ids; the overlap refusal, the persistence
 * context and a failed per-chunk insert's source keys (spec api/14 R-WRT-27, R-WRT-28, R-WRT-32, R-WRT-38, D-117).
 */
class InsertSelectTest {

    /** The source model: an order line, read through the order's customer and its items. */
    record Line(Long orderId, Long itemId, Long customerId, String status, String customerName, String productCode) {}

    /** The insert model of {@code order_archive}. */
    record ArchiveRow(Long id, Long orderId, Long customer, String status, String customerName, String productCode) {}

    /** A code and a name: the insert model of a probe root and the source model of {@code ins_source}. */
    record Coded(String code, String name) {}

    /** An insert model of {@code orders}, for a source on the same table. */
    record OrderRow(Long id, String status) {}

    /** An archive row without its customer, which {@code set} writes as a constant. */
    record BareArchiveRow(Long id, Long orderId, String status) {}

    /** An insert model of {@code order_items}, a table the order source joins. */
    record ItemRow(Long id, String productCode) {}

    /** An insert model of {@code customers}, a table the order source's {@code where} joins. */
    record CustomerRow(Long id, String name) {}

    /** A composite-key item: the source model of {@code composite_key_items}, the insert model of its copies. */
    record KeyedItem(Integer tenantId, Integer itemNo, String label) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);

    private static final ColumnField<Line, OrderEntity, Long> LINE_ORDER_ID =
            ColumnField.of(Line.class, ORDERS, "id", Long.class);
    private static final ColumnField<Line, OrderItemEntity, Long> LINE_ITEM_ID =
            ColumnField.of(Line.class, ITEMS, "id", Long.class);
    private static final ColumnField<Line, CustomerEntity, Long> LINE_CUSTOMER_ID =
            ColumnField.of(Line.class, CUSTOMER, "id", Long.class);
    private static final ColumnField<Line, OrderEntity, String> LINE_STATUS =
            ColumnField.of(Line.class, ORDERS, "status", String.class);
    private static final ColumnField<Line, CustomerEntity, String> LINE_CUSTOMER_NAME =
            ColumnField.of(Line.class, CUSTOMER, "name", String.class);
    private static final ColumnField<Line, CustomerEntity, String> LINE_COUNTRY =
            ColumnField.of(Line.class, CUSTOMER, "country", String.class);
    private static final ColumnField<Line, OrderItemEntity, String> LINE_PRODUCT =
            ColumnField.of(Line.class, ITEMS, "productCode", String.class);

    private static final TableField<OrderArchiveEntity, OrderArchiveEntity> ARCHIVE =
            TableField.root(OrderArchiveEntity.class);
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, Long> ARCHIVE_ID =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "id", Long.class);
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, Long> ARCHIVE_ORDER_ID =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "orderId", Long.class);
    /** The to-one {@code customer}, written by id. */
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, Long> ARCHIVE_CUSTOMER =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "customer", Long.class);
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, String> ARCHIVE_STATUS =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "status", String.class);
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, String> ARCHIVE_CUSTOMER_NAME =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "customerName", String.class);
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, String> ARCHIVE_PRODUCT =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "productCode", String.class);
    /** A column the model leaves out, which {@code set} writes. */
    private static final ColumnField<OrderArchiveEntity, OrderArchiveEntity, String> ARCHIVED_BY =
            ColumnField.of(OrderArchiveEntity.class, ARCHIVE, "archivedBy", String.class);

    private static final TableField<CompositeKeyItemEntity, CompositeKeyItemEntity> KEY_ITEMS =
            TableField.root(CompositeKeyItemEntity.class);
    private static final TableField<CompositeKeyCopyEntity, CompositeKeyCopyEntity> COPIES =
            TableField.root(CompositeKeyCopyEntity.class);

    private static final InsertColumns<ArchiveRow, OrderArchiveEntity> ARCHIVE_COLUMNS =
            InsertColumns.<ArchiveRow, OrderArchiveEntity>of(ARCHIVE)
                    .addKey(ARCHIVE_ID, ArchiveRow::id)
                    .add(ARCHIVE_ORDER_ID, ArchiveRow::orderId)
                    .add(ARCHIVE_CUSTOMER, ArchiveRow::customer)
                    .add(ARCHIVE_STATUS, ArchiveRow::status)
                    .add(ARCHIVE_CUSTOMER_NAME, ArchiveRow::customerName)
                    .add(ARCHIVE_PRODUCT, ArchiveRow::productCode);

    /** 13 paid orders of German customers, orders 1 to 97, with 4 items each. */
    private static final UnaryOperator<Filters<Line>> PAID_IN_DE =
            f -> f.eq(LINE_STATUS, "PAID").eq(LINE_COUNTRY, "DE").lt(LINE_ORDER_ID, 101L);
    /** The same choice over orders 101 to 199, which share no item with {@link #PAID_IN_DE}. */
    private static final UnaryOperator<Filters<Line>> LATER_PAID_IN_DE =
            f -> f.eq(LINE_STATUS, "PAID").eq(LINE_COUNTRY, "DE").gt(LINE_ORDER_ID, 100L).lt(LINE_ORDER_ID, 200L);

    // ---- AC-WRT-21

    @TckTest
    void ac_wrt_21_an_insert_select_writes_the_rows_the_list_returns_with_joined_and_to_many_columns(TckDatabase db) {
        List<String> expected = read(db, PAID_IN_DE);
        long[] written = new long[1];
        var archived = new ArrayList<String>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = archives(em).insert(archive(PAID_IN_DE).build());
            archived.addAll(archived(em));
        }));

        // A to-many source column multiplies the rows, as on the read path: 13 orders, 4 items each.
        assertThat(expected).hasSize(52);
        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(archived).containsExactlyInAnyOrderElementsOf(expected);
        SqlSnapshots.assertMatches(db, "wrt-21-insert-select", writes(sql, "insert"));
    }

    @TckTest
    void ac_wrt_21_a_chunked_insert_select_over_a_separate_target_writes_each_source_row_once(TckDatabase db) {
        List<String> expected = read(db, PAID_IN_DE);
        long[] written = new long[1];
        var archived = new ArrayList<String>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = archives(em).insert(archive(PAID_IN_DE).chunked(ChunkOptions.size(5)).build());
            archived.addAll(archived(em));
        }));

        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(archived).containsExactlyInAnyOrderElementsOf(expected);
        // Key-first over the 13 distinct source ids in chunks of 5: three key selects, three inserts.
        assertThat(sql.stream().filter(statement -> statement.startsWith("select")
                && statement.contains(" order by ")).toList()).hasSize(3);
        List<String> inserts = writes(sql, "insert");
        assertThat(inserts).hasSize(3);
        SqlSnapshots.assertMatches(db, "wrt-21-chunked-insert-select", inserts.subList(0, 1));
    }

    @TckTest
    void ac_wrt_21_an_insert_select_writes_a_to_one_set_constant_by_id(TckDatabase db) {
        var id = ColumnField.of(BareArchiveRow.class, ARCHIVE, "id", Long.class);
        var orderId = ColumnField.of(BareArchiveRow.class, ARCHIVE, "orderId", Long.class);
        var status = ColumnField.of(BareArchiveRow.class, ARCHIVE, "status", String.class);
        var customer = ColumnField.of(OrderArchiveEntity.class, ARCHIVE, "customer", Long.class);
        var insert = ModelInsert.select(InsertColumns.<BareArchiveRow, OrderArchiveEntity>of(ARCHIVE)
                        .addKey(id, BareArchiveRow::id).add(orderId, BareArchiveRow::orderId)
                        .add(status, BareArchiveRow::status), ORDERS)
                .map(id, LINE_ITEM_ID).map(orderId, LINE_ORDER_ID).map(status, LINE_STATUS)
                .set(customer, 3L)
                .where(PAID_IN_DE).build();
        long[] written = new long[1];
        var customers = new ArrayList<Long>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            written[0] = archives(em).insert(insert);
            customers.addAll(em.createQuery("select a.customer.id from OrderArchiveEntity a", Long.class)
                    .getResultList());
        });

        assertThat(written[0]).isEqualTo(52);
        assertThat(customers).hasSize(52).containsOnly(3L);
    }

    @TckTest
    void ac_wrt_21_a_chunked_insert_select_filtering_on_a_to_many_join_writes_each_source_row_once(TckDatabase db) {
        UnaryOperator<Filters<Line>> where = f -> PAID_IN_DE.apply(f).gt(LINE_PRODUCT, "P010");
        List<String> expected = read(db, where);
        long[] written = new long[1];
        var archived = new ArrayList<String>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            written[0] = archives(em).insert(archive(where).chunked(ChunkOptions.size(5)).build());
            archived.addAll(archived(em));
        });

        // The key select repeats an order once per matching item, across a chunk's end too; each order is written
        // once, with every item the read returns for it.
        assertThat(expected.size()).isGreaterThan(orderIds(expected).size());
        assertThat(written[0]).isEqualTo(expected.size());
        assertThat(archived).containsExactlyInAnyOrderElementsOf(expected);
    }

    @TckTest
    void ac_wrt_21_a_chunked_insert_select_pages_over_a_composite_source_id_ordered_by_component_name(
            TckDatabase db) {
        long[] written = new long[1];
        var copied = new ArrayList<String>();
        var expected = new ArrayList<String>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            expected.addAll(rowsOf(em, "select i.tenantId, i.itemNo, i.label from CompositeKeyItemEntity i"
                    + " where i.itemNo <= 3"));
            written[0] = ModelQueryExecutor.create(em, CompositeKeyCopyEntity.class, ModelQueryConfig.defaults())
                    .insert(copyFirstItems(ChunkOptions.size(7)));
            copied.addAll(rowsOf(em, "select c.tenantId, c.itemNo, c.label from CompositeKeyCopyEntity c"));
        }));

        assertThat(expected).hasSize(60);
        assertThat(written[0]).isEqualTo(60);
        assertThat(copied).containsExactlyInAnyOrderElementsOf(expected);
        // 60 keys in chunks of 7: eight full key selects and a ninth of 4, each followed by its insert.
        assertThat(writes(sql, "insert")).hasSize(9);
        List<String> keySelects = sql.stream().filter(statement -> statement.startsWith("select")
                && statement.contains(" order by ")).toList();
        // The key select reads, and orders by, the item number before the tenant.
        assertThat(keySelects).hasSize(9).allSatisfy(statement -> assertThat(statement.indexOf("item_no"))
                .isNotNegative().isLessThan(statement.indexOf("tenant_id")));
    }

    @TckTest
    void ac_wrt_21_a_chunked_insert_select_whose_select_joins_its_target_throws_mq1806_before_the_flush(
            TckDatabase db) {
        // Items written from orders through their to-many items: a row a chunk writes could join a later order
        TableField<OrderItemEntity, OrderItemEntity> items = TableField.root(OrderItemEntity.class);
        var itemId = ColumnField.of(ItemRow.class, items, "id", Long.class);
        var product = ColumnField.of(ItemRow.class, items, "productCode", String.class);
        var intoItems = ModelInsert.select(InsertColumns.<ItemRow, OrderItemEntity>of(items)
                        .addKey(itemId, ItemRow::id).add(product, ItemRow::productCode), ORDERS)
                .map(itemId, LINE_ITEM_ID).map(product, LINE_PRODUCT)
                .where(PAID_IN_DE).chunked(ChunkOptions.size(10)).build();
        // Customers written from orders whose where joins their customer, a to-one
        TableField<CustomerEntity, CustomerEntity> customers = TableField.root(CustomerEntity.class);
        var customerId = ColumnField.of(CustomerRow.class, customers, "id", Long.class);
        var name = ColumnField.of(CustomerRow.class, customers, "name", String.class);
        var intoCustomers = ModelInsert.select(InsertColumns.<CustomerRow, CustomerEntity>of(customers)
                        .addKey(customerId, CustomerRow::id).add(name, CustomerRow::name), ORDERS)
                .map(customerId, LINE_ORDER_ID).map(name, LINE_STATUS)
                .where(PAID_IN_DE).chunked(ChunkOptions.size(10)).build();

        assertRefusedBeforeTheFlush(db, OrderItemEntity.class, executor -> executor.insert(intoItems),
                "reads OrderItemEntity, of the target's own entity hierarchy");
        assertRefusedBeforeTheFlush(db, CustomerEntity.class, executor -> executor.insert(intoCustomers),
                "reads CustomerEntity, of the target's own entity hierarchy");
    }

    @TckTest
    void ac_wrt_21_a_chunked_insert_select_over_its_own_entity_or_a_shared_table_throws_mq1806_before_the_flush(
            TckDatabase db) {
        // Archive rows copied into the archive: the same entity
        TableField<OrderArchiveEntity, CustomerEntity> archivedFor = TableField.join(ARCHIVE, "customer", LEFT);
        var self = ModelInsert.select(ARCHIVE_COLUMNS, ARCHIVE).map(ARCHIVE_ID, ARCHIVE_ID)
                .map(ARCHIVE_ORDER_ID, ARCHIVE_ORDER_ID)
                .map(ARCHIVE_CUSTOMER, ColumnField.of(ArchiveRow.class, archivedFor, "id", Long.class))
                .map(ARCHIVE_STATUS, ARCHIVE_STATUS).map(ARCHIVE_CUSTOMER_NAME, ARCHIVE_CUSTOMER_NAME)
                .map(ARCHIVE_PRODUCT, ARCHIVE_PRODUCT).all().chunked(ChunkOptions.size(10)).build();
        // Orders copied from StampedOrderEntity, a second entity on the orders table
        TableField<StampedOrderEntity, StampedOrderEntity> stamped = TableField.root(StampedOrderEntity.class);
        var onOrders = ModelInsert.select(orderColumns(), stamped)
                .map(ColumnField.of(OrderRow.class, ORDERS, "id", Long.class),
                        ColumnField.of(OrderRow.class, stamped, "id", Long.class))
                .map(ColumnField.of(OrderRow.class, ORDERS, "status", String.class),
                        ColumnField.of(OrderRow.class, stamped, "status", String.class))
                .all().chunked(ChunkOptions.size(10)).build();

        assertRefusedBeforeTheFlush(db, OrderArchiveEntity.class, executor -> executor.insert(self),
                "reads OrderArchiveEntity, of the target's own entity hierarchy");
        assertRefusedBeforeTheFlush(db, OrderEntity.class, executor -> executor.insert(onOrders),
                "reads StampedOrderEntity, which shares the table orders with the target");
    }

    @TckTest
    void ac_wrt_21_a_chunked_insert_select_whose_provider_names_no_tables_throws_mq1806(TckDatabase db) {
        var chunked = archive(PAID_IN_DE).chunked(ChunkOptions.size(5)).build();

        NoTablesProviderSupport.serving(() -> assertRefusedBeforeTheFlush(db, OrderArchiveEntity.class,
                executor -> executor.insert(chunked), "the provider names no tables for OrderArchiveEntity"));
    }

    @TckTest
    void ac_wrt_21_an_insert_select_writes_identity_and_database_sequence_ids_the_database_generates(
            TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertThat(copySources(p, InsIdentityEntity.class)).containsExactly("s1", "s2", "s3");
            // MySQL backs a sequence with a table, which an insert-select refuses (MQ1805, AC-WRT-29)
            var id = (IdGeneration.Sequence) new HibernateProviderSupport().inserts().orElseThrow()
                    .target(p.factory(), InsSequenceEntity.class).id();
            if (id.physical()) {
                assertThat(copySources(p, InsSequenceEntity.class)).containsExactly("s1", "s2", "s3");
            }
        }
    }

    // ---- AC-WRT-27

    @TckTest
    void ac_wrt_27_an_insert_select_flushes_first_clears_by_default_keeps_with_keep_and_evicts_the_root(
            TckDatabase db) {
        var managed = new ArrayList<Boolean>();
        var evicted = new ArrayList<Object>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            em.setFlushMode(FlushModeType.COMMIT);
            CustomerEntity customer = em.find(CustomerEntity.class, 7L);
            customer.rename("Flushed first");
            EntityManager recording = recordingEvictions(em, evicted);
            archives(recording).insert(archive(PAID_IN_DE).build());
            managed.add(em.contains(customer));
            customer = em.find(CustomerEntity.class, 7L);
            archives(recording).insert(archive(LATER_PAID_IN_DE).persistenceContext(PersistenceContextMode.KEEP)
                    .build());
            managed.add(em.contains(customer));
        }));

        assertThat(managed).containsExactly(false, true);
        assertThat(evicted).containsExactly(OrderArchiveEntity.class, OrderArchiveEntity.class);
        List<String> written = sql.stream().filter(s -> s.startsWith("update") || s.startsWith("insert")).toList();
        assertThat(written).hasSize(3);
        assertThat(written.get(0)).startsWith("update customers");
        assertThat(written.subList(1, 3)).allMatch(s -> s.startsWith("insert into order_archive"));
    }

    // ---- AC-WRT-26

    @TckTest
    void ac_wrt_26_a_per_chunk_insert_select_whose_third_chunk_fails_reports_source_keys(TckDatabase db) {
        List<Long> orders = orderIds(read(db, PAID_IN_DE));
        var insert = archive(PAID_IN_DE).chunked(ChunkOptions.size(5).commitEachChunk()).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new ResourceLocal());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            // An archive row already holding the id of an item of the 11th order: the third chunk's insert fails.
            execute(db, "insert into order_archive (id, order_id, status, version) values (" + itemOf(orders.get(10))
                    + ", 0, 'X', 0)");
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    ModelQueryExecutor.create(em, OrderArchiveEntity.class, config).insert(insert)));

            assertThat(failure[0].code()).isEqualTo(MqCode.MQ2502);
            assertThat(failure[0]).hasCauseInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("the 2 chunks before it stay committed, 40 rows");
            assertThat(failure[0].committedRows()).isEqualTo(40);
            assertThat(failure[0].lastCommittedKey()).contains(orders.get(9));
            assertThat(failure[0].inDoubtKeys()).isEmpty();
            assertThat(failure[0].nextRowIndex()).isEmpty();
            assertThat(archivedOrders(db)).containsExactlyElementsOf(orders.subList(0, 10));
        } finally {
            execute(db, "delete from order_archive");
        }
    }

    @TckTest
    void ac_wrt_26_a_failed_chunk_commit_lists_that_chunk_source_keys_in_in_doubt_keys(TckDatabase db) {
        List<Long> orders = orderIds(read(db, PAID_IN_DE));
        var insert = archive(PAID_IN_DE).chunked(ChunkOptions.size(5).commitEachChunk()).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(failingSecondCommit());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    ModelQueryExecutor.create(em, OrderArchiveEntity.class, config).insert(insert)));

            assertThat(failure[0]).hasCauseInstanceOf(RollbackException.class).hasMessageContaining("unknown");
            assertThat(failure[0].committedRows()).isEqualTo(20);
            assertThat(failure[0].lastCommittedKey()).contains(orders.get(4));
            assertThat(failure[0].inDoubtKeys()).containsExactlyElementsOf(orders.subList(5, 10));
        } finally {
            execute(db, "delete from order_archive");
        }
    }

    @TckTest
    void ac_wrt_26_a_failed_chunk_commit_over_a_composite_source_id_reports_lists_of_its_components(TckDatabase db) {
        var config = ModelQueryConfig.defaults().chunkTransactions(failingSecondCommit());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    ModelQueryExecutor.create(em, CompositeKeyCopyEntity.class, config)
                            .insert(copyFirstItems(ChunkOptions.size(7).commitEachChunk()))));

            // Keys run by item number, then tenant: the first chunk is item 1 of tenants 1 to 7, the second 8 to 14.
            assertThat(failure[0]).hasCauseInstanceOf(RollbackException.class);
            assertThat(failure[0].committedRows()).isEqualTo(7);
            assertThat(failure[0].lastCommittedKey()).contains(List.of(1, 7));
            assertThat(failure[0].inDoubtKeys()).containsExactlyElementsOf(IntStream.rangeClosed(8, 14)
                    .mapToObj(tenant -> List.of(1, tenant)).toList());
        } finally {
            execute(db, "delete from composite_key_copies");
        }
    }

    /** Chunks as {@link ResourceLocal} runs them, but the second chunk's commit rolls back and throws. */
    private static ResourceLocal failingSecondCommit() {
        return new ResourceLocal() {
            @Override
            void commit(EntityTransaction transaction) {
                if (chunks == 2) {
                    transaction.rollback();
                    throw new RollbackException("the commit of chunk 2 failed");
                }
                super.commit(transaction);
            }
        };
    }

    /** Each chunk on a new resource-local {@code EntityManager}, committed unless {@link #commit} throws. */
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

    /** The archive of the orders {@code where} chooses: every column mapped, and {@code archivedBy} set. */
    private static ModelInsert.SelectOptions<OrderArchiveEntity, ArchiveRow> archive(
            UnaryOperator<Filters<Line>> where) {
        return ModelInsert.select(ARCHIVE_COLUMNS, ORDERS)
                .map(ARCHIVE_ID, LINE_ITEM_ID)
                .map(ARCHIVE_ORDER_ID, LINE_ORDER_ID)
                .map(ARCHIVE_CUSTOMER, LINE_CUSTOMER_ID)
                .map(ARCHIVE_STATUS, LINE_STATUS)
                .map(ARCHIVE_CUSTOMER_NAME, LINE_CUSTOMER_NAME)
                .map(ARCHIVE_PRODUCT, LINE_PRODUCT)
                .set(ARCHIVED_BY, "tck")
                .where(where);
    }

    /**
     * The lines {@code where} chooses, as the read path's {@code list} returns them, each as an archive row reads
     * back: {@code itemId|orderId|customerId|status|customerName|productCode|tck|0}.
     */
    private static List<String> read(TckDatabase db, UnaryOperator<Filters<Line>> where) {
        var query = ModelQuery.builder(ORDERS, row -> new Line(row.get(LINE_ORDER_ID), row.get(LINE_ITEM_ID),
                row.get(LINE_CUSTOMER_ID), row.get(LINE_STATUS), row.get(LINE_CUSTOMER_NAME), row.get(LINE_PRODUCT)))
                .select(SelectSet.of(LINE_ORDER_ID, LINE_ITEM_ID, LINE_CUSTOMER_ID, LINE_STATUS, LINE_CUSTOMER_NAME,
                        LINE_PRODUCT))
                .where(where).build();
        var lines = new ArrayList<String>();
        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> ModelQueryExecutor.create(em,
                OrderEntity.class, ModelQueryConfig.defaults()).list(query, Limit.unlimited()).forEach(line ->
                        lines.add(String.join("|", String.valueOf(line.itemId()), String.valueOf(line.orderId()),
                                String.valueOf(line.customerId()), line.status(), line.customerName(),
                                line.productCode(), "tck", "0"))));
        return lines;
    }

    /**
     * Copies items 1 to 3 of every tenant, 60 rows, from {@code composite_key_items} into {@code composite_key_copies},
     * keeping their two-column id.
     */
    private static ModelInsert<CompositeKeyCopyEntity, KeyedItem> copyFirstItems(ChunkOptions chunks) {
        var tenant = ColumnField.of(KeyedItem.class, COPIES, "tenantId", Integer.class);
        var itemNo = ColumnField.of(KeyedItem.class, COPIES, "itemNo", Integer.class);
        var label = ColumnField.of(KeyedItem.class, COPIES, "label", String.class);
        var sourceItemNo = ColumnField.of(KeyedItem.class, KEY_ITEMS, "itemNo", Integer.class);
        return ModelInsert.select(InsertColumns.<KeyedItem, CompositeKeyCopyEntity>of(COPIES)
                        .addKey(tenant, KeyedItem::tenantId).addKey(itemNo, KeyedItem::itemNo)
                        .add(label, KeyedItem::label), KEY_ITEMS)
                .map(tenant, ColumnField.of(KeyedItem.class, KEY_ITEMS, "tenantId", Integer.class))
                .map(itemNo, sourceItemNo)
                .map(label, ColumnField.of(KeyedItem.class, KEY_ITEMS, "label", String.class))
                .where(f -> f.lte(sourceItemNo, 3))
                .chunked(chunks).build();
    }

    /** The rows of {@code jpql}, each as its values joined with {@code |}. */
    private static List<String> rowsOf(EntityManager em, String jpql) {
        return em.createQuery(jpql, Object[].class).getResultList().stream()
                .map(row -> String.join("|", Arrays.stream(row).map(String::valueOf).toList()))
                .toList();
    }

    /** The archive rows, read back as {@link #read} formats a line. */
    private static List<String> archived(EntityManager em) {
        return em.createQuery("select a.id, a.orderId, a.customer.id, a.status, a.customerName, a.productCode,"
                + " a.archivedBy, a.version from OrderArchiveEntity a", Object[].class).getResultList().stream()
                .map(row -> String.join("|", Arrays.stream(row).map(String::valueOf).toList()))
                .toList();
    }

    /** The distinct order ids of {@link #read}'s lines, in order. */
    private static List<Long> orderIds(List<String> lines) {
        var ids = new LinkedHashSet<Long>();
        lines.forEach(line -> ids.add(Long.valueOf(line.split("\\|")[1])));
        return ids.stream().sorted().toList();
    }

    /** An item id of {@code order}, as the fixture seeds them: item {@code i} belongs to order {@code 7(i-1)+1}. */
    private static long itemOf(long order) {
        for (long item = 1; item <= 20_000; item++) {
            if ((item - 1) * 7 % 5_000 + 1 == order) {
                return item;
            }
        }
        throw new IllegalArgumentException("order " + order + " has no item");
    }

    /** The orders the archive holds rows of, distinct and in order, read over plain JDBC. */
    private static List<Long> archivedOrders(TckDatabase db) {
        var orders = new ArrayList<Long>();
        try (var c = db.getConnection(); var statement = c.createStatement();
                var rs = statement.executeQuery("select distinct order_id from order_archive where order_id > 0"
                        + " order by order_id")) {
            while (rs.next()) {
                orders.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return orders;
    }

    private static ModelQueryExecutor<OrderArchiveEntity> archives(EntityManager em) {
        return ModelQueryExecutor.create(em, OrderArchiveEntity.class, ModelQueryConfig.defaults());
    }

    /** The insert columns of an {@link OrderRow}. */
    private static InsertColumns<OrderRow, OrderEntity> orderColumns() {
        return InsertColumns.<OrderRow, OrderEntity>of(ORDERS)
                .addKey(ColumnField.of(OrderRow.class, ORDERS, "id", Long.class), OrderRow::id)
                .add(ColumnField.of(OrderRow.class, ORDERS, "status", String.class), OrderRow::status);
    }

    /**
     * Copies {@code ins_source}'s code and name into {@code root}, which generates its ids, in a rolled-back
     * transaction, and returns the codes written, after checking that each row got its own id.
     */
    private static <E> List<String> copySources(InsertProbes p, Class<E> root) {
        TableField<InsSourceEntity, InsSourceEntity> source = TableField.root(InsSourceEntity.class);
        TableField<E, E> target = TableField.root(root);
        var code = ColumnField.of(Coded.class, target, "code", String.class);
        var name = ColumnField.of(Coded.class, target, "name", String.class);
        var insert = ModelInsert.select(InsertColumns.<Coded, E>of(target).add(code, Coded::code)
                        .add(name, Coded::name), source)
                .map(code, ColumnField.of(Coded.class, source, "code", String.class))
                .map(name, ColumnField.of(Coded.class, source, "name", String.class))
                .all().build();
        var codes = new ArrayList<String>();
        p.factory().inSession(em -> {
            em.getTransaction().begin();
            try {
                assertThat(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults()).insert(insert))
                        .isEqualTo(3);
                String entity = root.getSimpleName();
                codes.addAll(em.createQuery("select e.code from " + entity + " e order by e.code", String.class)
                        .getResultList());
                assertThat(em.createQuery("select count(distinct e.id) from " + entity + " e", Long.class)
                        .getSingleResult()).isEqualTo(3);
            } finally {
                em.getTransaction().rollback();
            }
        });
        return codes;
    }

    /**
     * Runs {@code call} on an executor of {@code root} in a rolled-back transaction with a pending rename only a flush
     * would write, and asserts that it throws {@code MQ1806} with {@code message} and that nothing was written.
     */
    private static <E> void assertRefusedBeforeTheFlush(TckDatabase db, Class<E> root,
            Consumer<ModelQueryExecutor<E>> call, String message) {
        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            em.setFlushMode(FlushModeType.COMMIT);
            em.find(CustomerEntity.class, 7L).rename("Not flushed");
            var executor = ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults());
            assertThatThrownBy(() -> call.accept(executor))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1806))
                    .hasMessageContaining(message);
        }));
        assertThat(writes(sql, "update")).isEmpty();
        assertThat(writes(sql, "insert")).isEmpty();
    }

    /** The {@link ChunkedWriteException} {@code write} throws. */
    private static ChunkedWriteException catchChunked(Runnable write) {
        ChunkedWriteException[] thrown = new ChunkedWriteException[1];
        assertThatThrownBy(write::run).isInstanceOfSatisfying(ChunkedWriteException.class, e -> thrown[0] = e);
        return thrown[0];
    }

    /** Runs {@code work} on a session of a factory over {@code ds} with no transaction. */
    private static void withoutTransaction(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory factory = JoinTestSupport.sessionFactory(ds)) {
            factory.inSession(work::accept);
        }
    }
}
