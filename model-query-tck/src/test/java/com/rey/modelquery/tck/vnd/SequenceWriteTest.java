package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CounterRowEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * {@code setExpression(column, Expr.nextval(...))} at the four write sites (spec api/14 R-WRT-53, api/10 R-COL-28;
 * TCK AC-WRT-46): on H2 and PostgreSQL each written row gets its own value of the fixture's {@code tck_seq}, rendered
 * by the dialect in the provider's own statement and bound as nothing; MySQL, which has no sequences, refuses each
 * write with {@code MQ1509} before any statement.
 */
class SequenceWriteTest {

    record Counter(Long id, Long nextValue) {}

    record CounterValue(Long nextValue) {}

    record SourceItem(Long id) {}

    private static final TableField<CounterRowEntity, CounterRowEntity> COUNTERS =
            TableField.root(CounterRowEntity.class);
    private static final ColumnField<Counter, CounterRowEntity, Long> ID =
            ColumnField.of(Counter.class, COUNTERS, "id", Long.class);
    private static final ColumnField<Counter, CounterRowEntity, Long> NEXT =
            ColumnField.of(Counter.class, COUNTERS, "nextValue", Long.class);
    private static final ExpressionField<Counter, Long> SEQUENCE = Expr.nextval("tck_seq");

    /** The id only: the sequence fills {@code next_value}. */
    private static final InsertColumns<Counter, CounterRowEntity> KEYS =
            InsertColumns.<Counter, CounterRowEntity>of(COUNTERS).addKey(ID, Counter::id);
    private static final InsertColumns<Counter, CounterRowEntity> BOTH =
            InsertColumns.<Counter, CounterRowEntity>of(COUNTERS).addKey(ID, Counter::id).add(NEXT, Counter::nextValue);

    private static final ColumnField<CounterValue, CounterRowEntity, Long> VALUE_ID =
            ColumnField.of(CounterValue.class, COUNTERS, "id", Long.class);
    private static final ColumnField<CounterValue, CounterRowEntity, Long> VALUE_NEXT =
            ColumnField.of(CounterValue.class, COUNTERS, "nextValue", Long.class);
    private static final ModelQuery.Builder<CounterRowEntity, Long, CounterValue> RETURNING =
            ModelQuery.builder(COUNTERS, row -> new CounterValue(row.get(VALUE_NEXT)))
                    .select(SelectSet.of(VALUE_NEXT)).primaryKey(PrimaryKey.of(VALUE_ID));

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<SourceItem, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(SourceItem.class, ITEMS, "id", Long.class);

    private static ModelUpdate.Builder<CounterRowEntity, Long, Counter> update() {
        return ModelUpdate.builder(COUNTERS).primaryKey(PrimaryKey.of(ID));
    }

    // ---- AC-WRT-46

    @TckTest
    void ac_wrt_46_a_chunked_update_writes_a_distinct_sequence_value_per_row_increasing_across_chunks(
            TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL has no sequences");
        var seed = ValuesInsert.builder(BOTH, Long.class, LongStream.rangeClosed(1, 6)
                .mapToObj(id -> new Counter(id, 0L)).toList()).build();
        var numbered = update().setExpression(NEXT, SEQUENCE).all().chunked(ChunkOptions.size(2)).build();
        Map<Long, Long> stored = new LinkedHashMap<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "wrt-46-chunked-update", ds -> inRolledBackTransaction(ds,
                em -> {
                    ModelQueryExecutor<CounterRowEntity> counters = executor(em);
                    counters.insert(seed);
                    assertThat(counters.update(numbered)).isEqualTo(6);
                    stored.putAll(stored(em));
                }));

        assertThat(stored).containsOnlyKeys(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(stored.values()).doesNotHaveDuplicates().allSatisfy(value -> assertThat(value).isPositive());
        // Chunks run in key order, each statement evaluating the sequence per row.
        for (long chunk = 1; chunk < 3; chunk++) {
            long highest = Math.max(stored.get(2 * chunk - 1), stored.get(2 * chunk));
            assertThat(Math.min(stored.get(2 * chunk + 1), stored.get(2 * chunk + 2))).isGreaterThan(highest);
        }
        assertThat(sql.stream().filter(statement -> statement.startsWith("update"))).hasSize(3)
                .allSatisfy(statement -> assertThat(statement).contains("tck_seq"));
    }

    @TckTest
    void ac_wrt_46_a_chunked_insert_values_gives_each_row_its_own_value_and_binds_none_for_it(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL has no sequences");
        var insert = ValuesInsert.builder(KEYS, Long.class, LongStream.rangeClosed(1, 5)
                .mapToObj(id -> new Counter(id, null)).toList())
                .setExpression(NEXT, SEQUENCE).chunked(ChunkOptions.size(2)).build();
        Map<Long, Long> stored = new LinkedHashMap<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "wrt-46-insert-values", ds -> inRolledBackTransaction(ds,
                em -> {
                    assertThat(executor(em).insert(insert)).isEqualTo(5);
                    stored.putAll(stored(em));
                }));

        assertThat(stored).containsOnlyKeys(1L, 2L, 3L, 4L, 5L);
        assertThat(stored.values()).doesNotHaveDuplicates();
        List<String> inserts = sql.stream().filter(statement -> statement.startsWith("insert")).toList();
        // Rows of 2, 2 and 1: one bind per row, the id, and none for the sequence's value.
        assertThat(inserts).hasSize(3);
        assertThat(inserts).extracting(SequenceWriteTest::binds).containsExactly(2L, 2L, 1L);
    }

    @TckTest
    void ac_wrt_46_an_insert_select_writes_one_sequence_value_per_source_row(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL has no sequences");
        var copy = ModelInsert.select(KEYS, ITEMS).map(ID, ITEM_ID).setExpression(NEXT, Expr.nextval("tck_seq"))
                .where(f -> f.in(ITEM_ID, List.of(3L, 5L))).build();
        Map<Long, Long> stored = new LinkedHashMap<>();
        SqlSnapshots.assertMatches(db, "wrt-46-insert-select", ds -> inRolledBackTransaction(ds, em -> {
            assertThat(executor(em).insert(copy)).isEqualTo(2);
            stored.putAll(stored(em));
        }));

        assertThat(stored).containsOnlyKeys(3L, 5L);
        assertThat(stored.values()).doesNotHaveDuplicates();
    }

    @TckTest
    void ac_wrt_46_insert_returning_of_a_counter_upsert_returns_the_sequence_value_then_the_stored_value_plus_one(
            TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL returns no rows of a write and has no sequences");
        var upsert = ValuesInsert.builder(KEYS, Long.class, List.of(new Counter(1L, null)))
                .setExpression(NEXT, SEQUENCE)
                .onConflict(ID).doUpdate(u -> u.setExpression(NEXT, (stored, incoming, cb) -> cb.sum(stored, 1L)))
                .build();
        var counters = new ArrayList<CounterValue>();
        long before = nextSequenceValue(db);
        SqlSnapshots.assertMatches(db, "wrt-46-counter-upsert-returning", ds -> inRolledBackTransaction(ds, em -> {
            counters.addAll(executor(em).insertReturning(upsert, RETURNING.build()));
            counters.addAll(executor(em).insertReturning(upsert, RETURNING.build()));
        }));
        long after = nextSequenceValue(db);

        assertThat(counters).hasSize(2);
        long first = counters.get(0).nextValue();
        assertThat(first).as("the sequence's next value, which the insert wrote").isEqualTo(before + 1);
        assertThat(counters.get(1)).as("the stored value plus one").isEqualTo(new CounterValue(first + 1));
        // The conflicting row's VALUES were evaluated too, so the update consumed a value it did not write.
        assertThat(after).as("one value per statement, the updating one included").isEqualTo(first + 2);
    }

    @TckTest
    void ac_wrt_46_on_mysql_every_write_site_is_mq1509_with_no_statement_run(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.MYSQL, "H2 and PostgreSQL have sequences");
        var updated = update().setExpression(NEXT, SEQUENCE).all().chunked(ChunkOptions.size(2)).build();
        var values = ValuesInsert.builder(KEYS, Long.class, List.of(new Counter(1L, null)))
                .setExpression(NEXT, SEQUENCE).build();
        var upsert = ValuesInsert.builder(BOTH, Long.class, List.of(new Counter(1L, 1L))).onConflict(ID)
                .doUpdate(u -> u.setExpression(NEXT, SEQUENCE)).anyUniqueKey().build();
        var copy = ModelInsert.select(KEYS, ITEMS).map(ID, ITEM_ID).setExpression(NEXT, Expr.nextval("tck_seq"))
                .all().chunked(ChunkOptions.size(2)).build();
        var refusals = new ArrayList<Throwable>();
        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            refusals.add(catchThrowable(() -> executor(em).update(updated)));
            refusals.add(catchThrowable(() -> executor(em).insert(values)));
            refusals.add(catchThrowable(() -> executor(em).insert(upsert)));
            refusals.add(catchThrowable(() -> executor(em).insert(copy)));
        }));

        assertThat(sql).as("no statement ran, not even a chunk's key select").isEmpty();
        assertThat(refusals).hasSize(4).allSatisfy(refusal -> assertThat(refusal)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                .hasMessageContaining("no sequence support"));
        // A returning write is refused at method entry, before its write is looked at (R-WRT-50).
        List<String> none = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em ->
                assertThatThrownBy(() -> executor(em).insertReturning(values, RETURNING.build()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))));
        assertThat(none).isEmpty();
    }

    @TckTest
    void ac_wrt_46_an_insert_id_is_mq1802_on_first_execution_before_any_statement(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL refuses the sequence first (MQ1509)");
        var columns = InsertColumns.<Counter, CounterRowEntity>of(COUNTERS).add(NEXT, Counter::nextValue);
        var insert = ValuesInsert.builder(columns, Long.class, List.of(new Counter(null, 1L)))
                .setExpression(ID, SEQUENCE).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em ->
                assertCode(() -> executor(em).insert(insert), MqCode.MQ1802, "setExpression(...) writes "
                        + "CounterRowEntity's id")));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_wrt_46_a_sequence_in_a_conflict_where_or_a_returning_query_is_refused_before_any_statement(
            TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL refuses the sequence first (MQ1509)");
        var filtered = ValuesInsert.builder(BOTH, Long.class, List.of(new Counter(1L, 1L))).onConflict(ID)
                .doUpdate(u -> u.setFromRow(NEXT).where(f -> f.gt(SEQUENCE, 0L))).build();
        var plain = ValuesInsert.builder(KEYS, Long.class, List.of(new Counter(1L, null)))
                .setExpression(NEXT, SEQUENCE).build();
        var nextSelected = Expr.<CounterValue>nextval("tck_seq");
        var returning = ModelQuery.builder(COUNTERS, row -> new CounterValue(row.get(nextSelected)))
                .select(SelectSet.of(nextSelected)).primaryKey(PrimaryKey.of(VALUE_ID)).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            assertCode(() -> executor(em).insert(filtered), MqCode.MQ1509, "no filter, having, order or group key");
            assertCode(() -> executor(em).insertReturning(plain, returning), MqCode.MQ1809, "nextval(tck_seq)");
        }));
        assertThat(sql).as("no insert ran").noneMatch(statement -> statement.contains("counter_rows"));
    }

    @TckTest
    void ac_wrt_46_a_sequence_in_a_conflict_arbiter_is_mq1804_at_build(TckDatabase db) {
        assertCode(() -> ValuesInsert.builder(BOTH, Long.class, List.of(new Counter(1L, 1L))).onConflict(ID)
                .where(f -> f.gt(SEQUENCE, 0L)).doNothing().build(), MqCode.MQ1804, "nextval(tck_seq)");
    }

    // ---- helpers

    private static ModelQueryExecutor<CounterRowEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, CounterRowEntity.class, ModelQueryConfig.defaults());
    }

    /** Takes {@code tck_seq}'s next value in a session of its own: a sequence is not rolled back. */
    private static long nextSequenceValue(TckDatabase db) {
        String sql = db.vendor() == TckVendor.POSTGRESQL ? "select nextval('tck_seq')"
                : "select next value for tck_seq";
        long[] value = new long[1];
        inRolledBackTransaction(JoinTestSupport.dataSource(db),
                em -> value[0] = ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
        return value[0];
    }

    /** Every stored counter's value by id, in id order. */
    private static Map<Long, Long> stored(EntityManager em) {
        var stored = new LinkedHashMap<Long, Long>();
        em.createQuery("select c.id, c.nextValue from CounterRowEntity c order by c.id", Object[].class)
                .getResultList().forEach(row -> stored.put((Long) row[0], (Long) row[1]));
        return stored;
    }

    /** Runs {@code work} in a transaction over {@code ds} and rolls it back, so the fixture stays as seeded. */
    private static void inRolledBackTransaction(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory factory = JoinTestSupport.sessionFactory(ds)) {
            factory.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.accept(em);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }

    private static long binds(String statement) {
        return statement.chars().filter(c -> c == '?').count();
    }

    private static void assertCode(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(message);
    }
}
