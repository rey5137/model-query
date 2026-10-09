package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code byKeys} (engine/20 R-EXE-13) and the ignored-{@code orderBy} warning of {@code count}, {@code one(q)} and
 * {@code one(q, key)} (R-EXE-05, R-EXE-12) over in-memory H2.
 */
class ByKeysTest {

    record Row(Long id, Integer a) {}

    record Keyed(String id) {}

    record Pair(Integer first, Integer second) {}

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row, KeysetRowEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> A =
            ColumnField.of(Row.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> C =
            ColumnField.of(Row.class, ROOT, "c", Integer.class);

    /**
     * The id as {@code "k<id>"}; {@code toAttribute} reads the digits only,
     * so "k2" and "x2" are one attribute value.
     */
    private static final ColumnField<Keyed, KeysetRowEntity, String> KEYED_ID = ColumnField.of(Keyed.class, ROOT,
            "id", String.class, Long.class, new ColumnConverter<String, Long>() {
                @Override
                public String toModel(Long attribute) {
                    return "k" + attribute;
                }

                @Override
                public Long toAttribute(String model) {
                    return Long.valueOf(model.substring(1));
                }
            });

    private static final TableField<BindLimitPairEntity, BindLimitPairEntity> PAIRS =
            TableField.root(BindLimitPairEntity.class);
    private static final ColumnField<Pair, BindLimitPairEntity, Integer> FIRST =
            ColumnField.of(Pair.class, PAIRS, "firstNo", Integer.class);
    private static final ColumnField<Pair, BindLimitPairEntity, Integer> SECOND =
            ColumnField.of(Pair.class, PAIRS, "secondNo", Integer.class);

    private static final List<String> SQL = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger MAPPED = new AtomicInteger();

    private static SessionFactory sessions;

    /** Four rows, ids 1 to 4; {@code a} is NULL on row 1, and {@code c} pairs them up (0, 0, 1, 1). */
    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
                .addAnnotatedClass(BindLimitPairEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:bykeys;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .applySetting(AvailableSettings.STATEMENT_INSPECTOR, (StatementInspector) sql -> {
                            SQL.add(sql);
                            return sql;
                        })
                        .build());
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new KeysetRowEntity(1, null, 10, 0));
            em.persist(new KeysetRowEntity(2, 2, 20, 0));
            em.persist(new KeysetRowEntity(3, 1, 30, 1));
            em.persist(new KeysetRowEntity(4, 3, null, 1));
            em.persist(new BindLimitPairEntity(1, 1, 1));
            em.persist(new BindLimitPairEntity(1, 2, 2));
            em.persist(new BindLimitPairEntity(2, 1, 3));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @BeforeEach
    void reset() {
        SQL.clear();
        MAPPED.set(0);
    }

    private static ModelQuery.Builder<KeysetRowEntity, Long, Row> rows() {
        return ModelQuery.builder(ROOT, row -> {
            MAPPED.incrementAndGet();
            return new Row(row.get(ID), row.get(A));
        }).select(SelectSet.of(ID, A)).primaryKey(PrimaryKey.of(ID));
    }

    // ---- AC-EXE-14

    @Test
    void ac_exe_14_by_keys_returns_the_map_in_first_occurrence_order_with_missing_keys_absent() {
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            Map<Long, Row> result = executor.byKeys(rows().build(), Arrays.asList(3L, 99L, 1L, 3L, 2L));
            assertThat(result).containsExactly(Map.entry(3L, new Row(3L, 1)), Map.entry(1L, new Row(1L, null)),
                    Map.entry(2L, new Row(2L, 2)));
            assertThat(MAPPED).hasValue(3);
            assertThat(SQL).hasSize(1);
            assertThatThrownBy(() -> result.put(7L, new Row(7L, 7))).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void ac_exe_14_by_keys_ands_the_key_with_the_query_filter() {
        withExecutor(ModelQueryConfig.defaults(), executor -> assertThat(
                executor.byKeys(rows().where(f -> f.gt(A, 1)).build(), List.of(1L, 2L, 3L, 4L)).keySet())
                .containsExactly(2L, 4L));
    }

    @Test
    void ac_exe_14_by_keys_applies_a_model_phase_customizer() {
        var customized = rows().customize((spec, joins, query, cb, phase) -> {
            var predicate = cb.isNotNull(query.getRoots().iterator().next().get("a"));
            var own = query.getRestriction();
            query.where(own == null ? predicate : cb.and(own, predicate));
        }).build();
        withExecutor(ModelQueryConfig.defaults(), executor ->
                assertThat(executor.byKeys(customized, List.of(1L, 2L, 3L))).containsOnlyKeys(2L, 3L));
    }

    @Test
    void ac_exe_14_by_keys_converts_keys_as_where_key_does_and_two_keys_of_one_value_share_a_model() {
        AtomicInteger afterMapped = new AtomicInteger();
        var keyed = ModelQuery.builder(ROOT, row -> new Keyed(row.get(KEYED_ID)))
                .select(SelectSet.of(KEYED_ID))
                .primaryKey(PrimaryKey.of(KEYED_ID))
                .afterMap((model, row) -> afterMapped.incrementAndGet())
                .build();
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            Map<String, Keyed> result = executor.byKeys(keyed, List.of("k3", "k2", "x2", "k2"));
            assertThat(result).containsOnlyKeys("k3", "k2", "x2");
            assertThat(List.copyOf(result.keySet())).containsExactly("k3", "k2", "x2");
            assertThat(result.get("x2")).isSameAs(result.get("k2")).isEqualTo(new Keyed("k2"));
            assertThat(afterMapped).hasValue(2);
        });
    }

    @Test
    void ac_exe_14_by_keys_reads_composite_keys_and_checks_their_components() {
        var pairs = ModelQuery.builder(PAIRS, row -> new Pair(row.get(FIRST), row.get(SECOND)))
                .select(SelectSet.of(FIRST, SECOND))
                .primaryKey(PrimaryKey.composite(FIRST, SECOND))
                .build();
        try (EntityManager em = sessions.createEntityManager()) {
            var executor = ModelQueryExecutor.create(em, BindLimitPairEntity.class, ModelQueryConfig.defaults());
            assertThat(executor.byKeys(pairs, List.of(List.of(2, 1), List.of(9, 9), List.of(1, 2))))
                    .containsExactly(Map.entry(List.of(2, 1), new Pair(2, 1)),
                            Map.entry(List.of(1, 2), new Pair(1, 2)));
            assertThatThrownBy(() -> executor.byKeys(pairs, List.of(List.of(1))))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a key of 2 components is a list of 2 values, not [1]");
            assertThatThrownBy(() -> executor.byKeys(pairs, List.of(Arrays.asList(1, null))))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a key component is null: [1, null]");
        }
    }

    @Test
    void ac_exe_14_by_keys_with_no_keys_runs_no_sql_and_checks_come_first() {
        var keyless = ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(A)))
                .select(SelectSet.of(ID, A)).build();
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            assertThat(executor.byKeys(rows().build(), List.of())).isEmpty();
            assertThat(SQL).isEmpty();
            assertMq(() -> executor.byKeys(keyless, List.of()), MqCode.MQ2203,
                    "Row: byKeys(query, keys) needs a primary key to filter on, and primaryKey(...) was not set");
            assertThatThrownBy(() -> executor.byKeys(null, List.of())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> executor.byKeys(rows().build(), null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> executor.byKeys(rows().build(), Arrays.asList(1L, null)))
                    .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("the key is null");
            assertThat(SQL).isEmpty();
        });
    }

    @Test
    void ac_exe_14_by_keys_spreads_keys_over_statements_and_runs_the_fetch_plan_once() {
        List<Integer> enriched = new ArrayList<>();
        var q = rows().fetch(FetchPlan.of(SelectSet.of(ID, A)).enrich(Enricher.of(page -> {
            enriched.add(page.size());
            return page;
        }))).build();
        // An IN list of at most 2 values: five distinct keys are three statements, however the batch size is set.
        for (ModelQueryConfig config : List.of(limited(2), limited(2).primaryKeyFirstBatchSize(1),
                limited(2).primaryKeyFirstBatchSize(1_000))) {
            SQL.clear();
            enriched.clear();
            withExecutor(config, executor -> assertThat(executor.byKeys(q, List.of(4L, 3L, 2L, 1L, 99L)).keySet())
                    .containsExactly(4L, 3L, 2L, 1L));
            assertThat(SQL).hasSize(3).allSatisfy(sql -> assertThat(sql).doesNotContainIgnoringCase("order by"));
            assertThat(enriched).containsExactly(4);
        }
    }

    @Test
    void ac_exe_14_by_keys_ignores_the_order_by_with_one_warning_per_query_and_renders_none() {
        var ordered = rows().orderBy(A.desc()).build();
        List<String> warnings = new ArrayList<>();
        capturingWarnings(warnings, () -> withExecutor(ModelQueryConfig.defaults(), executor -> {
            assertThat(executor.byKeys(ordered, List.of(1L, 2L, 3L)).keySet()).containsExactly(1L, 2L, 3L);
            executor.byKeys(ordered, List.of(1L));
            assertThat(SQL).allSatisfy(sql -> assertThat(sql).doesNotContainIgnoringCase("order by"));
        }));
        assertThat(warnings).containsExactly(
                "Row: byKeys(query, keys) ignores the query's orderBy; the map is in key order");
    }

    @Test
    void ac_exe_14_a_key_matching_two_rows_throws_mq2003_before_any_row_is_mapped() {
        var byC = ModelQuery.builder(ROOT, row -> {
            MAPPED.incrementAndGet();
            return new Row(row.get(ID), row.get(A));
        }).select(SelectSet.of(ID, A)).primaryKey(PrimaryKey.of(C)).build();
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            assertMq(() -> executor.byKeys(byC, List.of(0)), MqCode.MQ2003,
                    "Row: byKeys(query, keys) found more than one row for key 0");
            assertThat(MAPPED).hasValue(0);
        });
    }

    // ---- AC-EXE-15

    @Test
    void ac_exe_15_count_one_and_one_by_key_warn_once_per_query_and_one_renders_no_order_by() {
        var ordered = rows().orderBy(A.desc()).build();
        List<String> warnings = new ArrayList<>();
        capturingWarnings(warnings, () -> withExecutor(ModelQueryConfig.defaults(), executor -> {
            assertThat(executor.count(ordered)).isEqualTo(4);
            executor.count(ordered);
            assertThat(executor.one(rows().where(f -> f.eq(ID, 2L)).orderBy(A.asc()).build())).isPresent();
            var oneOrdered = rows().orderBy(A.asc()).build();
            assertThat(executor.one(oneOrdered, 2L)).isPresent();
            executor.one(oneOrdered, 3L);
            assertThat(SQL).allSatisfy(sql -> assertThat(sql).doesNotContainIgnoringCase("order by"));
            // page's count and first use the order and log nothing more.
            SQL.clear();
            executor.page(ordered, PageSpec.of(0, 2), CountMode.COUNT);
            executor.first(ordered);
            assertThat(SQL).anySatisfy(sql -> assertThat(sql).containsIgnoringCase("order by"));
            // An unordered query warns of nothing.
            executor.count(rows().build());
            executor.one(rows().where(f -> f.eq(ID, 1L)).build());
            executor.list(ordered, Limit.of(1));
        }));
        assertThat(warnings).containsExactlyInAnyOrder(
                "Row: count(query) ignores the query's orderBy",
                "Row: one(query) ignores the query's orderBy",
                "Row: one(query, key) ignores the query's orderBy");
    }

    // ---- helpers

    private static ModelQueryConfig limited(int maxInListSize) {
        return ModelQueryConfig.defaults().vendorProfiles(List.of(new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return DatabaseVendor.OTHER;
            }

            @Override
            public int maxInListSize() {
                return maxInListSize;
            }

            @Override
            public int maxBindParameters() {
                return 2_000;
            }

            @Override
            public void applyTimeout(Query query, Duration timeout) {}

            @Override
            public NullOrdering defaultAscendingNullOrdering() {
                return NullOrdering.NULLS_FIRST;
            }
        }));
    }

    private static void assertMq(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessage(code.code() + ": " + message);
    }

    private static void withExecutor(ModelQueryConfig config, Consumer<ModelQueryExecutor<KeysetRowEntity>> work) {
        try (EntityManager em = sessions.createEntityManager()) {
            work.accept(ModelQueryExecutor.create(em, KeysetRowEntity.class, config));
        }
    }

    private static void capturingWarnings(List<String> warnings, Runnable work) {
        Logger logger = Logger.getLogger(DefaultModelQueryExecutor.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING) {
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
    }
}
