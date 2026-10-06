package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.vnd.ins.InsPersistEntity;
import com.rey.modelquery.tck.vnd.ins.InsRecordEmbeddedEntity;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import java.util.function.Consumer;

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
