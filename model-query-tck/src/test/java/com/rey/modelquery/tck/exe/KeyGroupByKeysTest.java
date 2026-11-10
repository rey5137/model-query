package com.rey.modelquery.tck.exe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.KeyGroups;
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
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SortSpec;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.fch.QStampedBuyer;
import com.rey.modelquery.tck.fch.StampedBuyer;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * {@code byKeys} and {@code one(q, key)} over a query with key groups on every vendor: one round per group with its
 * values bound, the {@code verifyMissing} re-read, copies keeping the groups, {@code MQ2215} on every other read and
 * the dedupe on the converted key (spec engine/20 R-EXE-18, D-204, D-206, D-207; AC-EXE-25 to AC-EXE-28). Customer
 * {@code c} is created {@code c} hours after 2020-01-01, so its key encodes the day it was created on.
 */
class KeyGroupByKeysTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final QueryParam<LocalDateTime> CREATED_FROM = QueryParam.of("createdFrom", LocalDateTime.class);
    private static final QueryParam<LocalDateTime> CREATED_TO = QueryParam.of("createdTo", LocalDateTime.class);
    private static final RowLock LOCK = RowLock.exclusive();

    /** A half-open range of creation times. */
    record Range(LocalDateTime from, LocalDateTime to) {}

    /** Limits every Tier-1 vendor binds for a timestamp column (MySQL DATETIME: 1000-01-01 to 9999-12-31). */
    private static final Range ALL = new Range(LocalDateTime.of(1000, 1, 1, 0, 0),
            LocalDateTime.of(9999, 12, 31, 0, 0));

    /** The day customer {@code customer} was created on: its key's group. */
    private static Range dayOf(long customer) {
        LocalDateTime day = BASE.plusHours(customer).toLocalDate().atStartOfDay();
        return new Range(day, day.plusDays(1));
    }

    /** Ten days after the day customer {@code customer} was created on: a range its row lies outside. */
    private static Range tenDaysAfter(long customer) {
        Range day = dayOf(customer);
        return new Range(day.from().plusDays(10), day.to().plusDays(10));
    }

    /** {@code dayOf}, except that customer {@code wrong} is put ten days later, outside its row's range. */
    private static Function<Long, Range> dayOfExcept(long wrong) {
        return customer -> customer == wrong ? tenDaysAfter(customer) : dayOf(customer);
    }

    private static KeyGroups.Builder<Long, Range> byDay(Function<Long, Range> day) {
        return KeyGroups.by(Long.class, day).bind(CREATED_FROM, Range::from).bind(CREATED_TO, Range::to);
    }

    /** Customers created in the range their key's group binds. */
    private static ModelQuery.Builder<CustomerEntity, Long, StampedBuyer> ranged() {
        return QStampedBuyer.query().select(QStampedBuyer.ALL)
                .where(f -> f.compare(QStampedBuyer.CREATED_AT, Op.GTE, Expr.param(CREATED_FROM))
                        .compare(QStampedBuyer.CREATED_AT, Op.LT, Expr.param(CREATED_TO)));
    }

    /** The same query without key groups, bound to the catch-all's range. */
    private static ModelQuery<CustomerEntity, Long, StampedBuyer> ungrouped() {
        return ranged().bind(CREATED_FROM, ALL.from()).bind(CREATED_TO, ALL.to()).build();
    }

    // ---- AC-EXE-25

    @TckTest
    void ac_exe_25_by_keys_with_verify_missing_returns_the_ungrouped_map_and_re_reads_a_row_outside_its_range(
            TckDatabase db) {
        // Customers 30 and 26 are created on January 2, 5 on January 1, and 50 on January 3 but put on January 13;
        // 5000 has no row.
        var grouped = ranged().keyGroups(byDay(dayOfExcept(50)).verifyMissing(ALL)).build();
        List<Long> keys = List.of(30L, 5L, 50L, 5000L, 5L, 26L);
        Map<Long, StampedBuyer> expected = new LinkedHashMap<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> expected.putAll(executor.byKeys(ungrouped(),
                keys))));
        assertThat(expected.keySet()).containsExactly(30L, 5L, 50L, 26L);

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor ->
                assertThat(executor.byKeys(grouped, keys)).containsExactlyEntriesOf(expected)));
        // One round per group in first-occurrence order, then the re-read of 50 and 5000 with the catch-all's range.
        assertThat(sql).extracting(KeyGroupByKeysTest::binds).containsExactly(4L, 3L, 3L, 3L, 4L);
        assertThat(sql.get(4)).isEqualTo(sql.get(0)); // the re-read's statement text is a two-key round's
    }

    @TckTest
    void ac_exe_25_by_keys_with_assume_in_range_returns_the_ungrouped_map_and_no_re_read_runs_when_all_are_found(
            TckDatabase db) {
        // Customers 26 and 30 are created on January 2, 5 on January 1 and 700 on January 30.
        List<Long> keys = List.of(26L, 5L, 30L, 700L, 5L);
        Map<Long, StampedBuyer> expected = new LinkedHashMap<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> expected.putAll(executor.byKeys(ungrouped(),
                keys))));
        assertThat(expected.keySet()).containsExactly(26L, 5L, 30L, 700L);

        for (var groups : List.of(byDay(KeyGroupByKeysTest::dayOf).assumeInRange(),
                byDay(KeyGroupByKeysTest::dayOf).verifyMissing(ALL))) {
            var grouped = ranged().keyGroups(groups).build();
            List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor ->
                    assertThat(executor.byKeys(grouped, keys)).containsExactlyEntriesOf(expected)));
            assertThat(sql).extracting(KeyGroupByKeysTest::binds).containsExactly(4L, 3L, 3L);
        }
    }

    // ---- AC-EXE-26

    @TckTest
    void ac_exe_26_one_by_key_binds_the_keys_group_and_re_reads_only_when_its_group_finds_no_row(TckDatabase db) {
        var assumed = ranged().keyGroups(byDay(dayOfExcept(30)).assumeInRange()).build();
        var verified = ranged().keyGroups(byDay(dayOfExcept(30)).verifyMissing(ALL)).build();
        StampedBuyer found = new StampedBuyer(26L, BASE.plusHours(26));
        StampedBuyer outside = new StampedBuyer(30L, BASE.plusHours(30));

        List<String> assumedSql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            assertThat(executor.one(assumed, 26L)).contains(found);
            // Customer 30's group binds January 12: its row isn't in that range.
            assertThat(executor.one(assumed, 30L)).isEmpty();
        }));
        // One statement each: the group's two bounds, the key and the limit.
        assertThat(assumedSql).extracting(KeyGroupByKeysTest::binds).containsExactly(4L, 4L);

        List<String> verifiedSql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            assertThat(executor.one(verified, 26L)).contains(found);
            assertThat(executor.one(verified, 30L)).contains(outside);
        }));
        // 26 is found in its group; 30's group finds no row, so the catch-all reads it again.
        assertThat(verifiedSql).hasSize(3);
    }

    @TckTest
    void ac_exe_26_locked_by_keys_and_one_by_key_keep_the_groups_through_lock_fetch_and_order_copies(TckDatabase db) {
        // Customer 30 is put ten days after its row: a copy that kept the groups doesn't find it.
        var grouped = ranged().keyGroups(byDay(dayOfExcept(30)).assumeInRange()).build();
        Map<String, ModelQuery<CustomerEntity, Long, StampedBuyer>> copies = new LinkedHashMap<>();
        copies.put("withLock", grouped.withLock(LOCK));
        copies.put("withFetch", grouped.withFetch(FetchPlan.of(QStampedBuyer.ALL)));
        copies.put("orderedBy", grouped.orderedBy(SortSpec.of(SortSpec.Key.desc("createdAt"))));
        copies.put("withLock of orderedBy",
                grouped.orderedBy(SortSpec.of(SortSpec.Key.desc("createdAt"))).withLock(LOCK));
        copies.forEach((name, copy) -> {
            List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
                assertThat(executor.byKeys(copy, List.of(30L, 5L, 26L)).keySet()).as(name).containsExactly(5L, 26L);
                assertThat(executor.one(copy, 30L)).as(name).isEmpty();
                assertThat(executor.one(copy, 26L)).as(name).isPresent();
            }));
            // byKeys runs a round per group, January 12 for 30, January 1 for 5 and January 2 for 26, then each
            // one(q, key) runs one statement.
            assertThat(sql).as(name).hasSize(5);
            if (name.startsWith("withLock")) {
                assertThat(sql).as(name).allSatisfy(statement ->
                        assertThat(locked(statement)).as("locked statement, but: %s", statement).isTrue());
            }
        });
    }

    @TckTest
    void ac_exe_26_a_locked_by_keys_over_interleaved_groups_locks_its_rounds_in_ascending_key_order(TckDatabase db) {
        // Customers 26 to 31 are all created on January 2: 28's group binds every time, the others' that day.
        var groups = byDay(customer -> customer == 28 ? ALL : dayOf(customer)).assumeInRange();
        var locked = ranged().keyGroups(groups).build().withLock(LOCK);
        List<Long> keys = List.of(31L, 28L, 26L, 30L, 27L, 29L);
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor ->
                assertThat(executor.byKeys(locked, keys).keySet()).containsExactlyElementsOf(keys)));
        // The sorted keys in runs of one group, {26, 27}, {28} then {29, 30, 31}, each with its group's two bounds,
        // so each locked round's keys lie above the last's; per group it would be {31, 26, 30, 27, 29} then {28}.
        assertThat(sql).extracting(KeyGroupByKeysTest::binds).containsExactly(4L, 3L, 5L);
        assertThat(sql).allSatisfy(statement ->
                assertThat(locked(statement)).as("locked statement, but: %s", statement).isTrue());
    }

    @TckTest
    void ac_exe_26_every_other_read_of_a_grouped_query_is_mq2215_before_any_statement(TckDatabase db) {
        var grouped = ranged().keyGroups(byDay(KeyGroupByKeysTest::dayOf).verifyMissing(ALL)).build();
        Map<String, Function<ModelQueryExecutor<CustomerEntity>, Object>> reads = new LinkedHashMap<>();
        reads.put("list(query, limit)", executor -> executor.list(grouped, Limit.unlimited()));
        reads.put("page(query, PageSpec, CountMode)",
                executor -> executor.page(grouped, PageSpec.of(0, 10), CountMode.COUNT));
        reads.put("page(query, KeysetSpec)", executor -> executor.page(grouped, KeysetSpec.first(10)));
        reads.put("count(query)", executor -> executor.count(grouped));
        reads.put("one(query)", executor -> executor.one(grouped));
        reads.put("first(query)", executor -> executor.first(grouped));
        reads.put("export(...)", executor -> executor.export(grouped, ExportOptions.defaults(), page -> page,
                row -> {}));
        reads.put("stream(...)", executor -> executor.stream(grouped, Limit.unlimited(), rows -> 0));
        // The returning writes too: refused before the write runs, so the transaction changes nothing.
        InsertColumns<StampedBuyer, CustomerEntity> columns = InsertColumns.<StampedBuyer, CustomerEntity>of(
                QStampedBuyer.ROOT).addKey(QStampedBuyer.ID, StampedBuyer::id)
                .add(QStampedBuyer.CREATED_AT, StampedBuyer::createdAt);
        StampedBuyer added = new StampedBuyer(9_000L, BASE);
        var update = ModelUpdate.builder(QStampedBuyer.ROOT).primaryKey(QStampedBuyer.KEY)
                .set(QStampedBuyer.CREATED_AT, BASE).where(f -> f.eq(QStampedBuyer.ID, 5L)).build();
        var delete = ModelDelete.builder(QStampedBuyer.ROOT).primaryKey(QStampedBuyer.KEY)
                .where(f -> f.eq(QStampedBuyer.ID, 5L)).build();
        reads.put("updateReturning(..., returning)", executor -> executor.updateReturning(update, grouped));
        reads.put("deleteReturning(..., returning)", executor -> executor.deleteReturning(delete, grouped));
        reads.put("insertReturning(..., returning)", executor -> executor.insertReturning(
                ValuesInsert.builder(columns, Long.class, List.of(added)).build(), grouped));
        reads.put("persist(persist, returning)", executor -> executor.persist(
                ModelPersist.of(columns, Long.class, added), grouped));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> reads.forEach((call, read) ->
                assertThatThrownBy(() -> read.apply(executor)).as(call)
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2215))
                        .hasMessageContaining(call + " can't read a query with key groups, which bind "
                                + "[createdFrom: LocalDateTime, createdTo: LocalDateTime] only per group"))));
        assertThat(sql).isEmpty();
    }

    // ---- AC-EXE-27

    @TckTest
    void ac_exe_27_build_refuses_a_key_type_mismatch_a_doubly_bound_param_and_an_unread_group_param(
            TckDatabase db) {
        // Only a raw cast gets past the compiler's check of the key type.
        @SuppressWarnings({"unchecked", "rawtypes"})
        KeyGroups<Long, ?> strings = (KeyGroups) KeyGroups.by(String.class, (String key) -> dayOf(key.length()))
                .bind(CREATED_FROM, Range::from).bind(CREATED_TO, Range::to).assumeInRange();
        var fromOnly = QStampedBuyer.query().select(QStampedBuyer.ALL)
                .where(f -> f.compare(QStampedBuyer.CREATED_AT, Op.GTE, Expr.param(CREATED_FROM)));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            assertMq1708(() -> ranged().keyGroups(strings).build(), "String");
            assertMq1708(() -> ranged().bind(CREATED_FROM, BASE)
                    .keyGroups(byDay(KeyGroupByKeysTest::dayOf).assumeInRange()).build(), "bound both");
            assertMq1708(() -> fromOnly.keyGroups(byDay(KeyGroupByKeysTest::dayOf).assumeInRange()).build(),
                    "nothing reads");
        }));
        assertThat(sql).isEmpty();
    }

    // ---- AC-EXE-28

    record Keyed(String id) {}

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    /** The id as {@code "k<id>"} or {@code "x<id>"}; {@code toAttribute} reads the digits only. */
    private static final ColumnField<Keyed, CustomerEntity, String> KEYED_ID = ColumnField.of(Keyed.class, CUSTOMERS,
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
    private static final ColumnField<Keyed, CustomerEntity, LocalDateTime> KEYED_CREATED_AT =
            ColumnField.of(Keyed.class, CUSTOMERS, "createdAt", LocalDateTime.class);

    @TckTest
    void ac_exe_28_two_caller_keys_of_one_value_are_read_once_grouped_by_the_first_with_no_mq2213(TckDatabase db) {
        // A "k" key is grouped on its row's day, an "x" key ten days later; both convert to the id.
        var byPrefix = KeyGroups.by(String.class, (String key) -> key.startsWith("k")
                        ? dayOf(Long.parseLong(key.substring(1))) : tenDaysAfter(Long.parseLong(key.substring(1))))
                .bind(CREATED_FROM, Range::from).bind(CREATED_TO, Range::to).assumeInRange();
        ModelQuery<CustomerEntity, String, Keyed> keyed = ModelQuery.builder(CUSTOMERS,
                        row -> new Keyed(row.get(KEYED_ID)))
                .select(SelectSet.of(KEYED_ID))
                .primaryKey(PrimaryKey.of(KEYED_ID))
                .where(f -> f.compare(KEYED_CREATED_AT, Op.GTE, Expr.param(CREATED_FROM))
                        .compare(KEYED_CREATED_AT, Op.LT, Expr.param(CREATED_TO)))
                .keyGroups(byPrefix)
                .build();
        List<String> first = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            Map<String, Keyed> result = executor.byKeys(keyed, List.of("k5", "x5", "k5"));
            assertThat(List.copyOf(result.keySet())).containsExactly("k5", "x5");
            assertThat(result.get("x5")).isSameAs(result.get("k5")).isEqualTo(new Keyed("k5"));
        }));
        assertThat(first).extracting(KeyGroupByKeysTest::binds).containsExactly(3L);
        // Grouped by "x5", the first: its range holds no row 5.
        List<String> second = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor ->
                assertThat(executor.byKeys(keyed, List.of("x5", "k5"))).isEmpty()));
        assertThat(second).extracting(KeyGroupByKeysTest::binds).containsExactly(3L);
    }

    private static void assertMq1708(Runnable call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1708))
                .hasMessageContaining(message);
    }

    /** Runs {@code work} on a customers executor over {@code ds}, in one transaction, so a locked read may run. */
    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<CustomerEntity>> work) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(ds)) {
            sessions.inTransaction(em -> work.accept(ModelQueryExecutor.create(em, CustomerEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }

    /** The binds {@code sql} takes. */
    private static long binds(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }

    /**
     * Whether {@code statement} carries a locking clause: {@code FOR UPDATE}, {@code FOR NO KEY UPDATE} or
     * {@code FOR SHARE}, as {@code RowLockExecutionTest} reads it.
     */
    private static boolean locked(String statement) {
        String lower = statement.toLowerCase(Locale.ROOT);
        return lower.contains("for update") || lower.contains("for no key update") || lower.contains("for share");
    }
}
