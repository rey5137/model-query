package com.rey.modelquery.tck.spr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.KeyGroups;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.fch.QStampedOrder;
import com.rey.modelquery.tck.fch.StampedOrder;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code findAllByKeys} and {@code findByKey} over a query with key groups, unlocked and locked, return what the
 * ungrouped query does, and every other read of it is the executor's {@code MQ2215} (spec integration/50 R-SPR-15,
 * engine/20 R-EXE-18, D-207, D-210; AC-SPR-19, AC-EXE-25, AC-EXE-26). Order {@code i} is placed {@code 17 i} minutes
 * after 2020-01-01, so its key encodes the day it was placed on.
 */
class KeyGroupRepositoryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final QueryParam<LocalDateTime> FROM = QueryParam.of("placedFrom", LocalDateTime.class);
    private static final QueryParam<LocalDateTime> TO = QueryParam.of("placedTo", LocalDateTime.class);
    private static final LocalDateTime ALL_FROM = LocalDateTime.of(1000, 1, 1, 0, 0);
    private static final LocalDateTime ALL_TO = LocalDateTime.of(9999, 12, 31, 0, 0);

    /** A half-open range of placing times. */
    record Range(LocalDateTime from, LocalDateTime to) {}

    private static Range dayOf(long order) {
        LocalDateTime day = BASE.plusMinutes(17 * order).toLocalDate().atStartOfDay();
        return new Range(day, day.plusDays(1));
    }

    private static ModelQuery.Builder<OrderEntity, Long, StampedOrder> ranged() {
        return QStampedOrder.query().select(QStampedOrder.ALL)
                .where(f -> f.compare(QStampedOrder.PLACED_AT, Op.GTE, Expr.param(FROM))
                        .compare(QStampedOrder.PLACED_AT, Op.LT, Expr.param(TO)));
    }

    private static ModelQuery<OrderEntity, Long, StampedOrder> ungrouped() {
        return ranged().bind(FROM, ALL_FROM).bind(TO, ALL_TO).build();
    }

    private static ModelQuery<OrderEntity, Long, StampedOrder> grouped() {
        return ranged().keyGroups(KeyGroups.by(Long.class, KeyGroupRepositoryTest::dayOf)
                .bind(FROM, Range::from).bind(TO, Range::to).assumeInRange()).build();
    }

    // Orders 7 and 80 are placed on day 0, 100 and 90 on day 1, and 300 on day 3; the keys alternate between groups.
    private static final List<Long> KEYS = List.of(100L, 7L, 300L, 90L, 7L, 80L, -1L);

    @TckTest
    void ac_spr_19_find_all_by_keys_and_find_by_key_over_key_groups_return_the_ungrouped_rows_in_key_order(
            TckDatabase db) {
        Map<Long, StampedOrder> expected = ModelQueryRepositoryTest.assertSameAsExecutor(db,
                executor -> executor.byKeys(ungrouped(), KEYS), repository -> repository.findAllByKeys(ungrouped(),
                        KEYS));
        assertThat(expected.keySet()).containsExactly(100L, 7L, 300L, 90L, 80L);

        Map<Long, StampedOrder> found = ModelQueryRepositoryTest.assertSameAsExecutor(db,
                executor -> executor.byKeys(grouped(), KEYS), repository -> repository.findAllByKeys(grouped(), KEYS));
        assertThat(found).containsExactlyEntriesOf(expected);
        // One round per day: days 1, 0 and 3, and the day before the start that key -1 falls on.
        assertThat(SqlSnapshots.capture(db, ds -> ModelQueryRepositoryTest.withRepository(ds,
                ModelQueryRepositoryTest.DefaultTransactions.class,
                (repository, context) -> repository.findAllByKeys(grouped(), KEYS)))).hasSize(4);

        Optional<StampedOrder> one = ModelQueryRepositoryTest.assertSameAsExecutor(db,
                executor -> executor.one(grouped(), 90L), repository -> repository.findByKey(grouped(), 90L));
        assertThat(one).contains(expected.get(90L));
        Optional<StampedOrder> none = ModelQueryRepositoryTest.assertSameAsExecutor(db,
                executor -> executor.one(grouped(), -1L), repository -> repository.findByKey(grouped(), -1L));
        assertThat(none).isEmpty();
    }

    @TckTest
    void ac_spr_20_locked_find_all_by_keys_and_find_by_key_over_key_groups_return_the_ungrouped_rows(
            TckDatabase db) {
        var lockedUngrouped = ungrouped().withLock(RowLock.exclusive());
        var lockedGrouped = grouped().withLock(RowLock.exclusive());
        Map<Long, StampedOrder> expected = ModelQueryRepositoryTest.assertLockedSameAsExecutor(db,
                executor -> executor.byKeys(lockedUngrouped, KEYS),
                repository -> repository.findAllByKeys(lockedUngrouped, KEYS));
        assertThat(expected.keySet()).containsExactly(100L, 7L, 300L, 90L, 80L);

        // The map is in the requested keys' first-occurrence order; the rounds' sorted order (D-210) is covered by
        // KeyGroupByKeysTest.
        Map<Long, StampedOrder> found = ModelQueryRepositoryTest.assertLockedSameAsExecutor(db,
                executor -> executor.byKeys(lockedGrouped, KEYS),
                repository -> repository.findAllByKeys(lockedGrouped, KEYS));
        assertThat(found).containsExactlyEntriesOf(expected);

        Optional<StampedOrder> one = ModelQueryRepositoryTest.assertLockedSameAsExecutor(db,
                executor -> executor.one(lockedGrouped, 90L), repository -> repository.findByKey(lockedGrouped, 90L));
        assertThat(one).contains(expected.get(90L));
    }

    @TckTest
    void ac_exe_26_find_one_find_first_and_find_all_of_a_grouped_query_are_mq2215_before_any_statement(
            TckDatabase db) {
        var grouped = grouped();
        List<String> sql = SqlSnapshots.capture(db, ds -> ModelQueryRepositoryTest.withRepository(ds,
                ModelQueryRepositoryTest.DefaultTransactions.class, (repository, context) -> {
                    assertMq2215(() -> repository.findOne(grouped), "one");
                    assertMq2215(() -> repository.findFirst(grouped), "first");
                    assertMq2215(() -> repository.findAll(grouped, Limit.of(5)), "list");
                    return null;
                }));
        assertThat(sql).isEmpty();
    }

    private static void assertMq2215(Runnable call, String read) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2215))
                .hasMessageContaining(MqCode.MQ2215.code() + ": ").hasMessageContaining(read)
                .hasMessageContaining("placedFrom");
    }
}
