package com.rey.modelquery.tck.col;

import static com.rey.modelquery.tck.col.TreatTestSupport.CARD;
import static com.rey.modelquery.tck.col.TreatTestSupport.HOLDERS;
import static com.rey.modelquery.tck.col.TreatTestSupport.ID;
import static com.rey.modelquery.tck.col.TreatTestSupport.NUMBER;
import static com.rey.modelquery.tck.col.TreatTestSupport.PAYMENT;
import static com.rey.modelquery.tck.col.TreatTestSupport.ROOT;
import static com.rey.modelquery.tck.col.TreatTestSupport.STATUS;
import static com.rey.modelquery.tck.col.TreatTestSupport.factory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.tck.col.TreatTestSupport.Card;
import com.rey.modelquery.tck.col.TreatTestSupport.CountingRenderer;
import com.rey.modelquery.tck.col.TreatTestSupport.Holder;
import com.rey.modelquery.tck.col.TreatTestSupport.Payment;
import com.rey.modelquery.tck.col.TreatTestSupport.ServingProvider;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;

/** Executor refusals run before any statement on every read and write path (AC-VND-16). */
class TreatExecutionTest {

    @TckTest
    void ac_vnd_16_no_renderer_refuses_reads_exports_and_writes_before_any_statement(TckDatabase db) {
        assertThat(new ProviderSupport() {
            @Override
            public boolean supports(EntityManagerFactory emf) {
                return true;
            }

            @Override
            public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
                return query.getResultStream();
            }
        }.treats(null)).isEmpty();
        var query = ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(ID, NUMBER))
                .primaryKey(PrimaryKey.of(ID)).build();
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "paid")
                .where(f -> f.eq(NUMBER, "1234")).build();
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.eq(NUMBER, "1234")).build();
        var exported = new ArrayList<String>();
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                factory.inSession(em -> {
                    ModelQueryExecutor<Payment> executor =
                            ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                    assertMissingRenderer(() -> executor.list(query, Limit.of(3)));
                    assertMissingRenderer(() -> executor.count(query));
                    assertMissingRenderer(() -> executor.page(query, PageSpec.of(0, 3), CountMode.COUNT));
                    assertMissingRenderer(() -> executor.export(query, ExportOptions.of(3), rows -> rows,
                            exported::add));
                    em.getTransaction().begin();
                    try {
                        assertMissingRenderer(() -> executor.update(update));
                        assertMissingRenderer(() -> executor.delete(delete));
                    } finally {
                        em.getTransaction().rollback();
                    }
                });
            }
        })).isEmpty();
        assertThat(exported).isEmpty();
    }

    @TckTest
    void ac_vnd_16_executor_passes_the_factory_renderer_to_core_and_scratch_queries(TckDatabase db) {
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                CountingRenderer renderer = new CountingRenderer();
                ServingProvider.serving(factory, renderer, () -> factory.inSession(em -> {
                    var query = ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(ID, NUMBER))
                            .primaryKey(PrimaryKey.of(ID))
                            .customize((spec, joins, statement, cb, phase) -> { throw new Built(); }).build();
                    var executor = ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                    assertThatThrownBy(() -> executor.list(query, Limit.of(3))).isExactlyInstanceOf(Built.class);
                    assertThat(renderer.calls).isPositive();
                }));
            }
        })).isEmpty();
    }

    private static final class Built extends RuntimeException {}

    @TckTest
    void ac_vnd_16_primary_key_first_refuses_before_empty_or_nonempty_step_one(TckDatabase db) {
        try (SessionFactory schema = TreatTestSupport.schema(db)) {
            schema.inTransaction(em -> {
                for (long id = 1; id <= 3; id++) {
                    Card card = new Card();
                    card.id = id;
                    card.number = "1234";
                    em.persist(card);
                }
            });
            for (boolean empty : List.of(false, true)) {
                var plain = ModelQuery.builder(ROOT, row -> String.valueOf(row.get(ID))).select(SelectSet.of(ID))
                        .primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc())
                        .where(f -> empty ? f.eq(ID, -1L) : f)
                        .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
                schema.inSession(em -> assertThat(ModelQueryExecutor.create(em, Payment.class,
                        ModelQueryConfig.defaults()).page(plain, PageSpec.of(1, 1), CountMode.NO_COUNT).content())
                        .hasSize(empty ? 0 : 1));
                var treated = ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(ID, NUMBER))
                        .primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc())
                        .where(f -> empty ? f.eq(ID, -1L) : f)
                        .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
                assertThat(SqlSnapshots.capture(db, ds -> {
                    try (SessionFactory factory = factory(ds)) {
                        factory.inSession(em -> {
                            var executor = ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                            assertMissingRenderer(() -> executor.page(treated, PageSpec.of(1, 1), CountMode.NO_COUNT));
                            assertMissingRenderer(() -> executor.page(treated, PageSpec.of(1, 1), CountMode.COUNT));
                        });
                    }
                })).isEmpty();
            }
        }
    }

    @TckTest
    void ac_vnd_16_keyset_stream_fetch_and_persist_returning_refuse_before_any_statement(TckDatabase db) {
        var query = ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(ID, NUMBER))
                .primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc()).keyset().build();
        var returning = ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(NUMBER)).build();
        var persist = ModelPersist.of(InsertColumns.<String, Payment>of(ROOT).add(STATUS, value -> value),
                Long.class, "paid");
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                factory.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                    assertMissingRenderer(() -> executor.page(query, KeysetSpec.first(1)));
                    assertMissingRenderer(() -> executor.stream(query, Limit.of(3), Stream::count));
                    assertMissingRenderer(() -> executor.list(fetchQuery(true), Limit.of(3)));
                    assertMissingRenderer(() -> executor.count(fetchQuery(false)));
                    em.getTransaction().begin();
                    try {
                        assertMissingRenderer(() -> executor.persist(persist, returning));
                    } finally {
                        em.getTransaction().rollback();
                    }
                });
            }
        })).isEmpty();
    }

    @TckTest
    void ac_vnd_17_factory_renderer_renders_admitted_reads_and_child_keys_and_returning_models_are_refused(
            TckDatabase db) {
        // The factory's renderer renders an admitted treated read; a treated child key and a treated returning model
        // reach M28's own refusals (MQ4016, R-VND-26) instead of MQ4014, before the renderer is asked; an untreated
        // returning model reaches the transaction check.
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                CountingRenderer renderer = new CountingRenderer();
                ServingProvider.serving(factory, renderer, () -> factory.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                    // Stopped by its customizer once built, so no statement runs.
                    var admitted = ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(ID, NUMBER))
                            .customize((spec, joins, statement, cb, phase) -> { throw new Built(); }).build();
                    assertThatThrownBy(() -> executor.list(admitted, Limit.of(3))).isExactlyInstanceOf(Built.class);
                    assertThat(renderer.calls).isPositive();
                    int calls = renderer.calls;
                    assertTreatRefused(() -> executor.list(fetchQuery(true), Limit.of(0)));
                    var treated = ModelQuery.builder(ROOT, row -> row.get(NUMBER))
                            .select(SelectSet.of(NUMBER)).build();
                    var plain = ModelQuery.builder(ROOT, row -> row.get(STATUS))
                            .select(SelectSet.of(STATUS)).build();
                    var persist = ModelPersist.of(InsertColumns.<String, Payment>of(ROOT)
                            .add(STATUS, value -> value), Long.class, "paid");
                    assertTreatRefused(() -> executor.persist(persist, treated));
                    assertThat(renderer.calls).isEqualTo(calls); // refused before any treated node is rendered
                    assertThatThrownBy(() -> executor.persist(persist, plain))
                            .isExactlyInstanceOf(ModelQueryExecutionException.class)
                            .extracting(failure -> ((ModelQueryExecutionException) failure).code())
                            .isEqualTo(MqCode.MQ2501);
                }));
            }
        })).isEmpty();
    }

    private static void assertTreatRefused(Runnable action) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4016);
    }

    @TckTest
    void ac_col_48_returning_reads_subtype_attributes_and_to_one_ids(TckDatabase db) {
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                factory.inSession(em -> {
                    var cb = em.getCriteriaBuilder();
                    var options = RenderOptions.portable().withTreatRenderer(new CountingRenderer());
                    ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(NUMBER)).build()
                            .checkReturning(cb, options);
                    TableField<Card, Holder> issuer = TableField.join(CARD, "issuer", JoinType.LEFT);
                    var issuerId = ColumnField.of(String.class, issuer, "id", Long.class);
                    ModelQuery.builder(ROOT, row -> String.valueOf(row.get(issuerId)))
                            .select(SelectSet.of(issuerId)).build()
                            .checkReturning(cb, options);
                    var treatedJoinId = ColumnField.of(String.class, PAYMENT.treat(Card.class), "id", Long.class);
                    ModelQuery.builder(HOLDERS, row -> String.valueOf(row.get(treatedJoinId)))
                            .select(SelectSet.of(treatedJoinId)).build().checkReturning(cb, options);
                    var missing = ColumnField.of(String.class, CARD, "missing", String.class);
                    assertThatThrownBy(() -> ModelQuery.builder(ROOT, row -> row.get(missing))
                            .select(SelectSet.of(missing)).build().checkReturning(cb, options))
                            .isExactlyInstanceOf(ModelQueryDefinitionException.class)
                            .extracting(failure -> ((ModelQueryDefinitionException) failure).code())
                            .isEqualTo(MqCode.MQ1002);
                    var absentJoin = ColumnField.of(String.class, TableField.join(CARD, "missing", JoinType.LEFT),
                            "id", Long.class);
                    assertThatThrownBy(() -> ModelQuery.builder(ROOT, row -> "")
                            .select(SelectSet.of(absentJoin)).build().checkReturning(cb, options))
                            .isExactlyInstanceOf(ModelQueryDefinitionException.class)
                            .extracting(failure -> ((ModelQueryDefinitionException) failure).code())
                            .isEqualTo(MqCode.MQ1002);
                    var issuerLabel = ColumnField.of(String.class, issuer, "label", String.class);
                    var deepId = ColumnField.of(String.class, TableField.join(issuer, "payment", JoinType.LEFT),
                            "id", Long.class);
                    var issuerEntity = ColumnField.of(String.class, CARD, "issuer", Holder.class);
                    for (var refused : List.of(issuerLabel, deepId, issuerEntity)) {
                        assertThatThrownBy(() -> ModelQuery.builder(ROOT, row -> "")
                                .select(SelectSet.of(refused)).build().checkReturning(cb, options))
                                .isExactlyInstanceOf(ModelQueryDefinitionException.class)
                                .extracting(failure -> ((ModelQueryDefinitionException) failure).code())
                                .isEqualTo(MqCode.MQ1809);
                    }
                });
            }
        })).isEmpty();
    }

    @TckTest
    void ac_vnd_16_a_factory_check_with_a_renderer_does_not_authorize_an_executor_without_one(TckDatabase db) {
        var query = ModelQuery.builder(ROOT, row -> row.get(NUMBER)).select(SelectSet.of(NUMBER)).build();
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                factory.inSession(em -> {
                    ServingProvider.serving(factory, new CountingRenderer(), () -> {
                        var supported = ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                        assertThat(supported.list(query, Limit.of(0))).isEmpty();
                    });
                    ServingProvider.serving(factory, null, () -> {
                        var executor = ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                        assertMissingRenderer(() -> executor.list(query, Limit.of(0)));
                        assertMissingRenderer(() -> executor.list(query, Limit.of(0)));
                        var plain = ModelQuery.builder(ROOT, row -> "plain").select(SelectSet.of(ID)).build();
                        assertThat(executor.list(plain, Limit.of(0))).isEmpty();
                        assertThat(executor.list(plain, Limit.of(0))).isEmpty();
                    });
                });
            }
        })).isEmpty();
    }

    @TckTest
    void ac_vnd_16_no_renderer_scans_are_shared_by_executors_and_children_but_not_factories(TckDatabase db) {
        var scans = new AtomicInteger();
        var query = countingFetchQuery(scans);
        scans.set(0);
        assertThat(SqlSnapshots.capture(db, ds -> {
            for (int factoryIndex = 1; factoryIndex <= 2; factoryIndex++) {
                try (SessionFactory factory = factory(ds)) {
                    for (int executorIndex = 0; executorIndex < 2; executorIndex++) {
                        factory.inSession(em -> {
                            var executor = ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                            assertThat(executor.list(query, Limit.of(0))).isEmpty();
                        });
                        assertThat(scans).hasValue(2 * factoryIndex);
                    }
                }
            }
        })).isEmpty();
    }

    @TckTest
    void ac_vnd_16_concurrent_first_executors_share_a_safe_no_renderer_scan(TckDatabase db) {
        var scans = new AtomicInteger();
        var query = countingFetchQuery(scans);
        scans.set(0);
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                var workers = Executors.newFixedThreadPool(4);
                try {
                    var runs = new ArrayList<CompletableFuture<Void>>();
                    for (int i = 0; i < 12; i++) {
                        runs.add(CompletableFuture.runAsync(() -> factory.inSession(em -> {
                            var executor = ModelQueryExecutor.create(em, Payment.class, ModelQueryConfig.defaults());
                            assertThat(executor.list(query, Limit.of(0))).isEmpty();
                        }), workers));
                    }
                    CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new))
                            .orTimeout(30, TimeUnit.SECONDS).join();
                } finally {
                    workers.shutdownNow();
                }
                assertThat(scans).hasValue(2);
            }
        })).isEmpty();
    }

    private static ModelQuery<Payment, Long, String> countingFetchQuery(AtomicInteger scans) {
        var leaf = FetchPlan.of(SelectSet.of(ID, STATUS));
        return ModelQuery.builder(ROOT, row -> row.get(STATUS)).primaryKey(PrimaryKey.of(ID))
                .fetch(leaf.child(countingChild("children", scans),
                        leaf.child(countingChild("grandchildren", scans), leaf))).build();
    }

    private static ChildField<String, String> countingChild(String name, AtomicInteger scans) {
        return new ChildField<>() {
            @Override public String name() { return name; }
            @Override public ColumnField<String, ?, ?> key() { return ID; }
            @Override public Optional<ColumnField<String, ?, ?>> foreignKey() { return Optional.of(ID); }
            @Override public Optional<TableField<?, ?>> through() {
                scans.incrementAndGet();
                return Optional.empty();
            }
            @Override public boolean isToMany() { return true; }
            @Override public ModelQuery.Builder<?, ?, String> query() {
                return ModelQuery.builder(ROOT, row -> row.get(STATUS)).select(SelectSet.of(ID, STATUS))
                        .primaryKey(PrimaryKey.of(ID));
            }
            @Override public String with(String parent, List<String> children) { return parent; }
        };
    }

    /** A treated parent key, or a treat found only in the child's own filter. */
    static ModelQuery<Payment, Long, String> fetchQuery(boolean treatedKey) {
        var childId = ColumnField.of(String.class, HOLDERS, "id", Long.class);
        var childKey = ColumnField.of(String.class, HOLDERS, "label", String.class);
        var innerNumber = ColumnField.of(String.class, PAYMENT.treat(Card.class), "number", String.class);
        ChildField<String, String> child = new ChildField<>() {
            @Override public String name() { return "children"; }
            @Override public ColumnField<String, ?, ?> key() { return treatedKey ? NUMBER : STATUS; }
            @Override public Optional<ColumnField<String, ?, ?>> foreignKey() { return Optional.of(childKey); }
            @Override public Optional<TableField<?, ?>> through() { return Optional.empty(); }
            @Override public boolean isToMany() { return true; }
            @Override public ModelQuery.Builder<?, ?, String> query() {
                return ModelQuery.builder(HOLDERS, row -> row.get(childKey)).select(SelectSet.of(childId, childKey))
                        .primaryKey(PrimaryKey.of(childId));
            }
            @Override public String with(String parent, List<String> children) { return parent; }
        };
        return ModelQuery.builder(ROOT, row -> "parent").primaryKey(PrimaryKey.of(ID))
                .fetch(FetchPlan.of(SelectSet.of(ID)).child(child, FetchPlan.of(SelectSet.of(childId, childKey)),
                        query -> treatedKey ? query : query.where(f -> f.eq(innerNumber, "1234"))))
                .build();
    }

    private static void assertMissingRenderer(Runnable action) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4014);
    }
}
