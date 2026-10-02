package com.rey.modelquery.tck.qry;

import static com.rey.modelquery.core.RenderOptions.portable;
import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
    /** A nullable reference: joined INNER, it removes the two thirds of the orders that have no referrer. */
    private static final TableField<OrderEntity, CustomerEntity> REFERRER =
            TableField.join(ROOT, "referrer", INNER);

    private static final ColumnField<View, OrderEntity, Long> ID =
            ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final ColumnField<View, OrderEntity, String> STATUS =
            ColumnField.of(View.class, ROOT, "status", String.class);
    private static final ColumnField<View, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(View.class, ROOT, "total", BigDecimal.class);
    private static final ColumnField<View, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(View.class, CUSTOMER, "name", String.class);
    private static final ColumnField<View, CustomerEntity, String> REFERRER_NAME =
            ColumnField.of(View.class, REFERRER, "name", String.class);

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

    private static final SelectSet<View> DEFAULT = SelectSet.of(STATUS, CUSTOMER_NAME, TOTAL);

    /** A constant shared by every thread of the concurrency test (INV-9). */
    private static final ModelQuery<OrderEntity, Long, View> SHARED = ModelQuery.builder(ROOT, VIEW_MAPPER)
            .select(SelectSet.of(ID, STATUS, CUSTOMER_NAME, TOTAL))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(TOTAL.desc().nullsLast(), ID.asc())
            .keyset()
            .build();

    private static <M> List<M> run(EntityManager em, ModelQuery<?, ?, M> q, Phase phase) {
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), phase, portable());
        return em.createQuery(built.query()).getResultList().stream().map(built::map).toList();
    }

    // ---- AC-QRY-01

    @Test
    void ac_qry_01_a_built_query_exposes_no_mutator_and_its_lists_throw() {
        var q = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).orderBy(ID.asc()).build();
        for (Method m : ModelQuery.class.getMethods()) {
            if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            assertThat(m.getName()).as(m.toString()).doesNotStartWith("set").doesNotStartWith("add")
                    .doesNotStartWith("put").doesNotStartWith("remove");
            // A with... returns a copy, as withFetch does (R-FCH-13), never this query changed.
            if (m.getName().startsWith("with")) {
                assertThat(m.getReturnType()).as(m.toString()).isEqualTo(ModelQuery.class);
            }
        }
        var fetching = q.withFetch(FetchPlan.of(DEFAULT));
        assertThat(fetching).isNotSameAs(q);
        assertThat(q.fetch()).isEmpty();
        assertThat(ModelQuery.class.getFields()).isEmpty();
        assertThatThrownBy(() -> q.orderBy().add(ID.desc())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> q.select().fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> q.spec().columns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ac_qry_01_two_queries_built_from_one_builder_instance_are_independent() {
        var base = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).primaryKey(PrimaryKey.of(ID));
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
        var noKey = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT);
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
                .select(SelectSet.of(STATUS, CUSTOMER_NAME, TOTAL))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(ID.asc())
                .keyset()
                .build();
        var unpaged = ModelQuery.builder(ROOT, VIEW_MAPPER)
                .select(SelectSet.of(STATUS, CUSTOMER_NAME, TOTAL))
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
                    BuiltQuery<View> keys = paged.buildQuery(em.getCriteriaBuilder(), Phase.PRIMARY_KEY, portable());
                    em.createQuery(keys.query()).getResultList().forEach(t -> keyOnly.add(t.get("c0", Long.class)));
                });
            }
        });
        assertThat(result[0]).isNotEmpty().allSatisfy(v -> assertThat(v.id()).isNotNull());
        assertThat(result[0].stream().map(View::id).toList()).isSorted();
        // Without keyset() or primaryKeyFirst(...) the key is still added: offset export needs it too (D-29).
        assertThat(result[1]).isEqualTo(result[0]);
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
                .select(SelectSet.of(L_ID, L_STATUS, L_CUSTOMER_NAME, L_TOTAL))
                .afterMap((m, row) -> {
                    calls.incrementAndGet();
                    sawAll.add(row.isSelected(L_ID) && row.isSelected(L_STATUS) && row.isSelected(L_CUSTOMER_NAME)
                            && row.isSelected(L_TOTAL));
                    m.label = row.get(L_CUSTOMER_NAME) + "/" + row.get(L_STATUS) + "/" + row.get(L_TOTAL);
                })
                .build();
        var records = ModelQuery.builder(ROOT, VIEW_MAPPER)
                .select(SelectSet.of(ID, STATUS, CUSTOMER_NAME, TOTAL))
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
                .select(SelectSet.of(ID, STATUS, CUSTOMER_NAME))
                .customize(extra)
                .build();
        try (SessionFactory sf = sessionFactory(db)) {
            sf.inSession(em -> {
                BuiltQuery<View> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable());
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
                var consistent = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT)
                        .primaryKey(PrimaryKey.of(ID)).customize(everywhere).build();
                consistent.checkPhases(cb);
                assertThat(warnings).isEmpty();
                var inconsistent = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT)
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
                var minimal = ModelQuery.builder(ROOT, counting).select(SelectSet.of(ID, STATUS)).build();

                // orderBy: unordered.
                assertThat(minimal.buildQuery(cb, Phase.MODEL, portable()).query().getOrderList()).isEmpty();
                // primaryKey: allowed for list/page/count shapes; nothing is added to the selection.
                assertThat(minimal.primaryKey()).isEmpty();
                assertThat(minimal.buildQuery(cb, Phase.MODEL, portable()).query().getSelection()
                        .getCompoundSelectionItems()).hasSize(2);
                assertThatThrownBy(() -> minimal.buildQuery(cb, Phase.PRIMARY_KEY, portable()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2203));
                // groupBy: not grouped.
                assertThat(minimal.buildQuery(cb, Phase.MODEL, portable()).query().getGroupList()).isEmpty();
                // keyset() and primaryKeyFirst: offset mode, never two-step.
                assertThat(minimal.isKeyset()).isFalse();
                assertThat(minimal.primaryKeyFirst()).isEmpty();
                // where: no predicate.
                assertThat(minimal.buildQuery(cb, Phase.MODEL, portable()).query().getRestriction()).isNull();
                // afterMap: the mapper's result is returned as is.
                BuiltQuery<View> built = minimal.buildQuery(cb, Phase.MODEL, portable());
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

    @TckTest
    void ac_qry_07_without_where_the_query_has_no_predicate_and_reads_every_row(TckDatabase db) {
        var base = ModelQuery.builder(ROOT, VIEW_MAPPER).select(SelectSet.of(ID, STATUS)).orderBy(ID.asc());
        var filtered = base.where(f -> f.eq(STATUS, "PAID")).build();
        var unfiltered = base.build();
        try (SessionFactory sf = sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                assertThat(unfiltered.buildQuery(cb, Phase.MODEL, portable()).query().getRestriction()).isNull();
                assertThat(filtered.buildQuery(cb, Phase.MODEL, portable()).query().getRestriction()).isNotNull();
                assertThat(run(em, unfiltered, Phase.MODEL)).hasSize(TckFixture.ORDERS);
                assertThat(run(em, filtered, Phase.MODEL)).isNotEmpty().hasSizeLessThan(TckFixture.ORDERS)
                        .allSatisfy(v -> assertThat(v.status()).isEqualTo("PAID"));
            });
        }
    }

    @TckTest
    void ac_qry_07_without_group_by_the_query_is_not_grouped_and_an_aggregate_makes_it_one_group(TckDatabase db) {
        AggregateField<View, Long> count = Agg.count(ROOT);
        var plain = ModelQuery.builder(ROOT, VIEW_MAPPER).select(SelectSet.of(ID, STATUS)).build();
        // The count rides in the view's id.
        RowMapper<View> counted = row -> new View(row.get(count), row.get(STATUS), null, null);
        var single = ModelQuery.builder(ROOT, counted).select(SelectSet.of(count)).build();
        var grouped = ModelQuery.builder(ROOT, counted).select(SelectSet.of(STATUS, count)).groupBy(STATUS).build();
        try (SessionFactory sf = sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                assertThat(plain.isGrouped()).isFalse();
                assertThat(plain.groupBy()).isEmpty();
                assertThat(plain.spec().isGrouped()).isFalse();
                assertThat(single.isGrouped()).isTrue();
                assertThat(single.groupBy()).isEmpty();
                assertThat(grouped.isGrouped()).isTrue();
                assertThat(grouped.groupBy()).containsExactly(STATUS);
                assertThat(grouped.spec().groupBy()).containsExactly(STATUS);
                assertThat(grouped.spec().isGrouped()).isTrue();
                assertThat(plain.buildQuery(cb, Phase.MODEL, portable()).query().getGroupList()).isEmpty();
                assertThat(run(em, plain, Phase.MODEL)).hasSize(TckFixture.ORDERS);
                assertThat(single.buildQuery(cb, Phase.MODEL, portable()).query().getGroupList()).isEmpty();
                assertThat(run(em, single, Phase.MODEL)).extracting(View::id).containsExactly((long) TckFixture.ORDERS);
                assertThat(grouped.buildQuery(cb, Phase.MODEL, portable()).query().getGroupList()).hasSize(1);
                assertThat(run(em, grouped, Phase.MODEL)).hasSize(4)
                        .allSatisfy(v -> assertThat(v.id()).isEqualTo(TckFixture.ORDERS / 4L));
            });
        }
    }

    @TckTest
    void ac_qry_06_the_where_predicate_is_not_counted_as_a_customizer_predicate(TckDatabase db) {
        QueryCustomizer noPredicate = (spec, joins, query, cb, phase) -> {};
        QueryCustomizer onlyModel = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL) {
                query.where(cb.and(query.getRestriction(),
                        cb.isNotNull(query.getRoots().iterator().next().get("total"))));
            }
        };
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
                var filtered = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).primaryKey(PrimaryKey.of(ID))
                        .where(f -> f.eq(STATUS, "PAID"));
                filtered.customize(noPredicate).build().checkPhases(cb);
                assertThat(warnings).isEmpty();
                filtered.customize(onlyModel).build().checkPhases(cb);
            });
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("[MODEL]").contains("PRIMARY_KEY");
    }

    @TckTest
    void ac_qry_06_a_customizer_adding_an_inner_join_only_in_model_logs_a_warning(TckDatabase db) {
        QueryCustomizer leftOnlyInModel = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL) {
                query.getRoots().iterator().next().join("referrer", jakarta.persistence.criteria.JoinType.LEFT);
            }
        };
        QueryCustomizer innerOnlyInModel = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL) {
                query.getRoots().iterator().next().join("referrer");
            }
        };
        var keyed = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).primaryKey(PrimaryKey.of(ID));
        List<String> warnings = warnings(() -> {
            try (SessionFactory sf = sessionFactory(db)) {
                sf.inSession(em -> {
                    // A LEFT join removes no row, so it cannot make the phases disagree on which rows exist.
                    keyed.customize(leftOnlyInModel).build().checkPhases(em.getCriteriaBuilder());
                    keyed.customize(innerOnlyInModel).build().checkPhases(em.getCriteriaBuilder());
                });
            }
        });
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("INNER join").contains("[MODEL]").contains("PRIMARY_KEY");
    }

    /** The warnings {@code ModelQuery} logs while {@code work} runs. */
    private static List<String> warnings(Runnable work) {
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
        try {
            work.run();
        } finally {
            logger.removeHandler(handler);
        }
        return warnings;
    }

    // ---- AC-QRY-08

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"}) // a join passed where the signature wants a root
    void ac_qry_08_a_join_as_root_and_a_negative_offset_throw_mq1203_and_mq1204() {
        assertThatThrownBy(() -> ModelQuery.builder((TableField) CUSTOMER, VIEW_MAPPER))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1203))
                .hasMessage("MQ1203: builder(...) takes a TableField.root(...), not the join 'customer' (INNER)");
        assertThatThrownBy(() -> PrimaryKeyFirst.whenOffsetAbove(-1))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1204))
                .hasMessageContaining("whenOffsetAbove(-1)");
        assertThat(PrimaryKeyFirst.whenOffsetAbove(0).offsetThreshold()).isZero();
    }

    @TckTest
    void ac_qry_08_the_primary_key_phase_of_a_query_without_a_key_throws_mq2203(TckDatabase db) {
        var noKey = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).build();
        AggregateField<View, Long> count = Agg.count(ROOT);
        var grouped = ModelQuery.builder(ROOT, VIEW_MAPPER).select(SelectSet.of(STATUS, count)).groupBy(STATUS)
                .primaryKey(PrimaryKey.of(ID)).build();
        try (SessionFactory sf = sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                assertThatThrownBy(() -> noKey.buildQuery(cb, Phase.PRIMARY_KEY, portable()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2203))
                        .hasMessage("MQ2203: View: phase PRIMARY_KEY needs a primary key, and primaryKey(...) was "
                                + "not set");
                assertThatThrownBy(() -> grouped.buildQuery(cb, Phase.PRIMARY_KEY, portable()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2203))
                        .hasMessageContaining("a grouped query has none");
            });
        }
    }

    // ---- AC-QRY-09

    @TckTest
    void ac_qry_09_every_phase_has_the_same_joins_predicate_and_order_and_selects_the_same_rows(TckDatabase db) {
        var keyed = ModelQuery.builder(ROOT, VIEW_MAPPER).primaryKey(PrimaryKey.of(ID))
                .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0));
        List<ModelQuery<OrderEntity, Long, View>> queries = List.of(
                // A nullable join made only by the selection: PRIMARY_KEY must drop the orders it drops.
                keyed.select(SelectSet.of(STATUS, REFERRER_NAME)).orderBy(TOTAL.desc(), ID.asc()).build(),
                // The same path inside an or(...) reuses the selection's INNER join, in every phase.
                keyed.select(SelectSet.of(STATUS, REFERRER_NAME))
                        .where(f -> f.or(a -> a.lt(REFERRER_NAME, "Customer 0100"), b -> b.eq(STATUS, "PAID")))
                        .orderBy(ID.asc()).build(),
                // Needed only inside the or(...): LEFT in every phase, next to an INNER join made by the selection.
                keyed.select(SelectSet.of(STATUS, CUSTOMER_NAME))
                        .where(f -> f.or(a -> a.lt(REFERRER_NAME, "Customer 0100"), b -> b.eq(STATUS, "PAID")))
                        .orderBy(CUSTOMER_NAME.asc(), ID.asc()).build());
        List<Map<Phase, List<Long>>> keys = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "qry-09-phases-share-joins", ds -> {
            try (SessionFactory sf = sessionFactory(ds)) {
                sf.inSession(em -> queries.forEach(q -> {
                    Map<Phase, List<Long>> byPhase = new EnumMap<>(Phase.class);
                    for (Phase phase : Phase.values()) {
                        BuiltQuery<View> built = q.buildQuery(em.getCriteriaBuilder(), phase, portable());
                        byPhase.put(phase, em.createQuery(built.query()).getResultList().stream()
                                .map(t -> built.selection().row(t).get(ID)).toList());
                    }
                    keys.add(byPhase);
                }));
            }
        });
        assertThat(sql).hasSize(queries.size() * Phase.values().length);
        for (int i = 0; i < queries.size(); i++) {
            String model = sql.get(3 * i);
            String primaryKey = sql.get(3 * i + 1);
            String modelByKeys = sql.get(3 * i + 2);
            assertThat(modelByKeys).as("query %d", i).isEqualTo(model);
            assertThat(afterSelect(primaryKey)).as("query %d", i).isEqualTo(afterSelect(model));
            Map<Phase, List<Long>> byPhase = keys.get(i);
            assertThat(byPhase.get(Phase.MODEL)).as("query %d", i).isNotEmpty().hasSizeLessThan(TckFixture.ORDERS)
                    .isEqualTo(byPhase.get(Phase.PRIMARY_KEY)).isEqualTo(byPhase.get(Phase.MODEL_BY_KEYS));
        }
    }

    private static final Pattern POSITION = Pattern.compile("^(\\d+)(.*)$");

    /**
     * {@code sql} from its {@code FROM} on, with every ordering key given by its position in the SELECT list
     * ({@code order by 2 desc}) replaced by that item, so statements selecting different lists compare equal.
     */
    private static String afterSelect(String sql) {
        int from = topLevel(sql, " from ", 0);
        // An item may carry a generated alias ("oe1_0.total c2"); the alias is not part of what is selected.
        List<String> items = split(sql.substring("select ".length(), from)).stream()
                .map(item -> item.replaceFirst(" c\\d+$", "")).toList();
        String rest = sql.substring(from);
        int order = rest.lastIndexOf(" order by ");
        if (order < 0) {
            return rest;
        }
        var keys = new ArrayList<String>();
        for (String key : split(rest.substring(order + " order by ".length()))) {
            Matcher position = POSITION.matcher(key);
            keys.add(position.matches()
                    ? items.get(Integer.parseInt(position.group(1)) - 1) + position.group(2)
                    : key);
        }
        return rest.substring(0, order) + " order by " + String.join(",", keys);
    }

    /** The comma-separated items of {@code list}, not splitting inside parentheses. */
    private static List<String> split(String list) {
        var items = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (c == ',' && depth == 0) {
                items.add(list.substring(start, i).trim());
                start = i + 1;
            }
        }
        items.add(list.substring(start).trim());
        return items;
    }

    private static int topLevel(String sql, String token, int from) {
        int depth = 0;
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (depth == 0 && sql.startsWith(token, i)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No top-level '" + token.trim() + "' in " + sql);
    }

    // ---- AC-QRY-10

    @TckTest
    void ac_qry_10_a_customizer_changing_the_order_or_the_grouping_throws_mq1205(TckDatabase db) {
        QueryCustomizer addsGroupBy = (spec, joins, query, cb, phase) -> {
            var groups = new ArrayList<Expression<?>>(query.getGroupList());
            groups.add(query.getRoots().iterator().next().get("total"));
            query.groupBy(groups);
        };
        QueryCustomizer reorders = (spec, joins, query, cb, phase) ->
                query.orderBy(cb.desc(query.getRoots().iterator().next().get("total")));
        QueryCustomizer reordersStepTwo = (spec, joins, query, cb, phase) -> {
            if (phase == Phase.MODEL_BY_KEYS) {
                reorders.customize(spec, joins, query, cb, phase);
            }
        };
        QueryCustomizer keepsOrder = (spec, joins, query, cb, phase) -> {
            query.orderBy(query.getOrderList());
            query.groupBy(query.getGroupList());
        };
        AggregateField<View, Long> count = Agg.count(ROOT);
        var byStatus = ModelQuery.builder(ROOT, VIEW_MAPPER).select(SelectSet.of(STATUS, count)).groupBy(STATUS);
        var keyed = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).primaryKey(PrimaryKey.of(ID))
                .orderBy(STATUS.asc());
        var unkeyed = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).orderBy(STATUS.asc());
        try (SessionFactory sf = sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                // A grouped query has no primary key, so only MODEL is checked, but it is checked (R-QRY-11).
                assertMq1205(() -> byStatus.customize(addsGroupBy).build().checkPhases(cb), "GROUP BY", Phase.MODEL);
                assertMq1205(() -> byStatus.customize(addsGroupBy).build().buildQuery(cb, Phase.MODEL, portable()),
                        "GROUP BY", Phase.MODEL);
                assertMq1205(() -> byStatus.customize(reorders).build().checkPhases(cb), "ORDER BY", Phase.MODEL);
                assertMq1205(() -> unkeyed.customize(reorders).build().checkPhases(cb), "ORDER BY", Phase.MODEL);
                assertMq1205(
                        () -> keyed.keyset().customize(reorders).build().buildQuery(cb, Phase.PRIMARY_KEY, portable()),
                        "ORDER BY", Phase.PRIMARY_KEY);
                // A change in one phase only is found on first execution, before that phase ever runs.
                assertMq1205(() -> keyed.customize(reordersStepTwo).build().checkPhases(cb), "ORDER BY",
                        Phase.MODEL_BY_KEYS);
                // Setting the order and grouping it received changes neither.
                byStatus.customize(keepsOrder).build().checkPhases(cb);
                keyed.customize(keepsOrder).build().checkPhases(cb);
            });
        }
    }

    private static void assertMq1205(ThrowingCallable call, String clause, Phase phase) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1205))
                .hasMessageStartingWith("MQ1205: View: the QueryCustomizer changed the " + clause + " of phase "
                        + phase + ";");
    }

    // ---- AC-QRY-11

    @Test
    void ac_qry_11_a_primary_key_column_of_array_type_throws_mq1206() {
        ColumnField<View, OrderEntity, byte[]> rawId = ColumnField.of(View.class, ROOT, "id", byte[].class);
        var keyedByBytes = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).primaryKey(PrimaryKey.of(rawId));
        for (var builder : List.of(keyedByBytes, keyedByBytes.keyset(),
                keyedByBytes.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)))) {
            assertThatThrownBy(builder::build)
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1206))
                    .hasMessageStartingWith("MQ1206: View.id: a primary-key column of type byte[] cannot identify a "
                            + "row");
        }
    }

    // ---- AC-QRY-12

    @Test
    void ac_qry_12_a_keyset_over_a_float_or_double_column_throws_mq1207() {
        // Resolved against no entity: the refusal is part of build(), before any database is involved.
        ColumnField<View, OrderEntity, Double> doubleTotal = ColumnField.of(View.class, ROOT, "total", Double.class);
        ColumnField<View, OrderEntity, Float> floatId = ColumnField.of(View.class, ROOT, "id", float.class);
        var byTotal = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).primaryKey(PrimaryKey.of(ID))
                .orderBy(STATUS.asc(), doubleTotal.desc());
        var keyedByFloat = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).primaryKey(PrimaryKey.of(floatId))
                .orderBy(STATUS.asc());
        assertThatThrownBy(() -> byTotal.keyset().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1207))
                .hasMessageStartingWith("MQ1207: View.total: keyset() cannot page by a Double column");
        // The appended primary-key tie-breaker is a keyset column too (R-PAG-04).
        assertThatThrownBy(() -> keyedByFloat.keyset().build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1207))
                .hasMessageStartingWith("MQ1207: View.id: keyset() cannot page by a Float column");
        // Offset paging binds no cursor, so the same queries build without keyset().
        assertThat(byTotal.build().isKeyset()).isFalse();
        assertThat(keyedByFloat.build().isKeyset()).isFalse();
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
