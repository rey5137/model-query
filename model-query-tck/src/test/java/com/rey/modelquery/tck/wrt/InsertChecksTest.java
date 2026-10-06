package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.inRolledBackTransaction;
import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.IdGeneration;
import com.rey.modelquery.tck.col.CompositeKeyItemEntity;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.vnd.ins.InsAssignedEntity;
import com.rey.modelquery.tck.vnd.ins.InsCompositeEntity;
import com.rey.modelquery.tck.vnd.ins.InsIdentityEntity;
import com.rey.modelquery.tck.vnd.ins.InsJoinedEntity;
import com.rey.modelquery.tck.vnd.ins.InsMapsIdEntity;
import com.rey.modelquery.tck.vnd.ins.InsPooledEntity;
import com.rey.modelquery.tck.vnd.ins.InsSecondaryEntity;
import com.rey.modelquery.tck.vnd.ins.InsSequenceEntity;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsTableEntity;
import com.rey.modelquery.tck.vnd.ins.InsUuidEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.util.List;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;

/**
 * The checks a bulk insert and {@code persist} run before any statement and before the flush: on the definition's
 * first execution, against the metamodel and the generator the provider reports, and the provider's insert support
 * (spec api/14 R-WRT-26, R-WRT-27, R-WRT-33, §10.1, vendor/40 R-VND-14, D-61, D-116, D-117). The roots are the D-116
 * probe entities.
 */
class InsertChecksTest {

    /** An insert model: an id, where the root's is assigned, and one text value for every other column. */
    record Row(Long id, String text) {}

    /** The insert-select source model. */
    record Source(Long id, String code, String name) {}

    private static final List<Row> ROWS = List.of(new Row(1L, "a"), new Row(2L, "b"));

    private static final TableField<InsSourceEntity, InsSourceEntity> SOURCE = TableField.root(InsSourceEntity.class);
    private static final ColumnField<Source, InsSourceEntity, Long> SOURCE_ID =
            ColumnField.of(Source.class, SOURCE, "id", Long.class);
    private static final ColumnField<Source, InsSourceEntity, String> SOURCE_CODE =
            ColumnField.of(Source.class, SOURCE, "code", String.class);
    private static final ColumnField<Source, InsSourceEntity, String> SOURCE_NAME =
            ColumnField.of(Source.class, SOURCE, "name", String.class);

    // ---- AC-WRT-29: MQ1805

    @TckTest
    void ac_wrt_29_insert_select_with_a_pooled_sequence_table_or_uuid_generator_throws_mq1805(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertCode(p, InsPooledEntity.class, MqCode.MQ1805, "with increment 20",
                    executor -> executor.insert(select(InsPooledEntity.class)));
            assertCode(p, InsTableEntity.class, MqCode.MQ1805, "a table generator",
                    executor -> executor.insert(select(InsTableEntity.class)));
            assertCode(p, InsUuidEntity.class, MqCode.MQ1805, "a UUID generator",
                    executor -> executor.insert(select(InsUuidEntity.class)));
        }
    }

    @TckTest
    void ac_wrt_29_insert_select_with_a_table_backed_sequence_throws_mq1805_and_a_database_one_passes(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            // MySQL backs a sequence with a table, on which Hibernate 6.6 writes wrong keys (D-116)
            var id = (IdGeneration.Sequence) new HibernateProviderSupport().inserts().orElseThrow()
                    .target(p.factory(), InsSequenceEntity.class).id();
            if (id.physical()) {
                // Outside a transaction, so the first error after the checks is MQ2501, before any statement
                p.factory().inSession(em -> assertNeedsTransaction(() -> ModelQueryExecutor.create(em,
                        InsSequenceEntity.class, ModelQueryConfig.defaults()).insert(select(InsSequenceEntity.class))));
            } else {
                assertCode(p, InsSequenceEntity.class, MqCode.MQ1805, "a table-backed sequence with increment 1",
                        executor -> executor.insert(select(InsSequenceEntity.class)));
            }
        }
    }

    @TckTest
    void ac_wrt_29_a_joined_secondary_table_maps_id_or_generated_composite_root_throws_mq1805(TckDatabase db) {
        try (InsertProbes p = InsertProbes.withUnsupportedRoots(db)) {
            assertCode(p, InsJoinedEntity.class, MqCode.MQ1805, "JOINED inheritance",
                    executor -> executor.insert(values(InsJoinedEntity.class, false, "name")));
            assertCode(p, InsSecondaryEntity.class, MqCode.MQ1805, "a @SecondaryTable",
                    executor -> executor.insert(values(InsSecondaryEntity.class, true, "name")));
            assertCode(p, InsMapsIdEntity.class, MqCode.MQ1805, "@MapsId",
                    executor -> executor.insert(values(InsMapsIdEntity.class, true, "note")));
            var composite = ValuesInsert.builder(columns(InsCompositeEntity.class, false, "name"),
                    InsCompositeEntity.Key.class, ROWS).build();
            assertCode(p, InsCompositeEntity.class, MqCode.MQ1805, "a composite id with generated parts",
                    executor -> executor.insert(composite));
        }
    }

    // ---- AC-WRT-29: MQ1802

    @TckTest
    void ac_wrt_29_naming_a_generated_id_throws_mq1802(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertCode(p, InsIdentityEntity.class, MqCode.MQ1802, "writes the root's id, which an IDENTITY column",
                    executor -> executor.insert(values(InsIdentityEntity.class, true, "code", "name")));
            assertCode(p, InsPooledEntity.class, MqCode.MQ1802, "writes the root's id, which a",
                    executor -> executor.insertReturningKeys(values(InsPooledEntity.class, true, "code")));
        }
    }

    @TckTest
    void ac_wrt_29_lacking_an_id_that_has_no_generator_throws_mq1802(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertCode(p, InsAssignedEntity.class, MqCode.MQ1802, "has no generator, and the insert does not write",
                    executor -> executor.insert(values(InsAssignedEntity.class, false, "code", "name")));
            assertCode(p, InsAssignedEntity.class, MqCode.MQ1802, "and map it from the source",
                    executor -> executor.insert(select(InsAssignedEntity.class)));
        }
    }

    @TckTest
    void ac_wrt_29_writing_part_of_a_composite_id_throws_mq1802(TckDatabase db) {
        TableField<CompositeKeyItemEntity, CompositeKeyItemEntity> root = TableField.root(CompositeKeyItemEntity.class);
        var columns = InsertColumns.<Row, CompositeKeyItemEntity>of(root)
                .add(ColumnField.of(Row.class, root, "tenantId", Long.class), Row::id)
                .add(ColumnField.of(Row.class, root, "label", String.class), Row::text);
        var insert = ValuesInsert.builder(columns, CompositeKeyItemEntity.Key.class, ROWS).build();

        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> assertThatThrownBy(() -> ModelQueryExecutor.create(em, CompositeKeyItemEntity.class,
                    ModelQueryConfig.defaults()).insert(insert))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1802))
                    .hasMessageContaining("writes part of the id"));
        }
    }

    // ---- AC-WRT-29: MQ1801 on first execution

    @TckTest
    void ac_wrt_29_an_insert_select_map_between_attributes_of_different_types_throws_mq1801(TckDatabase db) {
        // A hand-written source column typed String over the Long id: the metamodel shows the mismatch
        var lyingId = ColumnField.of(Source.class, SOURCE, "id", String.class);
        TableField<InsAssignedEntity, InsAssignedEntity> root = TableField.root(InsAssignedEntity.class);
        var code = ColumnField.of(Row.class, root, "code", String.class);
        var insert = ModelInsert.select(columns(InsAssignedEntity.class, true, "code"), SOURCE)
                .map(id(root), SOURCE_ID).map(code, lyingId).all().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            assertCode(p, InsAssignedEntity.class, MqCode.MQ1801, "the database copies attribute values",
                    executor -> executor.insert(insert));
        }
    }

    @TckTest
    void ac_wrt_29_an_insert_select_map_or_where_column_off_the_source_root_throws_mq1801(TckDatabase db) {
        // Columns of the Source model declared on another root, as a hand-written model can
        TableField<InsAssignedEntity, InsAssignedEntity> other = TableField.root(InsAssignedEntity.class);
        var otherName = ColumnField.of(Source.class, other, "name", String.class);
        TableField<InsIdentityEntity, InsIdentityEntity> root = TableField.root(InsIdentityEntity.class);
        var code = ColumnField.of(Row.class, root, "code", String.class);
        var name = ColumnField.of(Row.class, root, "name", String.class);
        var columns = columns(InsIdentityEntity.class, false, "code", "name");
        var mapped = ModelInsert.select(columns, SOURCE).map(code, SOURCE_CODE).map(name, otherName).all().build();
        var filtered = ModelInsert.select(columns, SOURCE).map(code, SOURCE_CODE).map(name, SOURCE_NAME)
                .where(f -> f.eq(otherName, "x")).build();

        try (InsertProbes p = InsertProbes.open(db)) {
            assertCode(p, InsIdentityEntity.class, MqCode.MQ1801, "map(...) copies a column of InsAssignedEntity",
                    executor -> executor.insert(mapped));
            assertCode(p, InsIdentityEntity.class, MqCode.MQ1801, "where(...) reads a column of InsAssignedEntity",
                    executor -> executor.insert(filtered));
        }
    }

    // ---- AC-WRT-29: MQ1807

    @TckTest
    void ac_wrt_29_insert_values_or_persist_with_a_key_type_other_than_the_ids_throws_mq1807(TckDatabase db) {
        var insert = ValuesInsert.builder(columns(InsPooledEntity.class, false, "code"), Integer.class, ROWS).build();
        var persist = ModelPersist.of(columns(InsPooledEntity.class, false, "code"), String.class, ROWS.get(0));

        try (InsertProbes p = InsertProbes.open(db)) {
            assertCode(p, InsPooledEntity.class, MqCode.MQ1807, "its keys are typed Integer, but the id of "
                    + "InsPooledEntity is a Long", executor -> executor.insertReturningKeys(insert));
            assertCode(p, InsPooledEntity.class, MqCode.MQ1807, "orm.xml", executor -> executor.insert(insert));
            assertCode(p, InsPooledEntity.class, MqCode.MQ1807, "its keys are typed String",
                    executor -> executor.persist(persist));
        }
    }

    @TckTest
    void ac_wrt_29_the_ids_key_type_an_id_class_or_object_passes_the_first_execution_checks(TckDatabase db) {
        TableField<CompositeKeyItemEntity, CompositeKeyItemEntity> root = TableField.root(CompositeKeyItemEntity.class);
        var composite = InsertColumns.<Row, CompositeKeyItemEntity>of(root)
                .addKey(ColumnField.of(Row.class, root, "tenantId", Long.class), Row::id)
                .addKey(ColumnField.of(Row.class, root, "itemNo", Long.class), Row::id)
                .add(ColumnField.of(Row.class, root, "label", String.class), Row::text);
        var byIdClass = ValuesInsert.builder(composite, CompositeKeyItemEntity.Key.class, ROWS).build();
        var byId = ValuesInsert.builder(columns(InsPooledEntity.class, false, "code"), Long.class, ROWS).build();
        var byObject = ValuesInsert.builder(columns(InsPooledEntity.class, false, "code"), Object.class, ROWS).build();

        // Outside a transaction, so the first error after the checks is MQ2501, before any statement
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> assertNeedsTransaction(() -> ModelQueryExecutor.create(em,
                    CompositeKeyItemEntity.class, ModelQueryConfig.defaults()).insert(byIdClass)));
        }
        try (InsertProbes p = InsertProbes.open(db)) {
            p.factory().inSession(em -> {
                var executor = ModelQueryExecutor.create(em, InsPooledEntity.class, ModelQueryConfig.defaults());
                assertNeedsTransaction(() -> executor.insertReturningKeys(byId));
                assertNeedsTransaction(() -> executor.insertReturningKeys(byObject));
            });
        }
    }

    @TckTest
    void ac_wrt_29_an_id_class_key_passing_the_key_type_check_is_never_returned(TckDatabase db) {
        // Hibernate 6 reports no id type for an @IdClass, so its K is not compared; its keys still cannot come back,
        // since a composite id is assigned (MQ1807) or has a generated part (MQ1805)
        TableField<CompositeKeyItemEntity, CompositeKeyItemEntity> root = TableField.root(CompositeKeyItemEntity.class);
        var assigned = InsertColumns.<Row, CompositeKeyItemEntity>of(root)
                .addKey(ColumnField.of(Row.class, root, "tenantId", Long.class), Row::id)
                .addKey(ColumnField.of(Row.class, root, "itemNo", Long.class), Row::id)
                .add(ColumnField.of(Row.class, root, "label", String.class), Row::text);
        var byAssigned = ValuesInsert.builder(assigned, CompositeKeyItemEntity.Key.class, ROWS).build();
        var byGenerated = ValuesInsert.builder(columns(InsCompositeEntity.class, false, "name"),
                InsCompositeEntity.Key.class, ROWS).build();

        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> assertThatThrownBy(() -> ModelQueryExecutor.create(em, CompositeKeyItemEntity.class,
                    ModelQueryConfig.defaults()).insertReturningKeys(byAssigned))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1807))
                    .hasMessageContaining("the keys are the rows' own ids"));
        }
        try (InsertProbes p = InsertProbes.withUnsupportedRoots(db)) {
            assertCode(p, InsCompositeEntity.class, MqCode.MQ1805, "a composite id with generated parts",
                    executor -> executor.insertReturningKeys(byGenerated));
        }
    }

    // ---- AC-WRT-22, AC-WRT-23: insertReturningKeys

    @TckTest
    void ac_wrt_22_identity_throws_mq1807_with_insert_returning_keys(TckDatabase db) {
        try (InsertProbes p = InsertProbes.open(db)) {
            assertCode(p, InsIdentityEntity.class, MqCode.MQ1807, "persist(...) returns one row's key",
                    executor -> executor.insertReturningKeys(values(InsIdentityEntity.class, false, "code")));
            assertCode(p, InsAssignedEntity.class, MqCode.MQ1807, "the keys are the rows' own ids",
                    executor -> executor.insertReturningKeys(values(InsAssignedEntity.class, true, "code")));
        }
    }

    @TckTest
    void ac_wrt_23_insert_returning_keys_with_commit_each_chunk_throws_mq1801_before_the_flush(TckDatabase db) {
        var insert = ValuesInsert.builder(columns(InsPooledEntity.class, false, "code"), Long.class, ROWS)
                .chunked(ChunkOptions.defaultSize().commitEachChunk()).build();

        try (InsertProbes p = InsertProbes.open(db)) {
            assertCode(p, InsPooledEntity.class, MqCode.MQ1801, "commitEachChunk()",
                    executor -> executor.insertReturningKeys(insert));
        }
    }

    // ---- AC-WRT-31: MQ4009

    @TckTest
    void ac_wrt_31_a_bulk_insert_without_insert_support_throws_mq4009_and_runs_no_flush(TckDatabase db) {
        TableField<CustomerEntity, CustomerEntity> root = TableField.root(CustomerEntity.class);
        var columns = InsertColumns.<Row, CustomerEntity>of(root)
                .addKey(ColumnField.of(Row.class, root, "id", Long.class), Row::id)
                .add(ColumnField.of(Row.class, root, "name", String.class), Row::text);
        var insert = ValuesInsert.builder(columns, Long.class, ROWS).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> JoinTestSupport.withoutServices(
                () -> inRolledBackTransaction(ds, em -> {
                    // A pending rename only the insert's flush would write.
                    em.setFlushMode(FlushModeType.COMMIT);
                    em.find(CustomerEntity.class, 7L).rename("Not flushed");
                    var executor = ModelQueryExecutor.create(em, CustomerEntity.class, ModelQueryConfig.defaults());
                    assertThatThrownBy(() -> executor.insert(insert))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4009))
                            .hasMessageContaining("InsertSupport");
                    assertThatThrownBy(() -> executor.insertReturningKeys(insert))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4009));
                })));

        assertThat(writes(sql, "update")).isEmpty();
        assertThat(writes(sql, "insert")).isEmpty();
    }

    /**
     * Runs {@code call} on an executor of {@code root} in a rolled-back transaction of {@code p}'s factory with a
     * pending entity only a flush would write, and asserts that it throws a {@link ModelQueryDefinitionException} of
     * {@code code} with {@code message}, and that nothing was flushed or written.
     */
    private static <E> void assertCode(InsertProbes p, Class<E> root, MqCode code, String message,
            Consumer<ModelQueryExecutor<E>> call) {
        p.forget();
        p.factory().inSession(em -> {
            em.getTransaction().begin();
            try {
                em.setFlushMode(FlushModeType.COMMIT);
                pending(em);
                var executor = ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults());
                assertThatThrownBy(() -> call.accept(executor))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(code))
                        .hasMessageContaining(message);
            } finally {
                em.getTransaction().rollback();
            }
        });
        assertThat(writes(p.statements(), "insert")).isEmpty();
    }

    private static void assertNeedsTransaction(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2501));
    }

    /** Persists a source row that only a flush would write. */
    private static void pending(EntityManager em) {
        em.persist(InsSourceEntity.of(99L, "pending", "Not flushed"));
    }

    /** An insert-values of {@link #ROWS} into {@code entity}'s {@code attributes}, with its id when {@code withId}. */
    private static <E> ValuesInsert<E, Long, Row> values(Class<E> entity, boolean withId, String... attributes) {
        return ValuesInsert.builder(columns(entity, withId, attributes), Long.class, ROWS).build();
    }

    /** An insert-select of every source row into {@code entity}'s code and name. */
    private static <E> ModelInsert<E, Row> select(Class<E> entity) {
        TableField<E, E> root = TableField.root(entity);
        return ModelInsert.select(columns(entity, false, "code", "name"), SOURCE)
                .map(ColumnField.of(Row.class, root, "code", String.class), SOURCE_CODE)
                .map(ColumnField.of(Row.class, root, "name", String.class), SOURCE_NAME)
                .all().build();
    }

    /** {@code entity}'s insert columns: its id when {@code withId}, then each of {@code attributes}, a text. */
    private static <E> InsertColumns<Row, E> columns(Class<E> entity, boolean withId, String... attributes) {
        TableField<E, E> root = TableField.root(entity);
        InsertColumns<Row, E> columns = InsertColumns.of(root);
        if (withId) {
            columns = columns.addKey(id(root), Row::id);
        }
        for (String attribute : attributes) {
            columns = columns.add(ColumnField.of(Row.class, root, attribute, String.class), Row::text);
        }
        return columns;
    }

    private static <E> ColumnField<Row, E, Long> id(TableField<E, E> root) {
        return ColumnField.of(Row.class, root, "id", Long.class);
    }
}
