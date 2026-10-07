package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ChunkedWriteException;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.WriteAssignment;
import com.rey.modelquery.jpa.WriteKind;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.ins.InsAuditedBase;
import com.rey.modelquery.tck.vnd.ins.InsAuditedEntity;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;

/**
 * Write assignments on every Tier-1 vendor: each applied by the writes of its kind, a bulk update, chunked or not, and
 * entity mode, insert-values, insert-select, a {@code doUpdate} and {@code persist}, after the definition's own values
 * and skipped where it sets the attribute; the supplier called once per execution; an entity callback winning under
 * {@code persist} and entity mode; a superclass's assignment applied to a root extending it; and the checks on the
 * first write, {@code MQ1611} and {@code MQ1612}, before any statement (spec api/14 R-WRT-49).
 */
class WriteAssignmentTest {

    record Audited(Long id, String name, String createdBy, String updatedBy) {}

    record Source(Long id, String name) {}

    private static final TableField<InsAuditedEntity, InsAuditedEntity> ROOT = TableField.root(InsAuditedEntity.class);
    private static final TableField<InsSourceEntity, InsSourceEntity> SOURCE = TableField.root(InsSourceEntity.class);
    private static final ColumnField<Audited, InsAuditedEntity, Long> ID =
            ColumnField.of(Audited.class, ROOT, "id", Long.class);
    private static final ColumnField<Audited, InsAuditedEntity, String> NAME =
            ColumnField.of(Audited.class, ROOT, "name", String.class);
    private static final ColumnField<Audited, InsAuditedEntity, String> CREATED_BY =
            ColumnField.of(Audited.class, ROOT, "createdBy", String.class);
    private static final ColumnField<Audited, InsAuditedEntity, String> UPDATED_BY =
            ColumnField.of(Audited.class, ROOT, "updatedBy", String.class);
    private static final ColumnField<Source, InsSourceEntity, Long> SOURCE_ID =
            ColumnField.of(Source.class, SOURCE, "id", Long.class);
    private static final ColumnField<Source, InsSourceEntity, String> SOURCE_NAME =
            ColumnField.of(Source.class, SOURCE, "name", String.class);

    private static final InsertColumns<Audited, InsAuditedEntity> COLUMNS =
            InsertColumns.<Audited, InsAuditedEntity>of(ROOT).addKey(ID, Audited::id).add(NAME, Audited::name);

    private static final String ROWS = "select id, name, created_by, updated_by, touched_by, callback_by, version "
            + "from ins_audited order by id";

    /** A supplier of {@code <prefix><n>}, {@code n} counting its calls. */
    private static final class Counted implements Supplier<String> {
        final String prefix;
        final AtomicInteger calls = new AtomicInteger();

        Counted(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String get() {
            return prefix + calls.incrementAndGet();
        }
    }

    /**
     * The audit assignments: {@code createdBy} on insert and {@code updatedBy} on both, named on the superclass;
     * {@code stamp.touchedBy} on update, through the embeddable; {@code callbackBy}, which the callbacks set too, on
     * both; and one on another root, whose path this root lacks.
     */
    private static final class Audit {
        final Counted created = new Counted("c");
        final Counted updated = new Counted("u");
        final Counted touched = new Counted("t");
        final Counted written = new Counted("w");

        ModelQueryConfig config() {
            return ModelQueryConfig.defaults().writeAssignments(List.of(
                    WriteAssignment.of(InsAuditedBase.class, "createdBy", String.class, WriteKind.INSERT, created),
                    WriteAssignment.of(InsAuditedBase.class, "updatedBy", String.class, WriteKind.INSERT_AND_UPDATE,
                            updated),
                    WriteAssignment.of(InsAuditedEntity.class, "stamp.touchedBy", String.class, WriteKind.UPDATE,
                            touched),
                    WriteAssignment.of(InsAuditedEntity.class, "callbackBy", String.class,
                            WriteKind.INSERT_AND_UPDATE, written),
                    WriteAssignment.of(InsSourceEntity.class, "code", String.class, WriteKind.INSERT_AND_UPDATE,
                            () -> "never")));
        }
    }

    /** A change set with nothing set. */
    private static final class NoChanges implements Changes<Audited> {
        @Override
        public boolean isSet(ColumnField<Audited, ?, ?> column) {
            return false;
        }

        @Override
        public NoChanges unset(ColumnField<Audited, ?, ?> column) {
            return this;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public List<Assignment<Audited, ?>> assignments() {
            return List.of();
        }
    }

    /** R-WRT-19's plain-JPA callback, which fails the {@code failing}th chunk before it writes. */
    private static final class FailingChunk implements ChunkTransactions {
        private final int failing;
        private int chunks;

        FailingChunk(int failing) {
            this.failing = failing;
        }

        @Override
        public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
            if (++chunks == failing) {
                throw new IllegalStateException("chunk " + chunks + " fails");
            }
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                T result = chunk.apply(em);
                em.getTransaction().commit();
                return result;
            } finally {
                em.close();
            }
        }
    }

    @AfterEach
    void forgetCallbacks() {
        InsAuditedEntity.CALLBACKS.clear();
    }

    // ---- AC-WRT-39: applied by each write

    @TckTest
    void ac_wrt_39_a_bulk_update_sets_its_update_assignments_after_its_own_values_and_no_insert_one(TckDatabase db) {
        var audit = new Audit();
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(NAME, "N").where(f -> f.lte(ID, 2L))
                .build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 3);
            long written = inCommittedTransaction(p, em -> executor(em, audit.config()).update(update));

            assertThat(written).isEqualTo(2);
            SqlSnapshots.assertMatches(db, "wrt-39-update-assignment", p.statements());
            assertThat(p.rows(ROWS)).containsExactly("1|N|seed|u1|t1|w1|1", "2|N|seed|u1|t1|w1|1",
                    "3|r3|seed|seed|null|null|0");
            assertThat(audit.created.calls).hasValue(0);
        }
    }

    @TckTest
    void ac_wrt_39_a_chunked_update_calls_each_supplier_once_per_execution_and_again_on_resume(TckDatabase db) {
        var audit = new Audit();
        var options = ChunkOptions.size(2).commitEachChunk();
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(NAME, "N").all().chunked(options)
                .build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 5);
            ChunkedWriteException[] failure = new ChunkedWriteException[1];
            var failing = audit.config().chunkTransactions(new FailingChunk(2));
            p.factory().inSession(em -> assertThatThrownBy(() -> executor(em, failing).update(update))
                    .isInstanceOfSatisfying(ChunkedWriteException.class, e -> failure[0] = e));
            assertThat(failure[0].lastCommittedKey()).contains(2L);

            var resume = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(NAME, "N").all()
                    .chunked(options, (Long) failure[0].lastCommittedKey().orElseThrow()).build();
            long[] resumed = new long[1];
            p.forget();
            p.factory().inSession(em -> resumed[0] = executor(em, audit.config()
                    .chunkTransactions(new FailingChunk(0))).update(resume));

            assertThat(resumed[0]).isEqualTo(3);
            // Two chunks of the resumed run, one value: every chunk of one execution gets the same.
            assertThat(writes(p.statements(), "update")).hasSize(2);
            assertThat(p.rows("select id, updated_by from ins_audited order by id")).containsExactly("1|u1", "2|u1",
                    "3|u2", "4|u2", "5|u2");
            assertThat(audit.updated.calls).hasValue(2);
        }
    }

    @TckTest
    void ac_wrt_39_an_entity_mode_update_assignment_dirties_and_fires_pre_update_for_every_matched_row(
            TckDatabase db) {
        var audit = new Audit();
        // Row 2 has the name already: the assignments alone make it dirty. The callback's callbackBy wins.
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(NAME, "r2").where(f -> f.lte(ID, 3L))
                .throughEntities().build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 4);
            long written = inCommittedTransaction(p, em -> executor(em, audit.config()).update(update));

            assertThat(written).isEqualTo(3);
            assertThat(InsAuditedEntity.CALLBACKS).containsExactlyInAnyOrder("preUpdate:1", "preUpdate:2",
                    "preUpdate:3");
            assertThat(p.rows(ROWS)).containsExactly("1|r2|seed|u1|t1|preUpdate|1", "2|r2|seed|u1|t1|preUpdate|1",
                    "3|r2|seed|u1|t1|preUpdate|1", "4|r4|seed|seed|null|null|0");
            assertThat(audit.updated.calls).hasValue(1);
        }
    }

    @TckTest
    void ac_wrt_39_insert_values_writes_its_insert_assignments_as_one_more_bound_column_per_row(TckDatabase db) {
        var audit = new Audit();
        var insert = ValuesInsert.builder(COLUMNS, Long.class, List.of(new Audited(10L, "A", null, null),
                new Audited(11L, "B", null, null))).build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            long written = inCommittedTransaction(p, em -> executor(em, audit.config()).insert(insert));

            assertThat(written).isEqualTo(2);
            SqlSnapshots.assertMatches(db, "wrt-39-insert-assignment", p.statements());
            assertThat(p.rows(ROWS)).containsExactly("10|A|c1|u1|null|w1|0", "11|B|c1|u1|null|w1|0");
            assertThat(audit.touched.calls).hasValue(0);
        }
    }

    @TckTest
    void ac_wrt_39_insert_values_counts_the_assignment_binds_in_its_rows_per_statement(TckDatabase db) {
        // id, name, three assignments and the version seed: 6 binds a row, so 12 binds take 2 rows, not 4.
        var config = new Audit().config().vendorProfiles(List.of(InsertValuesTest.limited(db, 12,
                Integer.MAX_VALUE)));
        var insert = ValuesInsert.builder(COLUMNS, Long.class, rows(5)).build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            long written = inCommittedTransaction(p, em -> executor(em, config).insert(insert));

            assertThat(written).isEqualTo(5);
            assertThat(writes(p.statements(), "insert")).hasSize(3)
                    .allSatisfy(sql -> assertThat(BulkWriteTest.binds(sql)).isLessThanOrEqualTo(12));
            assertThat(p.rows("select distinct created_by from ins_audited")).containsExactly("c1");
        }
    }

    @TckTest
    void ac_wrt_39_a_do_update_sets_the_update_assignments_it_does_not_set_itself_with_the_insert_value(
            TckDatabase db) {
        var audit = new Audit();
        var rows = List.of(new Audited(1L, "new", null, null), new Audited(5L, "five", null, null));
        var upsert = ValuesInsert.builder(COLUMNS, Long.class, rows).onConflict(ID)
                .doUpdate(u -> u.setFromRow(NAME)).anyUniqueKey().build();
        var manual = ValuesInsert.builder(COLUMNS, Long.class, rows).onConflict(ID)
                .doUpdate(u -> u.setFromRow(NAME).set(UPDATED_BY, "manual")).anyUniqueKey().build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 1);
            inCommittedTransaction(p, em -> executor(em, audit.config()).insert(upsert));

            // The stored row keeps its createdBy, an insert assignment; the new row gets the same u1 as the update.
            assertThat(p.rows(ROWS)).containsExactly("1|new|seed|u1|t1|w1|1", "5|five|c1|u1|null|w1|0");
            assertThat(audit.updated.calls).hasValue(1);

            inCommittedTransaction(p, em -> executor(em, audit.config()).insert(manual));
            assertThat(p.rows("select id, updated_by, touched_by from ins_audited order by id"))
                    .containsExactly("1|manual|t2", "5|manual|t2");
        }
    }

    @TckTest
    void ac_wrt_39_an_insert_select_selects_its_insert_assignments_as_bound_columns_once_per_execution(
            TckDatabase db) {
        var audit = new Audit();
        var insert = ModelInsert.select(COLUMNS, SOURCE).map(ID, SOURCE_ID).map(NAME, SOURCE_NAME).all()
                .chunked(ChunkOptions.size(2)).build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            long written = inCommittedTransaction(p, em -> executor(em, audit.config()).insert(insert));

            assertThat(written).isEqualTo(3);
            assertThat(writes(p.statements(), "insert")).hasSize(2);
            assertThat(p.rows(ROWS)).containsExactly("1|S1|c1|u1|null|w1|0", "2|S2|c1|u1|null|w1|0",
                    "3|S3|c1|u1|null|w1|0");
            assertThat(audit.created.calls).hasValue(1);
        }
    }

    @TckTest
    void ac_wrt_39_persist_sets_its_insert_assignments_before_the_flush_and_a_pre_persist_callback_wins(
            TckDatabase db) {
        var audit = new Audit();
        var persist = ModelPersist.of(COLUMNS, Long.class, new Audited(7L, "P", null, null));

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            Long key = inCommittedTransaction(p, em -> executor(em, audit.config()).persist(persist));

            assertThat(key).isEqualTo(7L);
            assertThat(writes(p.statements(), "insert")).hasSize(1);
            assertThat(p.rows(ROWS)).containsExactly("7|P|c1|u1|null|prePersist|0");
            assertThat(audit.written.calls).hasValue(1);
        }
    }

    // ---- AC-WRT-39: skipped where the definition sets the attribute, and never a write on its own

    @TckTest
    void ac_wrt_39_an_assignment_is_skipped_where_the_definition_sets_its_attribute(TckDatabase db) {
        var audit = new Audit();
        var columns = InsertColumns.<Audited, InsAuditedEntity>of(ROOT).addKey(ID, Audited::id).add(NAME, Audited::name)
                .add(CREATED_BY, Audited::createdBy);
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(UPDATED_BY, "backfill").whereKey(1L)
                .build();
        var insert = ValuesInsert.builder(columns, Long.class, List.of(new Audited(10L, "A", "mine", null))).build();
        var select = ModelInsert.select(COLUMNS, SOURCE).map(ID, SOURCE_ID).map(NAME, SOURCE_NAME)
                .set(CREATED_BY, "batch").where(f -> f.eq(SOURCE_ID, 3L)).build();
        var persist = ModelPersist.of(columns, Long.class, new Audited(20L, "P", "mine", null));

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 1);
            inCommittedTransaction(p, em -> {
                ModelQueryExecutor<InsAuditedEntity> executor = executor(em, audit.config());
                executor.update(update);
                executor.insert(insert);
                executor.insert(select);
                return executor.persist(persist);
            });

            // The update set updatedBy itself, so its supplier ran first for the insert-values.
            assertThat(p.rows("select id, created_by, updated_by from ins_audited order by id")).containsExactly(
                    "1|seed|backfill", "3|batch|u2", "10|mine|u1", "20|mine|u3");
            assertThat(audit.created.calls).hasValue(0);
        }
    }

    @TckTest
    void ac_wrt_39_an_update_with_nothing_to_write_stays_a_no_op(TckDatabase db) {
        var audit = new Audit();
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(new NoChanges()).whereKey(1L).build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 1);
            long written = inCommittedTransaction(p, em -> executor(em, audit.config()).update(update));

            assertThat(written).isZero();
            assertThat(p.statements()).isEmpty();
            assertThat(audit.updated.calls).hasValue(0);
        }
    }

    @TckTest
    void ac_wrt_39_a_set_null_skips_the_assignment_and_a_delete_applies_none(TckDatabase db) {
        var audit = new Audit();
        var cleared = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).setNull(UPDATED_BY).whereKey(1L)
                .build();
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID)).whereKey(2L).build();
        // Checked, this path would be MQ1611: a delete never consults the assignments.
        var unassignable = ModelQueryConfig.defaults().writeAssignments(List.of(WriteAssignment.of(
                InsAuditedEntity.class, "unknown", String.class, WriteKind.INSERT_AND_UPDATE, () -> "x")));

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 3);
            inCommittedTransaction(p, em -> executor(em, audit.config()).update(cleared));
            long deleted = inCommittedTransaction(p, em -> executor(em, unassignable).delete(delete));

            assertThat(deleted).isEqualTo(1);
            assertThat(p.rows("select id, updated_by, touched_by from ins_audited order by id"))
                    .containsExactly("1|null|t1", "3|seed|null");
            assertThat(audit.updated.calls).hasValue(0);
            assertThat(audit.touched.calls).hasValue(1);
        }
    }

    @TckTest
    void ac_wrt_39_an_assignment_on_a_mapped_superclass_applies_to_a_root_extending_it_and_none_to_another_root(
            TckDatabase db) {
        // The InsSourceEntity assignment names code, which this root lacks: applied here, it would be MQ1611.
        var audit = new Audit();
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(NAME, "N").whereKey(1L).build();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 1);
            inCommittedTransaction(p, em -> executor(em, audit.config()).update(update));

            assertThat(p.rows("select created_by, updated_by from ins_audited")).containsExactly("seed|u1");
            assertThat(p.rows("select code from ins_source order by id")).containsExactly("s1", "s2", "s3");
        }
    }

    // ---- AC-WRT-39: MQ1611 and MQ1612

    @TckTest
    void ac_wrt_39_a_path_that_is_not_one_basic_attribute_or_overlapping_kinds_is_mq1611_before_any_statement(
            TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(NAME, "N").whereKey(1L).build();
        var refused = new ArrayList<String>();

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 1);
            for (String path : List.of("unknown", "id", "version", "tags", "source", "stamp", "name.length")) {
                var config = ModelQueryConfig.defaults().writeAssignments(List.of(WriteAssignment.of(
                        InsAuditedEntity.class, path, String.class, WriteKind.UPDATE, () -> "x")));
                refused.add(refusal(p, config, update, MqCode.MQ1611));
            }
            var overlapping = ModelQueryConfig.defaults().writeAssignments(List.of(
                    WriteAssignment.of(InsAuditedBase.class, "updatedBy", String.class, WriteKind.UPDATE, () -> "a"),
                    WriteAssignment.of(InsAuditedEntity.class, "updatedBy", String.class,
                            WriteKind.INSERT_AND_UPDATE, () -> "b")));
            refused.add(refusal(p, overlapping, update, MqCode.MQ1611));

            assertThat(refused).satisfiesExactly(
                    m -> assertThat(m).contains("InsAuditedEntity.unknown names no attribute unknown"),
                    m -> assertThat(m).contains("names the id id"),
                    m -> assertThat(m).contains("names the @Version version"),
                    m -> assertThat(m).contains("names the collection tags"),
                    m -> assertThat(m).contains("names the to-one source"),
                    m -> assertThat(m).contains("names the whole embeddable stamp"),
                    m -> assertThat(m).contains("goes on past the basic attribute name"),
                    m -> assertThat(m).contains("is assigned by WriteAssignment[InsAuditedBase.updatedBy"));

            // An insert and an update assignment for one path do not overlap.
            var disjoint = ModelQueryConfig.defaults().writeAssignments(List.of(
                    WriteAssignment.of(InsAuditedBase.class, "updatedBy", String.class, WriteKind.UPDATE, () -> "a"),
                    WriteAssignment.of(InsAuditedEntity.class, "updatedBy", String.class, WriteKind.INSERT,
                            () -> "b")));
            inCommittedTransaction(p, em -> executor(em, disjoint).update(update));
            assertThat(p.rows("select updated_by from ins_audited")).containsExactly("a");
        }
    }

    @TckTest
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_wrt_39_a_type_the_attribute_cannot_take_or_a_null_or_mistyped_value_is_mq1612_before_any_statement(
            TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(NAME, "N").whereKey(1L).build();
        Supplier mistyped = () -> 42;

        try (InsertProbes p = InsertProbes.withWriteAssignmentRoots(db)) {
            seed(p, 1);
            String type = refusal(p, assigning(Integer.class, () -> 1), update, MqCode.MQ1612);
            String nothing = refusal(p, assigning(String.class, () -> null), update, MqCode.MQ1612);
            String wrong = refusal(p, assigning(String.class, mistyped), update, MqCode.MQ1612);

            assertThat(type).contains("InsAuditedEntity.updatedBy is a String, which a Integer is not");
            assertThat(nothing).contains("its supplier returned null, where a write needs a String");
            assertThat(wrong).contains("its supplier returned a Integer, where a write needs a String");
            assertThat(p.rows("select name, updated_by from ins_audited")).containsExactly("r1|seed");
        }
    }

    private static <A> ModelQueryConfig assigning(Class<A> type, Supplier<? extends A> value) {
        return ModelQueryConfig.defaults().writeAssignments(List.of(WriteAssignment.of(InsAuditedBase.class,
                "updatedBy", type, WriteKind.UPDATE, value)));
    }

    /** The message of {@code code}, which {@code update} throws under {@code config} before any statement. */
    private static String refusal(InsertProbes p, ModelQueryConfig config, ModelUpdate<InsAuditedEntity, ?> update,
            MqCode code) {
        String[] message = new String[1];
        assertThatThrownBy(() -> inCommittedTransaction(p, em -> executor(em, config).update(update)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    message[0] = e.getMessage();
                });
        assertThat(p.statements()).isEmpty();
        return message[0];
    }

    /** Rows {@code 1} to {@code n} of {@link Audited}, each named {@code r<id>}. */
    private static List<Audited> rows(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(id -> new Audited((long) id, "r" + id, null, null)).toList();
    }

    /** Rows {@code 1} to {@code n}, each named {@code r<id>}, created and updated by {@code seed}, at version 0. */
    private static void seed(InsertProbes p, int n) {
        p.jdbc("insert into ins_audited (id, name, created_by, updated_by, version) values "
                + IntStream.rangeClosed(1, n).mapToObj(id -> "(" + id + ", 'r" + id + "', 'seed', 'seed', 0)")
                        .collect(Collectors.joining(", ")));
    }

    private static ModelQueryExecutor<InsAuditedEntity> executor(EntityManager em, ModelQueryConfig config) {
        return ModelQueryExecutor.create(em, InsAuditedEntity.class, config);
    }

    /**
     * Runs {@code work} in a transaction of a new session of the probe factory, after forgetting the statements
     * recorded so far, and commits it.
     */
    private static <T> T inCommittedTransaction(InsertProbes p, Function<EntityManager, T> work) {
        p.forget();
        return p.factory().fromTransaction(work::apply);
    }
}
