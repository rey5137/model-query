package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.vnd.ins.InsCompositeEntity;
import com.rey.modelquery.tck.vnd.ins.InsCtorEmbeddedEntity;
import com.rey.modelquery.tck.vnd.ins.InsGeneratedUuidEntity;
import com.rey.modelquery.tck.vnd.ins.InsGeneratedUuidRow;
import com.rey.modelquery.tck.vnd.ins.InsPersistEntity;
import com.rey.modelquery.tck.vnd.ins.InsPropertyChildEntity;
import com.rey.modelquery.tck.vnd.ins.InsRecordEmbeddedEntity;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsUuidEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import com.rey.modelquery.tck.vnd.ins.QInsGeneratedUuidRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.Session;

/**
 * {@code persist} on every Tier-1 vendor: an {@code IDENTITY} key returned, {@code @PrePersist} run and the created
 * entity detached; an enum, a JPA converter, a model converter, a property-access embeddable and a to-one by id
 * written; an unnamed attribute written as the constructor leaves it, not as the database default; a
 * {@code CascadeType.ALL} target detached; no transaction, no insert support, and a record embeddable
 * (spec api/14 R-WRT-39, R-WRT-40).
 */
class PersistTest {

    /** A {@code persist} row: the amount as {@code #42}, the source by id. */
    record NewPersist(String code, InsPersistEntity.Status status, Boolean flagged, String amount, String city,
            String zip, Long source) {}

    record CodeRow(String code) {}

    record RankRow(Integer rank) {}

    record IdRow(Long id, String code) {}

    record SpotRow(Integer x, Integer rank) {}

    record LabelRow(String label) {}

    /** A row of the record-embeddable root. */
    record NewPoint(Integer x) {}

    private static final TableField<InsPersistEntity, InsPersistEntity> ROOT = TableField.root(InsPersistEntity.class);

    private static final InsertColumns<NewPersist, InsPersistEntity> COLUMNS =
            InsertColumns.<NewPersist, InsPersistEntity>of(ROOT)
                    .add(ColumnField.of(NewPersist.class, ROOT, "code", String.class), NewPersist::code)
                    .add(ColumnField.of(NewPersist.class, ROOT, "status", InsPersistEntity.Status.class),
                            NewPersist::status)
                    .add(ColumnField.of(NewPersist.class, ROOT, "flagged", Boolean.class), NewPersist::flagged)
                    .add(ColumnField.of(NewPersist.class, ROOT, "amount", String.class, Long.class,
                            InsertValuesTest.RefConverter.INSTANCE), NewPersist::amount)
                    .add(ColumnField.of(NewPersist.class, ROOT, "address.city", String.class), NewPersist::city)
                    .add(ColumnField.of(NewPersist.class, ROOT, "address.zip", String.class), NewPersist::zip)
                    .add(ColumnField.of(NewPersist.class, ROOT, "source", Long.class), NewPersist::source);

    private static final String ROW = "select code, status, flagged, amount, city, zip, source_id, region, origin, "
            + "audit from ins_persist where id = ";

    // ---- AC-WRT-28

    @TckTest
    void ac_wrt_28_persist_with_identity_returns_the_key_runs_pre_persist_and_leaves_the_entity_detached(
            TckDatabase db) {
        var persist = ModelPersist.of(COLUMNS, Long.class,
                new NewPersist("p1", InsPersistEntity.Status.PAID, true, "#42", "Hanoi", "100000", 2L));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                Long key = executor(em, InsPersistEntity.class).persist(persist);

                assertThat(key).isNotNull();
                assertThat(writes(p.statements(), "insert into ins_persist")).hasSize(1);
                // The source by id is a reference, never read
                assertThat(writes(p.statements(), "select")).isEmpty();
                // The created entity is not in the context: finding it reads the row back
                p.forget();
                assertThat(em.find(InsPersistEntity.class, key)).isNotNull();
                assertThat(writes(p.statements(), "select")).hasSize(1);
                em.getTransaction().commit();
                // The enum by name, the JPA converter's Y, the model converter's 42, the embeddable through its
                // setters, the to-one's id, and the @PrePersist audit
                assertThat(p.rows(ROW + key))
                        .containsExactly("p1|PAID|Y|42|Hanoi|100000|2|null|app|pre:p1");
                em.getTransaction().begin();
            });
        }
    }

    @TckTest
    void ac_wrt_28_persist_with_a_cascade_all_to_one_detaches_the_callers_managed_target(TckDatabase db) {
        var persist = ModelPersist.of(COLUMNS, Long.class,
                new NewPersist("p2", InsPersistEntity.Status.NEW, false, "#7", null, null, 1L));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                InsSourceEntity source = em.find(InsSourceEntity.class, 1L);

                executor(em, InsPersistEntity.class).persist(persist);

                // getReference returned the caller's managed source, and detach cascades to it, as documented
                assertThat(em.contains(source)).isFalse();
            });
        }
    }

    @TckTest
    void ac_wrt_28_an_unnamed_nullable_attribute_with_a_database_default_is_written_as_null(TckDatabase db) {
        var persist = ModelPersist.of(COLUMNS, Long.class,
                new NewPersist("p3", null, null, null, null, null, null));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            p.jdbc("insert into ins_persist (code) values ('plain')");
            assertThat(p.rows("select region from ins_persist where code = 'plain'")).containsExactly("DFLT");
            Long[] key = new Long[1];
            inProbeTransaction(p, em -> {
                key[0] = executor(em, InsPersistEntity.class).persist(persist);
                em.getTransaction().commit();
                em.getTransaction().begin();
            });

            // region is not in the model, so it is written as the constructor leaves it, NULL; origin keeps "app"
            assertThat(p.rows(ROW + key[0])).containsExactly("p3|null|null|null|null|null|null|null|app|pre:p3");
        }
    }

    @TckTest
    void ac_wrt_28_persist_binds_a_to_one_named_by_its_target_id_as_a_reference(TckDatabase db) {
        var columns = InsertColumns.<NewPersist, InsPersistEntity>of(ROOT)
                .add(ColumnField.of(NewPersist.class, ROOT, "code", String.class), NewPersist::code)
                .add(ColumnField.of(NewPersist.class, ROOT, "source.id", Long.class), NewPersist::source);
        var persist = ModelPersist.of(columns, Long.class, new NewPersist("p6", null, null, null, null, null, 2L));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            Long[] key = new Long[1];
            inProbeTransaction(p, em -> {
                key[0] = executor(em, InsPersistEntity.class).persist(persist);
                // A reference to source 2, neither read nor created through the CascadeType.ALL to-one
                assertThat(p.statements()).hasSize(1);
                assertThat(writes(p.statements(), "insert into ins_persist")).hasSize(1);
                em.getTransaction().commit();
                em.getTransaction().begin();
            });

            assertThat(p.rows("select code, source_id from ins_persist where id = " + key[0]))
                    .containsExactly("p6|2");
        }
    }

    // ---- AC-WRT-13

    @TckTest
    void ac_wrt_13_persist_with_no_transaction_throws_mq2501_before_any_statement(TckDatabase db) {
        var persist = ModelPersist.of(COLUMNS, Long.class,
                new NewPersist("p4", InsPersistEntity.Status.NEW, true, "#1", null, null, null));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            p.forget();
            p.factory().inSession(em -> assertThatThrownBy(() -> executor(em, InsPersistEntity.class).persist(persist))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2501))
                    .hasMessageContaining("persist needs an active transaction"));
            assertThat(p.statements()).isEmpty();
        }
    }

    // ---- AC-WRT-31

    @TckTest
    void ac_wrt_31_persist_runs_on_a_provider_with_no_insert_support(TckDatabase db) {
        var persist = ModelPersist.of(COLUMNS, Long.class,
                new NewPersist("p5", InsPersistEntity.Status.PAID, false, "#5", null, null, null));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            Long[] key = new Long[1];
            inProbeTransaction(p, em -> JoinTestSupport.withoutServices(() -> {
                key[0] = executor(em, InsPersistEntity.class).persist(persist);
                em.getTransaction().commit();
                em.getTransaction().begin();
            }));

            assertThat(p.rows(ROW + key[0])).containsExactly("p5|PAID|N|5|null|null|null|null|app|pre:p5");
        }
    }

    // ---- AC-WRT-29 (R-WRT-39): MQ1805

    @TckTest
    void ac_wrt_29_persist_through_a_record_embeddable_throws_mq1805_before_any_statement(TckDatabase db) {
        TableField<InsRecordEmbeddedEntity, InsRecordEmbeddedEntity> root =
                TableField.root(InsRecordEmbeddedEntity.class);
        var columns = InsertColumns.<NewPoint, InsRecordEmbeddedEntity>of(root)
                .add(ColumnField.of(NewPoint.class, root, "point.x", Integer.class), NewPoint::x);
        var persist = ModelPersist.of(columns, Long.class, new NewPoint(3));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> assertThatThrownBy(
                    () -> executor(em, InsRecordEmbeddedEntity.class).persist(persist))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1805))
                    .hasMessageContaining("point.x is set on Point, a record"));
            assertThat(p.statements()).isEmpty();
        }
    }

    // ---- AC-WRT-28 (R-WRT-39): detach, generators, primitives, setters, ids

    @TckTest
    void ac_wrt_28_persist_leaves_the_entity_detached_when_the_flush_fails(TckDatabase db) {
        TableField<InsUuidEntity, InsUuidEntity> root = TableField.root(InsUuidEntity.class);
        var columns = InsertColumns.<CodeRow, InsUuidEntity>of(root)
                .add(ColumnField.of(CodeRow.class, root, "code", String.class), CodeRow::code);
        var persist = ModelPersist.of(columns, UUID.class, new CodeRow("dup"));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                var executor = executor(em, InsUuidEntity.class);
                executor.persist(persist);

                // The same unique code again fails the flush, and the entity it created is not left managed
                assertThatThrownBy(() -> executor.persist(persist)).isInstanceOf(PersistenceException.class);
                assertThat(em.unwrap(Session.class).getStatistics().getEntityCount()).isZero();
            });
        }
    }

    @TckTest
    void ac_wrt_28_persist_and_insert_write_a_root_whose_id_has_a_hibernate_uuid_generator(TckDatabase db) {
        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                var executor = executor(em, InsGeneratedUuidEntity.class);

                UUID key = executor.persist(QInsGeneratedUuidRow.persist(new InsGeneratedUuidRow("one")));
                long inserted = executor.insert(QInsGeneratedUuidRow.insert(List.of(
                        new InsGeneratedUuidRow("two"), new InsGeneratedUuidRow("three"))).build());
                em.getTransaction().commit();
                em.getTransaction().begin();

                assertThat(key).isNotNull();
                assertThat(inserted).isEqualTo(2);
                assertThat(p.rows("select label from ins_generated_uuid order by label"))
                        .containsExactly("one", "three", "two");
            });
        }
    }

    @TckTest
    void ac_wrt_28_persist_of_null_to_a_primitive_attribute_throws_mq1308_before_any_statement(TckDatabase db) {
        TableField<InsCtorEmbeddedEntity, InsCtorEmbeddedEntity> root = TableField.root(InsCtorEmbeddedEntity.class);
        var columns = InsertColumns.<RankRow, InsCtorEmbeddedEntity>of(root)
                .add(ColumnField.of(RankRow.class, root, "ranking", Integer.class), RankRow::rank);
        var persist = ModelPersist.of(columns, Long.class, new RankRow(null));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> assertThatThrownBy(
                    () -> executor(em, InsCtorEmbeddedEntity.class).persist(persist))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1308))
                    .hasMessageContaining("ranking is a primitive int"));
            assertThat(p.statements()).isEmpty();
        }
    }

    @TckTest
    void ac_wrt_26_persist_naming_a_generated_id_or_part_of_a_composite_id_throws_mq1802_before_any_statement(
            TckDatabase db) {
        TableField<InsPersistEntity, InsPersistEntity> root = TableField.root(InsPersistEntity.class);
        var named = ModelPersist.of(InsertColumns.<IdRow, InsPersistEntity>of(root)
                .add(ColumnField.of(IdRow.class, root, "id", Long.class), IdRow::id)
                .add(ColumnField.of(IdRow.class, root, "code", String.class), IdRow::code),
                Long.class, new IdRow(99L, "p9"));
        TableField<InsCompositeEntity, InsCompositeEntity> composite = TableField.root(InsCompositeEntity.class);
        var partial = ModelPersist.of(InsertColumns.<IdRow, InsCompositeEntity>of(composite)
                .add(ColumnField.of(IdRow.class, composite, "tenant", Long.class), IdRow::id),
                InsCompositeEntity.Key.class, new IdRow(1L, null));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                assertThatThrownBy(() -> executor(em, InsPersistEntity.class).persist(named))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1802))
                        .hasMessageContaining("writes the root's id");
                assertThatThrownBy(() -> executor(em, InsCompositeEntity.class).persist(partial))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1802))
                        .hasMessageContaining("writes part of the id");
            });
            assertThat(p.statements()).isEmpty();
        }
    }

    @TckTest
    void ac_wrt_29_a_constructor_only_embeddable_is_mq1805_under_persist_and_written_by_insert(TckDatabase db) {
        TableField<InsCtorEmbeddedEntity, InsCtorEmbeddedEntity> root = TableField.root(InsCtorEmbeddedEntity.class);
        var columns = InsertColumns.<SpotRow, InsCtorEmbeddedEntity>of(root)
                .add(ColumnField.of(SpotRow.class, root, "spot.x", Integer.class), SpotRow::x)
                .add(ColumnField.of(SpotRow.class, root, "ranking", Integer.class), SpotRow::rank);
        var persist = ModelPersist.of(columns, Long.class, new SpotRow(4, 1));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                var executor = executor(em, InsCtorEmbeddedEntity.class);

                assertThatThrownBy(() -> executor.persist(persist))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1805))
                        .hasMessageContaining("spot.x is set on Spot, an embeddable with no no-arg constructor");
                assertThat(p.statements()).isEmpty();

                assertThat(executor.insert(ValuesInsert.builder(columns, Long.class, List.of(new SpotRow(4, 1)))
                        .build())).isEqualTo(1);
                em.getTransaction().commit();
                em.getTransaction().begin();
                assertThat(p.rows("select x, ranking from ins_ctor_embedded")).containsExactly("4|1");
            });
        }
    }

    @TckTest
    void ac_wrt_28_persist_sets_a_property_through_a_setter_declared_above_the_overridden_getter(TckDatabase db) {
        TableField<InsPropertyChildEntity, InsPropertyChildEntity> root = TableField.root(InsPropertyChildEntity.class);
        var columns = InsertColumns.<LabelRow, InsPropertyChildEntity>of(root)
                .add(ColumnField.of(LabelRow.class, root, "label", String.class), LabelRow::label);
        var persist = ModelPersist.of(columns, Long.class, new LabelRow("kid"));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                Long key = executor(em, InsPropertyChildEntity.class).persist(persist);
                em.getTransaction().commit();
                em.getTransaction().begin();

                assertThat(p.rows("select label from ins_property_child where id = " + key)).containsExactly("kid");
            });
        }
    }

    private static <E> ModelQueryExecutor<E> executor(EntityManager em, Class<E> root) {
        return ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults());
    }

    /**
     * Runs {@code work} in a transaction of a new session of the probe factory, after forgetting the statements
     * recorded so far, and rolls back what is still open.
     */
    private static void inProbeTransaction(InsertProbes p, Consumer<EntityManager> work) {
        p.forget();
        p.factory().inSession(em -> {
            em.getTransaction().begin();
            try {
                work.accept(em);
            } finally {
                em.getTransaction().rollback();
            }
        });
    }
}
