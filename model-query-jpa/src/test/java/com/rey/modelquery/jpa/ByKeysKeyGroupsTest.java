package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.KeyGroups;
import com.rey.modelquery.core.KeyGroups.GroupingRule;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
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
 * {@code byKeys} and {@code one(q, key)} over a query with key groups, and {@code MQ2215} on every other read
 * (engine/20 R-EXE-18, D-204, D-206, D-207, D-208) over in-memory H2.
 */
class ByKeysKeyGroupsTest {

    record Row(Long id, Integer a) {}

    record Keyed(String id) {}

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row, KeysetRowEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> A =
            ColumnField.of(Row.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> B =
            ColumnField.of(Row.class, ROOT, "b", Integer.class);

    /** The id as {@code "k<id>"} or {@code "x<id>"}; {@code toAttribute} reads the digits only. */
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
    private static final ColumnField<Keyed, KeysetRowEntity, Integer> KEYED_B =
            ColumnField.of(Keyed.class, ROOT, "b", Integer.class);

    static final QueryParam<Integer> LO = QueryParam.of("bFrom", Integer.class);
    static final QueryParam<Integer> HI = QueryParam.of("bTo", Integer.class);
    /** The catch-all group: every {@code b} the seed holds. */
    static final int ALL = 9;

    /** Ids 1 to 3 are group 0, {@code b} in [0, 100); the others group 1, {@code b} in [100, 200). */
    static int bucket(long id) {
        return id <= 3 ? 0 : 1;
    }

    static int lo(int group) {
        return group == ALL ? 0 : group * 100;
    }

    static int hi(int group) {
        return group == ALL ? 1_000 : group * 100 + 100;
    }

    static final KeyGroups<Long, Integer> VERIFY = KeyGroups.by(Long.class, ByKeysKeyGroupsTest::bucket)
            .bind(LO, ByKeysKeyGroupsTest::lo).bind(HI, ByKeysKeyGroupsTest::hi).verifyMissing(ALL);
    static final KeyGroups<Long, Integer> ASSUME = KeyGroups.by(Long.class, ByKeysKeyGroupsTest::bucket)
            .bind(LO, ByKeysKeyGroupsTest::lo).bind(HI, ByKeysKeyGroupsTest::hi).assumeInRange();

    private static final List<String> SQL = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> RULE_CALLS = Collections.synchronizedList(new ArrayList<>());

    private static SessionFactory sessions;

    /** Ids 1 to 6; {@code b} lies in its id's group, except row 6's 50, which lies in group 0's range. */
    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:bykeys-groups;DB_CLOSE_DELAY=-1")
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
            em.persist(new KeysetRowEntity(1, 1, 10, 0));
            em.persist(new KeysetRowEntity(2, 2, 20, 0));
            em.persist(new KeysetRowEntity(3, 3, 30, 0));
            em.persist(new KeysetRowEntity(4, 4, 110, 1));
            em.persist(new KeysetRowEntity(5, 5, 120, 1));
            em.persist(new KeysetRowEntity(6, 6, 50, 1));
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
        RULE_CALLS.clear();
    }

    private static ModelQuery.Builder<KeysetRowEntity, Long, Row> ranged() {
        return ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(A))).select(SelectSet.of(ID, A))
                .primaryKey(PrimaryKey.of(ID))
                .where(f -> f.compare(B, Op.GTE, Expr.param(LO)).compare(B, Op.LT, Expr.param(HI)));
    }

    /** The same query without key groups, bound to the catch-all's range. */
    private static final ModelQuery<KeysetRowEntity, Long, Row> UNGROUPED =
            ranged().bind(LO, lo(ALL)).bind(HI, hi(ALL)).build();

    /** A rule recording each call and answering {@code answer}. */
    private static GroupingRule rule(boolean answer) {
        return (keys, groups) -> {
            RULE_CALLS.add(keys + " keys, " + groups + " groups");
            return answer;
        };
    }

    private static KeyGroups<Long, Integer> ruled(GroupingRule rule, boolean verify) {
        var builder = KeyGroups.by(Long.class, ByKeysKeyGroupsTest::bucket).bind(LO, ByKeysKeyGroupsTest::lo)
                .bind(HI, ByKeysKeyGroupsTest::hi).groupWhen(rule, ALL);
        return verify ? builder.verifyMissing(ALL) : builder.assumeInRange();
    }

    // ---- AC-EXE-25

    @Test
    void ac_exe_25_by_keys_with_verify_missing_returns_the_ungrouped_map_and_re_reads_a_row_outside_its_range() {
        ModelQuery<KeysetRowEntity, Long, Row> grouped = ranged().keyGroups(VERIFY).build();
        List<Long> keys = List.of(6L, 99L, 1L, 4L, 2L, 5L, 3L, 1L);
        List<String> warnings = new ArrayList<>();
        capturingWarnings(warnings, () -> withExecutor(ModelQueryConfig.defaults(), executor -> {
            Map<Long, Row> expected = executor.byKeys(UNGROUPED, keys);
            assertThat(expected.keySet()).containsExactly(6L, 1L, 4L, 2L, 5L, 3L);
            SQL.clear();
            assertThat(executor.byKeys(grouped, keys)).containsExactlyEntriesOf(expected);
            // One round per group, then one re-read of the keys that found no row: 6 and 99.
            assertThat(SQL).hasSize(3);
            SQL.clear();
            assertThat(executor.byKeys(grouped, keys)).containsExactlyEntriesOf(expected);
            assertThat(SQL).hasSize(3);
        }));
        // MQ2608 once per definition, naming the key, never a bound value.
        assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning).startsWith("MQ2608: ")
                .contains("the row of key 6 was found only by the verifyMissing re-read").doesNotContain("1000"));
    }

    @Test
    void ac_exe_25_when_every_key_finds_a_row_in_its_group_no_re_read_runs() {
        List<String> warnings = new ArrayList<>();
        capturingWarnings(warnings, () -> withExecutor(ModelQueryConfig.defaults(), executor ->
                assertThat(executor.byKeys(ranged().keyGroups(VERIFY).build(), List.of(4L, 1L, 5L)).keySet())
                        .containsExactly(4L, 1L, 5L)));
        assertThat(SQL).hasSize(2);
        assertThat(warnings).isEmpty();
    }

    @Test
    void ac_exe_25_by_keys_with_assume_in_range_returns_the_ungrouped_map_when_every_row_lies_in_its_range() {
        ModelQuery<KeysetRowEntity, Long, Row> grouped = ranged().keyGroups(ASSUME).build();
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            List<Long> keys = List.of(5L, 1L, 4L, 2L, 3L);
            Map<Long, Row> expected = executor.byKeys(UNGROUPED, keys);
            SQL.clear();
            assertThat(executor.byKeys(grouped, keys)).containsExactlyEntriesOf(expected).hasSize(5);
            assertThat(SQL).hasSize(2);
            // Row 6 lies outside its group's range, and nothing reads it again.
            SQL.clear();
            assertThat(executor.byKeys(grouped, List.of(6L, 1L)).keySet()).containsExactly(1L);
            assertThat(SQL).hasSize(2);
        });
    }

    @Test
    void ac_exe_25_rounds_split_within_a_group_under_the_bind_limit_and_no_keys_run_nothing() {
        ModelQuery<KeysetRowEntity, Long, Row> grouped = ranged().keyGroups(ASSUME).build();
        withExecutor(limited(2), executor -> {
            assertThat(executor.byKeys(grouped, List.of(1L, 2L, 3L, 4L, 5L)).keySet())
                    .containsExactly(1L, 2L, 3L, 4L, 5L);
            // Group 0's three keys take two rounds of at most two, group 1's two keys one.
            assertThat(SQL).hasSize(3);
            SQL.clear();
            assertThat(executor.byKeys(grouped, List.of())).isEmpty();
            assertThat(SQL).isEmpty();
        });
    }

    // ---- AC-EXE-26

    @Test
    void ac_exe_26_an_unlocked_by_keys_over_interleaved_groups_keeps_one_round_per_group() {
        // Odd and even keys, both bound to every b: interleaved in key order, unlike the id ranges of bucket(...).
        KeyGroups<Long, Integer> oddEven = KeyGroups.by(Long.class, (Long key) -> (int) (key % 2))
                .bind(LO, group -> lo(ALL)).bind(HI, group -> hi(ALL)).assumeInRange();
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            assertThat(executor.byKeys(ranged().keyGroups(oddEven).build(), List.of(4L, 3L, 2L, 1L)).keySet())
                    .containsExactly(4L, 3L, 2L, 1L);
            // Without a lock, no lock order to keep: one round per group, not one per run of sorted keys (D-210).
            assertThat(SQL).hasSize(2);
        });
    }

    @Test
    void ac_exe_26_one_by_key_binds_the_keys_group_in_one_statement() {
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            ModelQuery<KeysetRowEntity, Long, Row> assumed = ranged().keyGroups(ASSUME).build();
            assertThat(executor.one(assumed, 4L)).contains(new Row(4L, 4));
            assertThat(executor.one(assumed, 1L)).contains(new Row(1L, 1));
            assertThat(SQL).hasSize(2);
            SQL.clear();
            assertThat(executor.one(assumed, 6L)).isEmpty();
            assertThat(SQL).hasSize(1);
        });
    }

    @Test
    void ac_exe_26_one_by_key_under_verify_missing_re_reads_only_when_its_group_finds_no_row() {
        ModelQuery<KeysetRowEntity, Long, Row> verified = ranged().keyGroups(VERIFY).build();
        List<String> warnings = new ArrayList<>();
        capturingWarnings(warnings, () -> withExecutor(ModelQueryConfig.defaults(), executor -> {
            assertThat(executor.one(verified, 4L)).contains(new Row(4L, 4));
            assertThat(SQL).hasSize(1);
            SQL.clear();
            assertThat(executor.one(verified, 6L)).contains(new Row(6L, 6));
            assertThat(SQL).hasSize(2);
            SQL.clear();
            assertThat(executor.one(verified, 99L)).isEmpty();
            assertThat(SQL).hasSize(2);
        }));
        assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning).startsWith("MQ2608: ")
                .contains("the row of key 6"));
    }

    @Test
    void ac_exe_26_every_other_read_of_a_grouped_query_is_mq2215_before_any_statement() {
        ModelQuery<KeysetRowEntity, Long, Row> grouped = ranged().keyGroups(VERIFY).build();
        InsertColumns<Row, KeysetRowEntity> columns =
                InsertColumns.<Row, KeysetRowEntity>of(ROOT).addKey(ID, Row::id).add(A, Row::a);
        ModelUpdate<KeysetRowEntity, Row> update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(A, 7)
                .where(f -> f.eq(ID, 1L)).build();
        ModelDelete<KeysetRowEntity, Row> delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.eq(ID, 1L)).build();
        Map<String, Function<ModelQueryExecutor<KeysetRowEntity>, Object>> reads = Map.ofEntries(
                Map.entry("list(query, limit)", executor -> executor.list(grouped, Limit.unlimited())),
                Map.entry("page(query, PageSpec, CountMode)",
                        executor -> executor.page(grouped, PageSpec.of(0, 10), CountMode.COUNT)),
                Map.entry("page(query, KeysetSpec)", executor -> executor.page(grouped, KeysetSpec.first(10))),
                Map.entry("count(query)", executor -> executor.count(grouped)),
                Map.entry("one(query)", executor -> executor.one(grouped)),
                Map.entry("first(query)", executor -> executor.first(grouped)),
                Map.entry("export(...)", executor -> executor.export(grouped, ExportOptions.defaults(),
                        page -> page, row -> {})),
                Map.entry("stream(...)", executor -> executor.stream(grouped, Limit.unlimited(), rows -> 0)),
                Map.entry("updateReturning(..., returning)", executor -> executor.updateReturning(update, grouped)),
                Map.entry("deleteReturning(..., returning)", executor -> executor.deleteReturning(delete, grouped)),
                Map.entry("insertReturning(..., returning)", executor -> executor.insertReturning(
                        ValuesInsert.builder(columns, Long.class, List.of(new Row(7L, 7))).build(), grouped)),
                Map.entry("persist(persist, returning)", executor -> executor.persist(
                        ModelPersist.of(columns, Long.class, new Row(7L, 7)), grouped)));
        withExecutor(ModelQueryConfig.defaults(), executor -> reads.forEach((call, read) -> {
            assertMq(() -> read.apply(executor), MqCode.MQ2215, call + " can't read a query with key groups, "
                    + "which bind [bFrom: Integer, bTo: Integer] only per group of byKeys(query, keys) and "
                    + "one(query, key)");
            assertThat(SQL).as(call).isEmpty();
        }));
    }

    @Test
    void ac_exe_26_build_query_refuses_a_round_value_for_a_param_not_bound_per_round() {
        QueryParam<Integer> other = QueryParam.of("other", Integer.class);
        try (EntityManager em = sessions.createEntityManager()) {
            var cb = em.getCriteriaBuilder();
            ModelQuery<KeysetRowEntity, Long, Row> grouped = ranged().keyGroups(ASSUME).build();
            BuiltQuery<Row> built = grouped.buildQuery(cb, Phase.MODEL, RenderOptions.portable(),
                    Map.of(LO, 0, HI, 100));
            assertThat(built.query().getRestriction()).isNotNull();
            assertThatThrownBy(() -> grouped.buildQuery(cb, Phase.MODEL, RenderOptions.portable(),
                    Map.of(other, 1)))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("the param other: Integer isn't bound per round by key groups");
            // A param bound by bind(...) keeps its binding: a round can't replace it.
            assertThatThrownBy(() -> UNGROUPED.buildQuery(cb, Phase.MODEL, RenderOptions.portable(),
                    Map.of(LO, 0)))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("the param bFrom: Integer isn't bound per round by key groups");
        }
    }

    // ---- AC-EXE-28

    @Test
    void ac_exe_28_two_caller_keys_of_one_value_are_read_once_grouped_by_the_first_with_no_mq2213() {
        // "k<id>" keys are group 0, "x<id>" keys group 1; both convert to the id.
        KeyGroups<String, Integer> byPrefix = KeyGroups.by(String.class, (String key) -> key.startsWith("k") ? 0 : 1)
                .bind(LO, ByKeysKeyGroupsTest::lo).bind(HI, ByKeysKeyGroupsTest::hi).assumeInRange();
        ModelQuery<KeysetRowEntity, String, Keyed> keyed = ModelQuery.builder(ROOT, row -> new Keyed(row.get(KEYED_ID)))
                .select(SelectSet.of(KEYED_ID))
                .primaryKey(PrimaryKey.of(KEYED_ID))
                .where(f -> f.compare(KEYED_B, Op.GTE, Expr.param(LO)).compare(KEYED_B, Op.LT, Expr.param(HI)))
                .keyGroups(byPrefix)
                .build();
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            Map<String, Keyed> result = executor.byKeys(keyed, List.of("k2", "x2", "k2"));
            assertThat(List.copyOf(result.keySet())).containsExactly("k2", "x2");
            assertThat(result.get("x2")).isSameAs(result.get("k2")).isEqualTo(new Keyed("k2"));
            assertThat(SQL).hasSize(1);
            // Grouped by "x2", the first: group 1's range holds no row 2.
            SQL.clear();
            assertThat(executor.byKeys(keyed, List.of("x2", "k2"))).isEmpty();
            assertThat(SQL).hasSize(1);
        });
    }

    // ---- AC-EXE-29

    @Test
    void ac_exe_29_a_false_rule_runs_ungrouped_with_the_catch_all_and_a_true_rule_runs_per_group() {
        List<Long> keys = List.of(6L, 1L, 4L, 99L);
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            // False: one statement with the catch-all's values, so row 6 is found; no re-read, even for key 99.
            assertThat(executor.byKeys(ranged().keyGroups(ruled(rule(false), true)).build(), keys).keySet())
                    .containsExactly(6L, 1L, 4L);
            assertThat(SQL).hasSize(1);
            assertThat(RULE_CALLS).containsExactly("4 keys, 2 groups");
            SQL.clear();
            RULE_CALLS.clear();
            // True, under assumeInRange: one statement per group, and row 6 lies outside its group.
            assertThat(executor.byKeys(ranged().keyGroups(ruled(rule(true), false)).build(), keys).keySet())
                    .containsExactly(1L, 4L);
            assertThat(SQL).hasSize(2);
            assertThat(RULE_CALLS).containsExactly("4 keys, 2 groups");
        });
    }

    @Test
    void ac_exe_29_both_branches_return_the_same_map_when_every_row_lies_in_its_group() {
        List<Long> keys = List.of(5L, 1L, 4L, 2L, 3L);
        withExecutor(ModelQueryConfig.defaults(), executor -> assertThat(
                executor.byKeys(ranged().keyGroups(ruled(rule(true), false)).build(), keys))
                .containsExactlyEntriesOf(executor.byKeys(ranged().keyGroups(ruled(rule(false), false)).build(), keys))
                .hasSize(5));
    }

    @Test
    void ac_exe_29_the_rule_is_not_called_for_one_group_for_equal_round_counts_or_for_no_keys() {
        withExecutor(ModelQueryConfig.defaults(), executor -> {
            ModelQuery<KeysetRowEntity, Long, Row> ruled = ranged().keyGroups(ruled(rule(false), false)).build();
            assertThat(executor.byKeys(ruled, List.of(1L, 2L, 3L)).keySet()).containsExactly(1L, 2L, 3L);
            assertThat(executor.one(ruled, 4L)).contains(new Row(4L, 4));
            assertThat(executor.byKeys(ruled, List.of())).isEmpty();
            assertThat(RULE_CALLS).isEmpty();
        });
        // Two keys per group at two keys a round: grouped, two rounds; ungrouped, two rounds too.
        withExecutor(limited(2), executor -> {
            SQL.clear();
            assertThat(executor.byKeys(ranged().keyGroups(ruled(rule(false), false)).build(),
                    List.of(1L, 2L, 4L, 5L)).keySet()).containsExactly(1L, 2L, 4L, 5L);
            assertThat(SQL).hasSize(2);
            assertThat(RULE_CALLS).isEmpty();
        });
    }

    @Test
    void ac_exe_29_a_null_group_value_and_the_rules_exception_fail_before_any_statement() {
        for (boolean answer : List.of(true, false)) {
            KeyGroups<Long, Integer> nullOne = KeyGroups.by(Long.class, ByKeysKeyGroupsTest::bucket)
                    .bind(LO, group -> group == 1 ? null : lo(group)).bind(HI, ByKeysKeyGroupsTest::hi)
                    .groupWhen(rule(answer), ALL).assumeInRange();
            withExecutor(ModelQueryConfig.defaults(), executor -> assertThatThrownBy(() ->
                    executor.byKeys(ranged().keyGroups(nullOne).build(), List.of(1L, 4L)))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502)));
            assertThat(SQL).isEmpty();
            assertThat(RULE_CALLS).isEmpty();
        }
        IllegalStateException failure = new IllegalStateException("the rule failed");
        GroupingRule failing = (keys, groups) -> {
            throw failure;
        };
        withExecutor(ModelQueryConfig.defaults(), executor -> assertThatThrownBy(() ->
                executor.byKeys(ranged().keyGroups(ruled(failing, true)).build(), List.of(1L, 4L)))
                .isSameAs(failure));
        assertThat(SQL).isEmpty();
    }

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
            public com.rey.modelquery.core.NullOrdering defaultAscendingNullOrdering() {
                return com.rey.modelquery.core.NullOrdering.NULLS_FIRST;
            }
        }));
    }

    private static void assertMq(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(code.code() + ": ").hasMessageContaining(message);
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
