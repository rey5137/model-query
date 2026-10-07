package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.writes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.vnd.ins.InsPersistEntity;
import com.rey.modelquery.tck.vnd.ins.InsPersistView;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsSourceRef;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import com.rey.modelquery.tck.vnd.ins.QInsPersistView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code persist} returning a model on every Tier-1 vendor: the model built from the flushed entity with one insert
 * and no select, and {@code MQ1809} before any statement for a refused clause or an unfillable column (spec api/14
 * R-WRT-48).
 */
class PersistReturningTest {

    /** A {@code persist} row: the amount as {@code #42}, the source by id. */
    record NewPersist(String code, InsPersistEntity.Status status, Boolean flagged, String amount, String city,
            Long source) {}

    private static final TableField<InsPersistEntity, InsPersistEntity> ROOT = QInsPersistView.ROOT;

    private static final InsertColumns<NewPersist, InsPersistEntity> COLUMNS =
            InsertColumns.<NewPersist, InsPersistEntity>of(ROOT)
                    .add(ColumnField.of(NewPersist.class, ROOT, "code", String.class), NewPersist::code)
                    .add(ColumnField.of(NewPersist.class, ROOT, "status", InsPersistEntity.Status.class),
                            NewPersist::status)
                    .add(ColumnField.of(NewPersist.class, ROOT, "flagged", Boolean.class), NewPersist::flagged)
                    .add(ColumnField.of(NewPersist.class, ROOT, "amount", String.class, Long.class,
                            InsertValuesTest.RefConverter.INSTANCE), NewPersist::amount)
                    .add(ColumnField.of(NewPersist.class, ROOT, "address.city", String.class), NewPersist::city)
                    .add(ColumnField.of(NewPersist.class, ROOT, "source", Long.class), NewPersist::source);

    /** Every root column, the embeddable path and the to-one's id under the {@code INNER} join. */
    private static final ModelQuery<InsPersistEntity, Long, InsPersistView> VIEW = QInsPersistView.query()
            .select(QInsPersistView.ALL.with(QInsPersistView.SOURCE)).build();

    // ---- AC-WRT-38

    @TckTest
    void ac_wrt_38_persist_returning_fills_the_generated_id_pre_persist_and_constructor_values_with_one_insert(
            TckDatabase db) {
        var persist = ModelPersist.of(COLUMNS, Long.class,
                new NewPersist("p1", InsPersistEntity.Status.PAID, true, "#42", "Hanoi", 2L));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                InsPersistView view = executor(em).persist(persist, VIEW);

                // One insert, and no select: neither the row read back nor the source reference initialized
                assertThat(p.statements()).hasSize(1);
                assertThat(writes(p.statements(), "insert into ins_persist")).hasSize(1);
                assertThat(em.getEntityManagerFactory().getPersistenceUnitUtil()
                        .isLoaded(em.getReference(InsSourceEntity.class, 2L))).isFalse();
                assertThat(view.id()).isNotNull();
                // The model converter's #42, the JPA converter's true, the constructor's origin, the @PrePersist
                // audit; region, which the row does not name, as the constructor left it
                assertThat(view).isEqualTo(new InsPersistView(view.id(), "p1", InsPersistEntity.Status.PAID, true,
                        "#42", null, "app", "pre:p1", "Hanoi", Optional.of(new InsSourceRef(2L))));
                em.getTransaction().commit();
                // The generated id is the row's
                assertThat(p.rows("select code, origin, audit from ins_persist where id = " + view.id()))
                        .containsExactly("p1|app|pre:p1");
                em.getTransaction().begin();
            });
        }
    }

    @TckTest
    void ac_wrt_38_persist_returning_fills_null_through_an_inner_join_whose_foreign_key_is_null(TckDatabase db) {
        var persist = ModelPersist.of(COLUMNS, Long.class, new NewPersist("p2", null, null, null, null, null));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                InsPersistView view = executor(em).persist(persist, VIEW);

                // A read through the INNER join would drop the row; the model has no source instead
                assertThat(view.source()).isEmpty();
                assertThat(view.city()).isNull();
                assertThat(view.amount()).isNull();
                assertThat(view.audit()).isEqualTo("pre:p2");
                assertThat(p.statements()).hasSize(1);
            });
        }
    }

    @TckTest
    void ac_wrt_38_persist_returning_fills_null_under_an_embeddable_the_entity_holds_as_null(TckDatabase db) {
        // No address column, so the entity keeps the constructor's null address
        var persist = ModelPersist.of(InsertColumns.<NewPersist, InsPersistEntity>of(ROOT)
                .add(ColumnField.of(NewPersist.class, ROOT, "code", String.class), NewPersist::code), Long.class,
                new NewPersist("p4", null, null, null, null, null));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                InsPersistView view = executor(em).persist(persist, VIEW);

                assertThat(view.code()).isEqualTo("p4");
                assertThat(view.city()).isNull();
                assertThat(p.statements()).hasSize(1);
            });
        }
    }

    @TckTest
    void ac_wrt_38_persist_returning_a_where_whose_filters_were_all_skipped_is_accepted_on_every_call(
            TckDatabase db) {
        ModelQuery<InsPersistEntity, Long, InsPersistView> skipped = QInsPersistView.query()
                .select(QInsPersistView.ALL)
                .where(f -> f.eq(QInsPersistView.CODE, Optional.<String>empty()))
                .build();

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                // The second call finds the query checked on this factory
                for (String code : List.of("p5", "p6")) {
                    var persist = ModelPersist.of(COLUMNS, Long.class,
                            new NewPersist(code, InsPersistEntity.Status.NEW, false, "#5", "Hue", 1L));
                    assertThat(executor(em).persist(persist, skipped).code()).isEqualTo(code);
                }
                assertThat(writes(p.statements(), "insert into ins_persist")).hasSize(2);
                assertThat(p.statements()).hasSize(2);
            });
        }
    }

    @TckTest
    void ac_wrt_38_persist_returning_runs_after_map_and_the_finisher_of_the_query_once(TckDatabase db) {
        var persist = ModelPersist.of(COLUMNS, Long.class,
                new NewPersist("p3", InsPersistEntity.Status.NEW, false, "#7", "Hue", 1L));
        List<String> mapped = new ArrayList<>();
        ModelQuery<InsPersistEntity, Long, InsPersistView> finished = QInsPersistView.query()
                .select(QInsPersistView.ALL)
                .afterMap((view, row) -> mapped.add(row.get(QInsPersistView.CITY)))
                .finisher(view -> new InsPersistView(view.id(), view.code().toUpperCase(), view.status(),
                        view.flagged(), view.amount(), view.region(), view.origin(), view.audit(), view.city(),
                        view.source()))
                .build();

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> {
                InsPersistView view = executor(em).persist(persist, finished);

                assertThat(mapped).containsExactly("Hue");
                assertThat(view.code()).isEqualTo("P3");
                assertThat(view.amount()).isEqualTo("#7");
                // Not selected, so not filled
                assertThat(view.source()).isEmpty();
                assertThat(p.statements()).hasSize(1);
            });
        }
    }

    @TckTest
    void ac_wrt_38_persist_returning_a_query_with_another_clause_throws_mq1809_before_any_statement(TckDatabase db) {
        var refused = new LinkedHashMap<String, ModelQuery<InsPersistEntity, Long, InsPersistView>>();
        refused.put("where(...)", QInsPersistView.query().select(QInsPersistView.ALL)
                .where(f -> f.eq(QInsPersistView.CODE, "x")).build());
        refused.put("having(...)", QInsPersistView.query().select(SelectSet.of(QInsPersistView.CODE))
                .groupBy(QInsPersistView.CODE)
                .having(h -> h.gt(Agg.<InsPersistView>count(ROOT), 0L)).build());
        refused.put("groupBy(...)", QInsPersistView.query().select(SelectSet.of(QInsPersistView.CODE))
                .groupBy(QInsPersistView.CODE).build());
        refused.put("a fetch plan", QInsPersistView.query().fetch(FetchPlan.of(QInsPersistView.ALL)).build());
        refused.put("customize(...)", QInsPersistView.query().select(QInsPersistView.ALL)
                .customize((spec, joins, query, cb, phase) -> { }).build());
        refused.put("orderBy(...)", QInsPersistView.query().select(QInsPersistView.ALL)
                .orderBy(QInsPersistView.CODE.asc()).build());
        refused.put("keyset()", QInsPersistView.query().select(QInsPersistView.ALL).keyset().build());
        refused.put("primaryKeyFirst(...)", QInsPersistView.query().select(QInsPersistView.ALL)
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(100)).build());

        assertRefused(db, refused);
    }

    @TckTest
    void ac_wrt_38_persist_returning_an_unfillable_column_throws_mq1809_before_any_statement(TckDatabase db) {
        TableField<InsPersistEntity, InsSourceEntity> filtered = TableField.<InsPersistEntity, InsSourceEntity>join(
                ROOT, "source", JoinType.INNER).as("s").on((source, cb) -> cb.isNotNull(source.get("code")));
        var refused = new LinkedHashMap<String, ModelQuery<InsPersistEntity, Long, InsPersistView>>();
        refused.put("source.code: persist returning a model fills it from the flushed entity, without a statement, "
                + "and it is on the join", select(ColumnField.of(InsPersistView.class, QInsPersistView.SOURCE_TABLE,
                        "code", String.class)));
        refused.put("source.id: persist returning a model fills it from the flushed entity, without a statement, "
                + "and it is on the join with on(...)", select(ColumnField.of(InsPersistView.class, filtered, "id",
                        Long.class)));
        refused.put("an expression", select(Expr.coalesce(QInsPersistView.CODE, "none")));
        refused.put("an aggregate", QInsPersistView.query()
                .select(SelectSet.of(Agg.<InsPersistView>count(ROOT))).build());
        refused.put("the association source itself; select its target's id", select(
                ColumnField.of(InsPersistView.class, ROOT, "source", InsSourceEntity.class)));

        assertRefused(db, refused);
    }

    /** A query selecting the root's id and {@code extra}. */
    private static ModelQuery<InsPersistEntity, Long, InsPersistView> select(
            SelectField<InsPersistView, ?> extra) {
        return QInsPersistView.query().select(SelectSet.of(QInsPersistView.ID, extra)).build();
    }

    /**
     * Runs {@code persist} returning each query of {@code refused} on one factory, twice, since a check that threw
     * runs again, and expects {@code MQ1809} naming its key, with no statement.
     */
    private static void assertRefused(TckDatabase db,
            Map<String, ModelQuery<InsPersistEntity, Long, InsPersistView>> refused) {
        var persist = ModelPersist.of(COLUMNS, Long.class,
                new NewPersist("x1", InsPersistEntity.Status.NEW, true, "#1", "Hanoi", 1L));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            inProbeTransaction(p, em -> refused.forEach((named, query) -> {
                for (int run = 0; run < 2; run++) {
                    assertThatThrownBy(() -> executor(em).persist(persist, query))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1809))
                            .hasMessageContaining(named);
                }
                assertThat(p.statements()).as(named).isEmpty();
            }));
        }
    }

    private static ModelQueryExecutor<InsPersistEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, InsPersistEntity.class, ModelQueryConfig.defaults());
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
