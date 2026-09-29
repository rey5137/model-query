package com.rey.modelquery.tck.qry;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/** The {@code ModelQuery} builder, primary keys, {@code afterMap} and {@code QueryCustomizer} (spec api/11). */
class ModelQueryTest {

    record View(Long id, String status, String customerName, BigDecimal total) {}

    /** A mutable model with a derived field, the {@code afterMap} case. */
    static final class Labelled {
        Long id;
        String status;
        String customerName;
        String label;
    }

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER =
            TableField.join(ROOT, "customer", INNER);

    private static final ColumnField<View, OrderEntity, Long> ID =
            ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final ColumnField<View, OrderEntity, String> STATUS =
            ColumnField.of(View.class, ROOT, "status", String.class);
    private static final ColumnField<View, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(View.class, ROOT, "total", BigDecimal.class);
    private static final ColumnField<View, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(View.class, CUSTOMER, "name", String.class);

    private static final ColumnField<Labelled, OrderEntity, Long> L_ID =
            ColumnField.of(Labelled.class, ROOT, "id", Long.class);
    private static final ColumnField<Labelled, OrderEntity, String> L_STATUS =
            ColumnField.of(Labelled.class, ROOT, "status", String.class);
    private static final ColumnField<Labelled, OrderEntity, BigDecimal> L_TOTAL =
            ColumnField.of(Labelled.class, ROOT, "total", BigDecimal.class);
    private static final ColumnField<Labelled, CustomerEntity, String> L_CUSTOMER_NAME =
            ColumnField.of(Labelled.class, CUSTOMER, "name", String.class);

    private static final RowMapper<View> VIEW_MAPPER =
            row -> new View(row.get(ID), row.get(STATUS), row.get(CUSTOMER_NAME), row.get(TOTAL));

    private static final ColumnSet<View> DEFAULT = ColumnSet.of(STATUS, CUSTOMER_NAME, TOTAL);

    /** A constant shared by every thread of the concurrency test (INV-9). */
    private static final ModelQuery<OrderEntity, Long, View> SHARED = ModelQuery.builder(ROOT, VIEW_MAPPER)
            .columns(ColumnSet.of(ID, STATUS, CUSTOMER_NAME, TOTAL))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(TOTAL.desc().nullsLast(), ID.asc())
            .keyset()
            .build();

    private static <M> List<M> run(EntityManager em, ModelQuery<?, ?, M> q, Phase phase) {
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), phase);
        return em.createQuery(built.query()).getResultList().stream().map(built::map).toList();
    }

    // ---- AC-QRY-01

    @Test
    void ac_qry_01_a_built_query_exposes_no_mutator_and_its_lists_throw() {
        var q = ModelQuery.builder(ROOT, VIEW_MAPPER).columns(DEFAULT).orderBy(ID.asc()).build();
        for (Method m : ModelQuery.class.getMethods()) {
            if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            assertThat(m.getName()).as(m.toString()).doesNotStartWith("set").doesNotStartWith("add")
                    .doesNotStartWith("put").doesNotStartWith("remove").doesNotStartWith("with");
        }
        assertThat(ModelQuery.class.getFields()).isEmpty();
        assertThatThrownBy(() -> q.orderBy().add(ID.desc())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> q.columns().columns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> q.spec().columns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ac_qry_01_two_queries_built_from_one_builder_instance_are_independent() {
        var base = ModelQuery.builder(ROOT, VIEW_MAPPER).columns(DEFAULT).primaryKey(PrimaryKey.of(ID));
        ModelQuery<OrderEntity, Long, View> plain = base.build();
        ModelQuery<OrderEntity, Long, View> keyset = base.keyset().orderBy(TOTAL.asc()).build();
        ModelQuery<OrderEntity, Long, View> again = base.build();

        assertThat(plain.isKeyset()).isFalse();
        assertThat(plain.orderBy()).isEmpty();
        assertThat(keyset.isKeyset()).isTrue();
        assertThat(keyset.orderBy()).hasSize(1);
        assertThat(again.isKeyset()).isFalse();
        assertThat(again.orderBy()).isEmpty();
    }

    // ---- AC-QRY-02

    @Test
    void ac_qry_02_keyset_without_a_primary_key_throws_mq1201_naming_the_model() {
        var noKey = ModelQuery.builder(ROOT, VIEW_MAPPER).columns(DEFAULT);
        assertThatThrownBy(() -> noKey.keyset().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> assertThat(e.code())
                        .isEqualTo(MqCode.MQ1201))
                .hasMessageContaining("MQ1201").hasMessageContaining("View").hasMessageContaining("keyset()");
        assertThatThrownBy(() -> noKey.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10_000)).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> assertThat(e.code())
                        .isEqualTo(MqCode.MQ1201))
                .hasMessageContaining("View").hasMessageContaining("primaryKeyFirst");
        var keyed = noKey.primaryKey(PrimaryKey.of(ID));
        assertThat(keyed.keyset().build().isKeyset()).isTrue();
        assertThat(keyed.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(5)).build().primaryKeyFirst()).isPresent();
    }

    // ---- AC-QRY-03

    @TckTest
    void ac_qry_03_a_column_set_omitting_the_primary_key_still_selects_it_for_paging(TckDatabase db) {
        var paged = ModelQuery.builder(ROOT, VIEW_MAPPER)
                .columns(ColumnSet.of(STATUS, CUSTOMER_NAME, TOTAL))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(ID.asc())
                .keyset()
                .build();
        var unpaged = ModelQuery.builder(ROOT, VIEW_MAPPER)
                .columns(ColumnSet.of(STATUS, CUSTOMER_NAME, TOTAL))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(ID.asc())
                .build();
        List<View>[] result = newHolder();
        List<Long> keyOnly = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "qry-03-primary-key-added", ds -> {
            try (SessionFactory sf = sessionFactory(ds)) {
                sf.inSession(em -> {
                    result[0] = run(em, paged, Phase.MODEL);
                    result[1] = run(em, unpaged, Phase.MODEL);
                    BuiltQuery<View> keys = paged.buildQuery(em.getCriteriaBuilder(), Phase.PRIMARY_KEY);
                    em.createQuery(keys.query()).getResultList().forEach(t -> keyOnly.add(t.get("c0", Long.class)));
                });
            }
        });
        assertThat(result[0]).isNotEmpty().allSatisfy(v -> assertThat(v.id()).isNotNull());
        assertThat(result[0].stream().map(View::id).toList()).isSorted();
        // Without keyset() or primaryKeyFirst(...) nothing needs the key, so it is not added.
        assertThat(result[1]).hasSameSizeAs(result[0]).allSatisfy(v -> assertThat(v.id()).isNull());
        assertThat(keyOnly).isEqualTo(result[0].stream().map(View::id).toList());
    }

    // ---- AC-QRY-04

    @TckTest
    void ac_qry_04_after_map_runs_once_per_row_sees_every_selected_column_and_its_effect_is_kept(TckDatabase db) {
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> sawAll = new ArrayList<>();
        RowMapper<Labelled> mapper = RowMapper.setters(Labelled::new)
                .bind(L_ID, (m, v) -> m.id = v)
                .bind(L_STATUS, (m, v) -> m.status = v)
                .bind(L_CUSTOMER_NAME, (m, v) -> m.customerName = v);
        var q = ModelQuery.builder(ROOT, mapper)
                .columns(ColumnSet.of(L_ID, L_STATUS, L_CUSTOMER_NAME, L_TOTAL))
                .afterMap((m, row) -> {
                    calls.incrementAndGet();
                    sawAll.add(row.isSelected(L_ID) && row.isSelected(L_STATUS) && row.isSelected(L_CUSTOMER_NAME)
                            && row.isSelected(L_TOTAL));
                    m.label = row.get(L_CUSTOMER_NAME) + "/" + row.get(L_STATUS) + "/" + row.get(L_TOTAL);
                })
                .build();
        var records = ModelQuery.builder(ROOT, VIEW_MAPPER)
                .columns(ColumnSet.of(ID, STATUS, CUSTOMER_NAME, TOTAL))
                .finisher(v -> new View(v.id(), v.status().toLowerCase(), v.customerName(), v.total()))
                .build();
        List<Labelled> mapped;
        List<View> finished;
        try (SessionFactory sf = sessionFactory(db)) {
            List<Labelled>[] out = newHolder();
            List<View>[] fin = newHolder();
            sf.inSession(em -> {
                out[0] = run(em, q, Phase.MODEL);
                fin[0] = run(em, records, Phase.MODEL);
            });
            mapped = out[0];
            finished = fin[0];
        }
        assertThat(mapped).isNotEmpty();
        assertThat(calls.get()).isEqualTo(mapped.size());
        assertThat(sawAll).hasSize(mapped.size()).containsOnly(true);
        assertThat(mapped).allSatisfy(m -> assertThat(m.label).startsWith(m.customerName + "/" + m.status + "/"));
        assertThat(finished).isNotEmpty().allSatisfy(v -> assertThat(v.status()).isEqualTo(v.status().toLowerCase()));
    }

    // ---- AC-QRY-05

    @TckTest
    void ac_qry_05_a_customizer_added_selection_is_not_readable_through_row(TckDatabase db) {
        QueryCustomizer extra = (spec, joins, query, cb, phase) -> addSelection(query, cb);
        var q = ModelQuery.builder(ROOT, VIEW_MAPPER)
                .columns(ColumnSet.of(ID, STATUS, CUSTOMER_NAME))
                .customize(extra)
                .build();
        try (SessionFactory sf = sessionFactory(db)) {
            sf.inSession(em -> {
                BuiltQuery<View> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
                List<Tuple> tuples = em.createQuery(built.query()).getResultList();
                assertThat(tuples).isNotEmpty();
                for (Tuple t : tuples) {
                    // The extra value is in the tuple, but nothing keyed by a field reaches it.
                    assertThat(t.getElements()).hasSize(4);
                    assertThat(t.get("extra", String.class)).isEqualTo(t.get("c1", String.class).toUpperCase());
                    Row row = built.selection().row(t);
                    assertThat(row.isSelected(TOTAL)).isFalse();
                    assertThat(row.get(TOTAL)).isNull();
                    assertThat(row.get(STATUS)).isEqualTo(t.get("c1", String.class));
                }
                assertThat(em.createQuery(built.query()).getResultList().stream().map(built::map))
                        .allSatisfy(v -> assertThat(v.total()).isNull());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static void addSelection(CriteriaQuery<?> query, CriteriaBuilder cb) {
        var items = new ArrayList<Selection<?>>(query.getSelection().getCompoundSelectionItems());
        Root<?> root = query.getRoots().iterator().next();
        items.add(cb.upper(root.<String>get("status")).alias("extra"));
        ((CriteriaQuery<Tuple>) query).multiselect(items);
    }

    // ---- AC-QRY-06

    @TckTest
    void ac_qry_06_a_customizer_adding_a_predicate_only_in_model_logs_a_warning_naming_the_phase(TckDatabase db) {
        QueryCustomizer onlyModel = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL) {
                query.where(cb.equal(query.getRoots().iterator().next().get("status"), "PAID"));
            }
        };
        QueryCustomizer everywhere = (spec, joins, query, cb, phase) ->
                query.where(cb.equal(query.getRoots().iterator().next().get("status"), "PAID"));
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger(ModelQuery.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(new SimpleFormatter().formatMessage(r));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try (SessionFactory sf = sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                var consistent = ModelQuery.builder(ROOT, VIEW_MAPPER).columns(DEFAULT)
                        .primaryKey(PrimaryKey.of(ID)).customize(everywhere).build();
                consistent.checkPhases(cb);
                assertThat(warnings).isEmpty();
                var inconsistent = ModelQuery.builder(ROOT, VIEW_MAPPER).columns(DEFAULT)
                        .primaryKey(PrimaryKey.of(ID)).customize(onlyModel).build();
                inconsistent.checkPhases(cb);
            });
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("[MODEL]").contains("PRIMARY_KEY").contains("MODEL_BY_KEYS");
    }

    // ---- AC-QRY-07: each row of §5, omitting exactly that part

    @TckTest
    void ac_qry_07_every_absent_part_has_its_defined_behaviour(TckDatabase db) {
        AtomicInteger mapped = new AtomicInteger();
        RowMapper<View> counting = row -> {
            mapped.incrementAndGet();
            return VIEW_MAPPER.map(row);
        };
        try (SessionFactory sf = sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                var minimal = ModelQuery.builder(ROOT, counting).columns(ColumnSet.of(ID, STATUS)).build();

                // orderBy: unordered.
                assertThat(minimal.buildQuery(cb, Phase.MODEL).query().getOrderList()).isEmpty();
                // primaryKey: allowed for list/page/count shapes; nothing is added to the selection.
                assertThat(minimal.primaryKey()).isEmpty();
                assertThat(minimal.buildQuery(cb, Phase.MODEL).query().getSelection().getCompoundSelectionItems())
                        .hasSize(2);
                assertThatThrownBy(() -> minimal.buildQuery(cb, Phase.PRIMARY_KEY))
                        .isInstanceOf(IllegalStateException.class);
                // groupBy: not grouped.
                assertThat(minimal.buildQuery(cb, Phase.MODEL).query().getGroupList()).isEmpty();
                // keyset() and primaryKeyFirst: offset mode, never two-step.
                assertThat(minimal.isKeyset()).isFalse();
                assertThat(minimal.primaryKeyFirst()).isEmpty();
                // where: no predicate.
                assertThat(minimal.buildQuery(cb, Phase.MODEL).query().getRestriction()).isNull();
                // afterMap: the mapper's result is returned as is.
                BuiltQuery<View> built = minimal.buildQuery(cb, Phase.MODEL);
                List<Tuple> tuples = em.createQuery(built.query()).getResultList();
                List<View> views = tuples.stream().map(built::map).toList();
                assertThat(views).hasSize(tuples.size());
                assertThat(mapped.get()).isEqualTo(tuples.size());
                assertThat(views).allSatisfy(v -> assertThat(v.id()).isNotNull().isEqualTo(v.id()));
            });
        }
        // columns is the one required part.
        assertThatThrownBy(() -> ModelQuery.builder(ROOT, VIEW_MAPPER).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1202))
                .hasMessageContaining("MQ1202").hasMessageContaining("OrderEntity");
    }

    // ---- AC-COL-09

    @TckTest
    void ac_col_09_a_static_final_model_query_gives_identical_results_on_eight_threads(
            TckDatabase db) throws Exception {
        int threads = 8;
        try (SessionFactory sf = sessionFactory(db)) {
            List<View>[] baseline = newHolder();
            sf.inSession(em -> baseline[0] = run(em, SHARED, Phase.MODEL));
            assertThat(baseline[0]).hasSizeGreaterThan(1);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<List<List<View>>>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<List<List<View>>> task = () -> {
                        start.await();
                        List<List<View>> runs = new ArrayList<>();
                        for (int n = 0; n < 10; n++) {
                            try (EntityManager em = sf.createEntityManager()) {
                                runs.add(run(em, SHARED, Phase.MODEL));
                            }
                        }
                        return runs;
                    };
                    futures.add(pool.submit(task));
                }
                start.countDown();
                for (Future<List<List<View>>> f : futures) {
                    assertThat(f.get()).hasSize(10).allSatisfy(r -> assertThat(r).isEqualTo(baseline[0]));
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T>[] newHolder() {
        return (List<T>[]) new List<?>[2];
    }

    private static SessionFactory sessionFactory(TckDatabase db) {
        return JoinTestSupport.sessionFactory(db);
    }

    private static SessionFactory sessionFactory(javax.sql.DataSource ds) {
        return JoinTestSupport.sessionFactory(ds);
    }
}
