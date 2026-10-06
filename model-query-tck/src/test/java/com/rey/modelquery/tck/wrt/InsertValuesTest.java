package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.execute;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.inRolledBackTransaction;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.recordingEvictions;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static com.rey.modelquery.tck.wrt.InsertSelectTest.catchChunked;
import static com.rey.modelquery.tck.wrt.InsertSelectTest.failingSecondCommit;
import static com.rey.modelquery.tck.wrt.InsertSelectTest.withoutTransaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.PersistenceContextMode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.KeysetTypeEntity;
import com.rey.modelquery.tck.col.OrderArchiveEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.ins.InsIdentityEntity;
import com.rey.modelquery.tck.vnd.ins.InsPooledEntity;
import com.rey.modelquery.tck.vnd.ins.InsSequenceEntity;
import com.rey.modelquery.tck.vnd.ins.InsTableEntity;
import com.rey.modelquery.tck.vnd.ins.InsUuidEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Insert-values on every Tier-1 vendor: rows round-tripping through converters, a to-one by id, a {@code set}
 * constant and each supported generator; keys drawn first and returned in row order; rows per statement within the
 * bind and {@code VALUES} limits, the version seed counted; a failed per-chunk insert's row positions; and the
 * persistence context (spec api/14 R-WRT-26, R-WRT-29, R-WRT-30, R-WRT-32, R-WRT-33, R-WRT-38, D-117).
 */
class InsertValuesTest {

    /** An archive row as the caller builds it: the order reference as {@code #42}, the customer by id. */
    record NewArchive(Long id, String orderRef, Long customer, String status, String customerName) {}

    /** An archive row of three columns. */
    record BareArchive(Long id, Long orderId, String status) {}

    /** A {@code keyset_types} row: a JDBC-typed UUID and a JPA-converted value class among its columns. */
    record TypedRow(Long id, Integer tie, BigDecimal amount, Timestamp stamp, UUID token, byte[] payload,
            KeysetTypeEntity.Shape shape) {}

    /** A probe row; the root generates its id. */
    record Coded(String code, String name) {}

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

    private static final TableField<OrderArchiveEntity, OrderArchiveEntity> ARCHIVE =
            TableField.root(OrderArchiveEntity.class);
    /** A column the models leave out, which {@code set} writes. */
    private static final ColumnField<OrderArchiveEntity, OrderArchiveEntity, String> ARCHIVED_BY =
            ColumnField.of(OrderArchiveEntity.class, ARCHIVE, "archivedBy", String.class);

    private static final InsertColumns<NewArchive, OrderArchiveEntity> NEW_ARCHIVE =
            InsertColumns.<NewArchive, OrderArchiveEntity>of(ARCHIVE)
                    .addKey(ColumnField.of(NewArchive.class, ARCHIVE, "id", Long.class), NewArchive::id)
                    .add(ColumnField.of(NewArchive.class, ARCHIVE, "orderId", String.class, Long.class,
                            RefConverter.INSTANCE), NewArchive::orderRef)
                    .add(ColumnField.of(NewArchive.class, ARCHIVE, "customer", Long.class), NewArchive::customer)
                    .add(ColumnField.of(NewArchive.class, ARCHIVE, "status", String.class), NewArchive::status)
                    .add(ColumnField.of(NewArchive.class, ARCHIVE, "customerName", String.class),
                            NewArchive::customerName);

    private static final InsertColumns<BareArchive, OrderArchiveEntity> BARE_ARCHIVE =
            InsertColumns.<BareArchive, OrderArchiveEntity>of(ARCHIVE)
                    .addKey(ColumnField.of(BareArchive.class, ARCHIVE, "id", Long.class), BareArchive::id)
                    .add(ColumnField.of(BareArchive.class, ARCHIVE, "orderId", Long.class), BareArchive::orderId)
                    .add(ColumnField.of(BareArchive.class, ARCHIVE, "status", String.class), BareArchive::status);

    private static final List<Coded> CODED = List.of(new Coded("c1", "N1"), new Coded("c2", "N2"),
            new Coded("c3", "N3"));

    // ---- AC-WRT-22

    @TckTest
    void ac_wrt_22_insert_values_with_an_assigned_id_a_converter_a_to_one_by_id_and_a_set_constant_round_trips(
            TckDatabase db) {
        var rows = List.of(new NewArchive(9001L, "#42", 3L, "PAID", "Ann"),
                new NewArchive(9002L, "#43", 7L, "NEW", null),
                new NewArchive(9003L, "#44", 3L, "SHIPPED", "Bea"));
        var insert = ValuesInsert.builder(NEW_ARCHIVE, Long.class, rows).set(ARCHIVED_BY, "tck").build();
        long[] written = new long[1];
        var archived = new ArrayList<String>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = archives(em, ModelQueryConfig.defaults()).insert(insert);
            archived.addAll(archived(em));
        }));

        assertThat(written[0]).isEqualTo(3);
        // The converter writes 42 for #42, the customer goes in by id, a null writes NULL, the version is seeded.
        assertThat(archived).containsExactly("9001|42|3|PAID|Ann|tck|0", "9002|43|7|NEW|null|tck|0",
                "9003|44|3|SHIPPED|Bea|tck|0");
        SqlSnapshots.assertMatches(db, "wrt-22-insert-values", writes(sql, "insert"));
    }

    @TckTest
    void ac_wrt_22_insert_values_binds_jpa_converted_and_jdbc_typed_attributes_through_their_mapping(TckDatabase db) {
        TableField<KeysetTypeEntity, KeysetTypeEntity> types = TableField.root(KeysetTypeEntity.class);
        var columns = InsertColumns.<TypedRow, KeysetTypeEntity>of(types)
                .addKey(ColumnField.of(TypedRow.class, types, "id", Long.class), TypedRow::id)
                .add(ColumnField.of(TypedRow.class, types, "tie", Integer.class), TypedRow::tie)
                .add(ColumnField.of(TypedRow.class, types, "amount", BigDecimal.class), TypedRow::amount)
                .add(ColumnField.of(TypedRow.class, types, "stamp", Timestamp.class), TypedRow::stamp)
                .add(ColumnField.of(TypedRow.class, types, "token", UUID.class), TypedRow::token)
                .add(ColumnField.of(TypedRow.class, types, "payload", byte[].class), TypedRow::payload)
                .add(ColumnField.of(TypedRow.class, types, "shape", KeysetTypeEntity.Shape.class), TypedRow::shape);
        UUID token = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
        var rows = List.of(new TypedRow(9001L, 1, new BigDecimal("1.5000"), new Timestamp(0), token,
                new byte[] {1, 2}, new KeysetTypeEntity.Shape("circle")));
        var stored = new ArrayList<String>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            assertThat(ModelQueryExecutor.create(em, KeysetTypeEntity.class, ModelQueryConfig.defaults())
                    .insert(ValuesInsert.builder(columns, Long.class, rows).build())).isEqualTo(1);
            Object[] row = (Object[]) em.createNativeQuery("select token, shape from keyset_types where id = 9001")
                    .getSingleResult();
            stored.add(row[0] + "|" + row[1]);
        });

        // Bound as the attribute's type: the UUID as its VARCHAR text, the Shape through its AttributeConverter.
        assertThat(stored).containsExactly(token + "|circle");
    }

    @TckTest
    void ac_wrt_22_insert_values_with_an_identity_or_a_pooled_sequence_id_round_trips(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertRoundTrips(p, InsIdentityEntity.class);
            assertRoundTrips(p, InsPooledEntity.class);
        }
    }

    // ---- AC-WRT-23

    @TckTest
    void ac_wrt_23_insert_returning_keys_returns_drawn_keys_that_read_back_each_row_by_index(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertKeysReadBack(p, InsPooledEntity.class, Long.class);
            assertKeysReadBack(p, InsSequenceEntity.class, Long.class);
            assertKeysReadBack(p, InsTableEntity.class, Long.class);
            assertKeysReadBack(p, InsUuidEntity.class, UUID.class);
        }
    }

    // ---- AC-WRT-24

    @TckTest
    void ac_wrt_24_rows_per_statement_stay_within_the_bind_limit_counting_the_version_seed(TckDatabase db) {
        // Three columns, the set constant and the version seed: 5 binds a row, so 12 binds take 2 rows, not 3.
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 12, Integer.MAX_VALUE)));
        var insert = ValuesInsert.builder(BARE_ARCHIVE, Long.class, bareRows(5)).set(ARCHIVED_BY, "tck").build();
        long[] written = new long[1];
        var archived = new ArrayList<String>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = archives(em, config).insert(insert);
            archived.addAll(archived(em));
        }));

        assertThat(written[0]).isEqualTo(5);
        assertThat(archived).hasSize(5).allMatch(row -> row.endsWith("|tck|0"));
        List<String> inserts = writes(sql, "insert");
        assertThat(inserts).hasSize(3).allSatisfy(statement -> assertThat(BulkWriteTest.binds(statement))
                .isLessThanOrEqualTo(12));
        SqlSnapshots.assertMatches(db, "wrt-24-insert-values-split", inserts);
    }

    @TckTest
    void ac_wrt_24_rows_per_statement_stay_within_the_values_row_limit_and_the_chunk_size(TckDatabase db) {
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 65_535, 3)));
        var rows = bareRows(5);
        var statements = new ArrayList<Integer>();

        for (ChunkOptions chunk : List.of(ChunkOptions.defaultSize(), ChunkOptions.size(2))) {
            List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> assertThat(
                    archives(em, config).insert(ValuesInsert.builder(BARE_ARCHIVE, Long.class, rows)
                            .chunked(chunk).build())).isEqualTo(5)));
            statements.add(writes(sql, "insert").size());
        }

        // Three rows a statement within the profile's VALUES limit, then two within the chunk size.
        assertThat(statements).containsExactly(2, 3);
    }

    @TckTest
    void ac_wrt_24_an_empty_list_runs_no_sql_and_returns_zero_or_no_keys(TckDatabase db) {
        long[] written = new long[1];

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            // A pending rename only a flush would write.
            em.setFlushMode(FlushModeType.COMMIT);
            em.find(CustomerEntity.class, 7L).rename("Not flushed");
            written[0] = archives(em, ModelQueryConfig.defaults())
                    .insert(ValuesInsert.builder(BARE_ARCHIVE, Long.class, List.of()).build());
        }));

        assertThat(written[0]).isZero();
        assertThat(sql).noneMatch(statement -> statement.startsWith("insert") || statement.startsWith("update"));
        try (InsertProbes p = InsertProbes.open(db)) {
            inProbeTransaction(p, em -> {
                p.forget();
                assertThat(executor(em, InsPooledEntity.class).insertReturningKeys(
                        coded(InsPooledEntity.class, Long.class, List.of()).build())).isEmpty();
                assertThat(p.statements()).isEmpty();
            });
        }
    }

    // ---- AC-WRT-26

    @TckTest
    void ac_wrt_26_a_per_chunk_insert_values_failure_reports_committed_rows_and_the_next_row_index(TckDatabase db) {
        var insert = ValuesInsert.builder(BARE_ARCHIVE, Long.class, bareRows(7))
                .chunked(ChunkOptions.size(2).commitEachChunk()).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(new InsertSelectTest.ResourceLocal());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            // A row already holding row 5's id: the third chunk, rows 4 and 5, fails.
            execute(db, "insert into order_archive (id, order_id, status, version) values (9006, 0, 'X', 0)");
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    archives(em, config).insert(insert)));

            assertThat(failure[0].code()).isEqualTo(MqCode.MQ2502);
            assertThat(failure[0]).hasCauseInstanceOf(ConstraintViolationException.class)
                    .hasMessageContaining("the 2 chunks before it stay committed, 4 rows, and the next row is 4");
            assertThat(failure[0].committedRows()).isEqualTo(4);
            assertThat(failure[0].nextRowIndex()).hasValue(4);
            assertThat(failure[0].inDoubtRowCount()).isZero();
            assertThat(failure[0].lastCommittedKey()).isEmpty();
            assertThat(failure[0].inDoubtKeys()).isEmpty();
            assertThat(archivedIds(db)).containsExactly(9001L, 9002L, 9003L, 9004L, 9006L);
        } finally {
            execute(db, "delete from order_archive");
        }
    }

    @TckTest
    void ac_wrt_26_a_failed_insert_values_chunk_commit_reports_its_rows_in_doubt(TckDatabase db) {
        var insert = ValuesInsert.builder(BARE_ARCHIVE, Long.class, bareRows(5))
                .chunked(ChunkOptions.size(2).commitEachChunk()).build();
        var config = ModelQueryConfig.defaults().chunkTransactions(failingSecondCommit());
        ChunkedWriteException[] failure = new ChunkedWriteException[1];

        try {
            withoutTransaction(JoinTestSupport.dataSource(db), em -> failure[0] = catchChunked(() ->
                    archives(em, config).insert(insert)));

            assertThat(failure[0]).hasMessageContaining("whether its 2 rows were written is unknown");
            assertThat(failure[0].committedRows()).isEqualTo(2);
            assertThat(failure[0].nextRowIndex()).hasValue(2);
            assertThat(failure[0].inDoubtRowCount()).isEqualTo(2);
            assertThat(failure[0].inDoubtKeys()).isEmpty();
            assertThat(archivedIds(db)).containsExactly(9001L, 9002L);
        } finally {
            execute(db, "delete from order_archive");
        }
    }

    // ---- AC-WRT-27

    @TckTest
    void ac_wrt_27_an_insert_values_flushes_first_clears_by_default_keeps_with_keep_and_evicts_the_root(
            TckDatabase db) {
        var managed = new ArrayList<Boolean>();
        var evicted = new ArrayList<Object>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            em.setFlushMode(FlushModeType.COMMIT);
            CustomerEntity customer = em.find(CustomerEntity.class, 7L);
            customer.rename("Flushed first");
            EntityManager recording = recordingEvictions(em, evicted);
            archives(recording, ModelQueryConfig.defaults())
                    .insert(ValuesInsert.builder(BARE_ARCHIVE, Long.class, bareRows(2)).build());
            managed.add(em.contains(customer));
            customer = em.find(CustomerEntity.class, 7L);
            archives(recording, ModelQueryConfig.defaults()).insert(ValuesInsert.builder(BARE_ARCHIVE, Long.class,
                    List.of(new BareArchive(9101L, 1L, "NEW"))).persistenceContext(PersistenceContextMode.KEEP)
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

    /** Inserts {@link #CODED} into {@code root}, which generates its ids, and reads each row back. */
    private static <E> void assertRoundTrips(InsertProbes p, Class<E> root) {
        String entity = root.getSimpleName();
        inProbeTransaction(p, em -> {
            assertThat(executor(em, root).insert(coded(root, Long.class, CODED).build())).as(entity).isEqualTo(3);
            assertThat(em.createQuery("select e.code, e.name from " + entity + " e order by e.code", Object[].class)
                    .getResultList()).extracting(row -> row[0] + "|" + row[1])
                    .as(entity).containsExactly("c1|N1", "c2|N2", "c3|N3");
            assertThat(em.createQuery("select count(distinct e.id) from " + entity + " e", Long.class)
                    .getSingleResult()).as(entity).isEqualTo(3);
        });
    }

    /**
     * Inserts five rows into {@code root}, in chunks of 2, so three statements' keys come back in one list, and reads
     * each row back by the key at its index.
     */
    private static <E, K> void assertKeysReadBack(InsertProbes p, Class<E> root, Class<K> keyType) {
        var rows = IntStream.rangeClosed(1, 5).mapToObj(i -> new Coded("k" + i, "K" + i)).toList();
        String entity = root.getSimpleName();
        inProbeTransaction(p, em -> {
            List<K> keys = executor(em, root).insertReturningKeys(coded(root, keyType, rows)
                    .chunked(ChunkOptions.size(2)).build());
            assertThat(keys).as(entity).hasSize(5).doesNotContainNull().doesNotHaveDuplicates();
            for (int i = 0; i < rows.size(); i++) {
                assertThat(em.createQuery("select e.code from " + entity + " e where e.id = :id", String.class)
                        .setParameter("id", keys.get(i)).getSingleResult())
                        .as(entity + " row " + i).isEqualTo(rows.get(i).code());
            }
        });
    }

    /** {@code n} archive rows, ids 9001 on, each of order 1 to {@code n}. */
    private static List<BareArchive> bareRows(int n) {
        return LongStream.rangeClosed(1, n).mapToObj(i -> new BareArchive(9000 + i, i, "NEW")).toList();
    }

    /** An insert of {@code rows} into {@code root}, a probe root that generates its id, keys typed {@code K}. */
    private static <E, K> ValuesInsert.Rows<E, K, Coded> coded(Class<E> root, Class<K> keyType, List<Coded> rows) {
        TableField<E, E> table = TableField.root(root);
        return ValuesInsert.builder(InsertColumns.<Coded, E>of(table)
                .add(ColumnField.of(Coded.class, table, "code", String.class), Coded::code)
                .add(ColumnField.of(Coded.class, table, "name", String.class), Coded::name), keyType, rows);
    }

    private static <E> ModelQueryExecutor<E> executor(EntityManager em, Class<E> root) {
        return ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults());
    }

    private static ModelQueryExecutor<OrderArchiveEntity> archives(EntityManager em, ModelQueryConfig config) {
        return ModelQueryExecutor.create(em, OrderArchiveEntity.class, config);
    }

    /** The archive rows by id: {@code id|orderId|customerId|status|customerName|archivedBy|version}. */
    private static List<String> archived(EntityManager em) {
        return em.createQuery("select a.id, a.orderId, c.id, a.status, a.customerName, a.archivedBy, a.version"
                + " from OrderArchiveEntity a left join a.customer c order by a.id", Object[].class)
                .getResultList().stream()
                .map(row -> String.join("|", Arrays.stream(row).map(String::valueOf).toList()))
                .toList();
    }

    /** The archive's ids, in order, read over plain JDBC. */
    private static List<Long> archivedIds(TckDatabase db) {
        var ids = new ArrayList<Long>();
        try (var c = db.getConnection(); var statement = c.createStatement();
                var rs = statement.executeQuery("select id from order_archive order by id")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    /** Runs {@code work} in a rolled-back transaction of a new session of the probe factory. */
    private static void inProbeTransaction(InsertProbes p, Consumer<EntityManager> work) {
        p.factory().inSession(em -> {
            em.getTransaction().begin();
            try {
                work.accept(em);
            } finally {
                em.getTransaction().rollback();
            }
        });
    }

    /** {@code db}'s built-in profile with the bind-parameter and {@code VALUES} row limits given (R-VND-03). */
    static VendorProfile limited(TckDatabase db, int maxBindParameters, int maxValuesRows) {
        VendorProfile builtIn;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            builtIn = VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile();
        }
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
                return maxBindParameters;
            }

            @Override
            public int maxValuesRows() {
                return maxValuesRows;
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
}
