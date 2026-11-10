package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.KeyGroups.GroupingRule;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** {@code KeyGroups}, its builder and {@code GroupingRule}: the terminal-call checks and group values (R-FCH-18). */
class KeyGroupsTest {

    static final class LineView {}

    static final QueryParam<Instant> FROM = QueryParam.of("createdFrom", Instant.class);
    static final QueryParam<Instant> TO = QueryParam.of("createdTo", Instant.class);

    record Range(Instant from, Instant to) {}

    static final Range ALL = new Range(Instant.parse("1000-01-01T00:00:00Z"), Instant.parse("9999-12-31T00:00:00Z"));

    /** A key's group: its thousands, as a range starting at that many seconds. */
    static Range rangeOf(long key) {
        Instant from = Instant.ofEpochSecond(key / 1000 * 1000);
        return new Range(from, from.plusSeconds(1000));
    }

    static KeyGroups.Builder<Long, Range> byRange() {
        return KeyGroups.by(Long.class, KeyGroupsTest::rangeOf).bind(FROM, Range::from).bind(TO, Range::to);
    }

    @Test
    void r_fch_18_min_keys_per_group_0_and_max_groups_0_throw_illegal_argument() {
        assertThatThrownBy(() -> GroupingRule.minKeysPerGroup(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minKeysPerGroup(0)");
        assertThatThrownBy(() -> GroupingRule.maxGroups(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxGroups(0)");
        assertThatThrownBy(() -> GroupingRule.maxGroups(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void r_fch_18_the_built_in_rules_compare_keys_per_group_in_long_arithmetic_and_count_groups() {
        GroupingRule min = GroupingRule.minKeysPerGroup(300);
        assertThat(min.group(600, 2)).isTrue();
        assertThat(min.group(599, 2)).isFalse();
        // n * groups overflows an int: in int arithmetic it would wrap negative and group.
        assertThat(GroupingRule.minKeysPerGroup(Integer.MAX_VALUE).group(Integer.MAX_VALUE, 2)).isFalse();
        GroupingRule max = GroupingRule.maxGroups(3);
        assertThat(max.group(1, 3)).isTrue();
        assertThat(max.group(10_000, 4)).isFalse();
    }

    @Test
    void r_fch_18_a_null_catch_all_value_is_mq1502_at_the_terminal_call() {
        var groups = KeyGroups.by(Long.class, KeyGroupsTest::rangeOf).bind(FROM, Range::from).bind(TO, r -> null);
        assertMq(MqCode.MQ1502, () -> groups.verifyMissing(ALL), "createdTo: Instant", "non-null value");
        assertMq(MqCode.MQ1502, () -> groups.groupWhen(GroupingRule.maxGroups(3), ALL).assumeInRange(),
                "createdTo: Instant", "catch-all");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void r_fch_18_a_mistyped_catch_all_value_is_mq1502_at_the_terminal_call() {
        // A raw bind is how a value of another type gets past the compiler.
        KeyGroups.Builder<Long, Range> groups = KeyGroups.by(Long.class, KeyGroupsTest::rangeOf)
                .bind((QueryParam) FROM, (Function<Range, Object>) r -> "2026-01-01");
        assertMq(MqCode.MQ1502, () -> groups.verifyMissing(ALL), "is a String, which is not a Instant");
        QueryParam<Optional> wrapped = QueryParam.of("wrapped", Optional.class);
        assertMq(MqCode.MQ1502, () -> KeyGroups.by(Long.class, KeyGroupsTest::rangeOf)
                .bind(wrapped, r -> Optional.of(r.from())).verifyMissing(ALL), "an expression cannot bind");
    }

    @Test
    void r_fch_18_verify_missing_unequal_to_group_when_catch_all_is_mq1708() {
        var other = new Range(ALL.from(), Instant.parse("9000-01-01T00:00:00Z"));
        var builder = byRange().groupWhen(GroupingRule.minKeysPerGroup(300), ALL);
        assertMq(MqCode.MQ1708, () -> builder.verifyMissing(other), "unequal to groupWhen's");
        // An equal catch-all, not the same instance, is accepted.
        KeyGroups<Long, Range> groups = builder.verifyMissing(new Range(ALL.from(), ALL.to()));
        assertThat(groups.verifiesMissing()).isTrue();
        assertThat(groups.catchAll()).hasValueSatisfying(
                values -> assertThat(values).containsExactly(entry(FROM, ALL.from()), entry(TO, ALL.to())));
    }

    @Test
    void r_fch_18_the_terminal_call_evaluates_each_catch_all_value_once_and_a_value_exception_propagates_unwrapped() {
        var calls = new AtomicInteger();
        KeyGroups<Long, Range> groups = KeyGroups.by(Long.class, KeyGroupsTest::rangeOf)
                .bind(FROM, r -> {
                    calls.incrementAndGet();
                    return r.from();
                })
                .verifyMissing(ALL);
        assertThat(calls).hasValue(1);
        groups.catchAll();
        groups.catchAll();
        assertThat(calls).hasValue(1);

        var boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> KeyGroups.by(Long.class, KeyGroupsTest::rangeOf).bind(FROM, r -> {
            throw boom;
        }).verifyMissing(ALL)).isSameAs(boom);
    }

    @Test
    void r_fch_18_assume_in_range_without_group_when_has_no_catch_all_and_calls_no_value() {
        var calls = new AtomicInteger();
        KeyGroups<Long, Range> groups = KeyGroups.by(Long.class, KeyGroupsTest::rangeOf)
                .bind(FROM, r -> {
                    calls.incrementAndGet();
                    return r.from();
                })
                .assumeInRange();
        assertThat(calls).hasValue(0);
        assertThat(groups.catchAll()).isEmpty();
        assertThat(groups.verifiesMissing()).isFalse();
        assertThat(groups.rule()).isEmpty();
        // groupWhen gives assumeInRange() a catch-all too.
        GroupingRule rule = GroupingRule.maxGroups(2);
        KeyGroups<Long, Range> ruled = byRange().groupWhen(rule, ALL).assumeInRange();
        assertThat(ruled.rule()).containsSame(rule);
        assertThat(ruled.catchAll()).hasValueSatisfying(values -> assertThat(values).containsKeys(FROM, TO));
    }

    @Test
    void r_fch_18_group_of_and_values_compute_a_key_group_and_its_bound_values_in_bind_order() {
        KeyGroups<Long, Range> groups = byRange().verifyMissing(ALL);
        Object group = groups.groupOf(4_321L, "Order.lines");
        assertThat(group).isEqualTo(rangeOf(4_321L));
        assertThat(groups.values(group, "Order.lines")).containsExactly(
                entry(FROM, Instant.ofEpochSecond(4_000)), entry(TO, Instant.ofEpochSecond(5_000)));
        assertThat(groups.params()).containsExactly(FROM, TO);
        assertThat(groups.keyType()).isEqualTo(Long.class);
    }

    @Test
    void ac_fch_22_a_null_group_is_mq2607_naming_the_key_and_a_group_exception_propagates_unwrapped() {
        KeyGroups<Long, Range> groups = KeyGroups.by(Long.class, (Long key) -> key < 0 ? null : rangeOf(key))
                .bind(FROM, Range::from).assumeInRange();
        assertThatThrownBy(() -> groups.groupOf(-7L, "Order.lines"))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2607))
                .hasMessageContaining("Order.lines").hasMessageContaining("-7");
        var boom = new ArithmeticException("boom");
        KeyGroups<Long, Range> throwing = KeyGroups.<Long, Range>by(Long.class, key -> {
            throw boom;
        }).assumeInRange();
        assertThatThrownBy(() -> throwing.groupOf(1L, "Order.lines")).isSameAs(boom);
    }

    @Test
    void ac_fch_22_a_null_or_mistyped_group_value_is_mq1502_naming_the_owner_and_the_param() {
        KeyGroups<Long, Range> groups = KeyGroups.by(Long.class, KeyGroupsTest::rangeOf)
                .bind(FROM, r -> r.from().getEpochSecond() == 0 ? null : r.from()).assumeInRange();
        assertMq(MqCode.MQ1502, () -> groups.values(rangeOf(1L), "Order.lines, the group of key 1"),
                "Order.lines, the group of key 1", "createdFrom: Instant");
    }

    @Test
    void r_fch_18_a_second_bind_or_group_when_replaces_the_first_and_each_call_returns_a_copy() {
        var start = KeyGroups.by(Long.class, KeyGroupsTest::rangeOf).bind(FROM, Range::from);
        var replaced = start.bind(FROM, Range::to);
        assertThat(start.assumeInRange().values(rangeOf(1L), "x")).containsExactly(entry(FROM, Instant.EPOCH));
        assertThat(replaced.assumeInRange().values(rangeOf(1L), "x"))
                .containsExactly(entry(FROM, Instant.ofEpochSecond(1000)));

        GroupingRule first = GroupingRule.maxGroups(1);
        GroupingRule second = GroupingRule.maxGroups(2);
        var other = new Range(Instant.EPOCH, Instant.EPOCH);
        KeyGroups<Long, Range> groups = start.groupWhen(first, other).groupWhen(second, ALL).verifyMissing(ALL);
        assertThat(groups.rule()).containsSame(second);
        assertThat(start.assumeInRange().rule()).isEmpty();
    }

    @Test
    void r_fch_18_a_primitive_key_type_is_its_wrapper_and_messages_show_no_value() {
        KeyGroups<Long, Range> groups = KeyGroups.by(long.class, KeyGroupsTest::rangeOf).bind(FROM, Range::from)
                .groupWhen(GroupingRule.maxGroups(2), ALL).verifyMissing(ALL);
        assertThat(groups.keyType()).isEqualTo(Long.class);
        assertThat(groups.groupOf(5L, "x")).isEqualTo(rangeOf(5L));
        assertThat(groups).hasToString("KeyGroups.by(Long).bind[createdFrom: Instant].groupWhen.verifyMissing");
        assertThat(KeyGroups.by(Long.class, KeyGroupsTest::rangeOf).assumeInRange())
                .hasToString("KeyGroups.by(Long).assumeInRange");
    }

    @Test
    void r_fch_18_child_query_key_groups_is_kept_by_every_copy_and_replaced_by_a_second_call() {
        KeyGroups<Long, Range> first = byRange().assumeInRange();
        KeyGroups<Long, Range> second = byRange().verifyMissing(ALL);
        ChildQuery<LineView> empty = ChildQuery.empty();
        ChildQuery<LineView> grouped = empty.keyGroups(first);
        assertThat(empty.groups()).isNull();
        assertThat(grouped.groups()).isSameAs(first);
        assertThat(grouped.maxPerParent(2).where(f -> f).orderBy().groups()).isSameAs(first);
        assertThat(grouped.keyGroups(second).groups()).isSameAs(second);
        assertThatThrownBy(() -> empty.keyGroups(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("keyGroups");
    }

    private static Map.Entry<QueryParam<?>, Object> entry(QueryParam<?> param, Object value) {
        return Map.entry(param, value);
    }

    private static void assertMq(MqCode code, ThrowingCallable call, String... message) {
        var thrown = assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
        for (String part : message) {
            thrown.hasMessageContaining(part);
        }
    }
}
