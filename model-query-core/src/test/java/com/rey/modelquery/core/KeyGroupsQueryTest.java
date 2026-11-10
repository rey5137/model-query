package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.KeyGroups.GroupingRule;
import com.rey.modelquery.core.SortSpec.Key;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * The {@code build()} checks of {@code ModelQuery.Builder#keyGroups}, its copies and its definition key (R-EXE-18,
 * R-COL-40, D-201, D-207, AC-EXE-27, AC-EXE-29).
 */
class KeyGroupsQueryTest {

    static final class LineEntity {}

    static final class LineView {}

    private static final TableField<LineEntity, LineEntity> ROOT = TableField.root(LineEntity.class);
    private static final OrderedColumnField<LineView, LineEntity, Long> ID =
            ColumnField.of(LineView.class, ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<LineView, LineEntity, Long> ORDER_ID =
            ColumnField.of(LineView.class, ROOT, "orderId", Long.class).named("orderId");
    private static final OrderedColumnField<LineView, LineEntity, Instant> CREATED =
            ColumnField.of(LineView.class, ROOT, "createdAt", Instant.class).named("createdAt");

    static final QueryParam<Instant> FROM = QueryParam.of("createdFrom", Instant.class);
    static final QueryParam<Instant> TO = QueryParam.of("createdTo", Instant.class);
    static final Instant ALL_FROM = Instant.parse("1000-01-01T00:00:00Z");

    /** A key's group: the start of its thousand seconds. */
    static Instant from(long key) {
        return Instant.ofEpochSecond(key / 1000 * 1000);
    }

    static final KeyGroups<Long, Instant> RANGE = KeyGroups.by(Long.class, KeyGroupsQueryTest::from)
            .bind(FROM, from -> from).bind(TO, from -> from.plusSeconds(1000)).verifyMissing(ALL_FROM);
    static final KeyGroups<Long, Instant> RULED = KeyGroups.by(Long.class, KeyGroupsQueryTest::from)
            .bind(FROM, from -> from).groupWhen(GroupingRule.minKeysPerGroup(300), ALL_FROM).assumeInRange();

    private static ModelQuery.Builder<LineEntity, Long, LineView> lines() {
        return ModelQuery.builder(ROOT, row -> new LineView()).select(SelectSet.of(ID, CREATED))
                .primaryKey(PrimaryKey.of(ID));
    }

    private static ModelQuery.Builder<LineEntity, Long, LineView> ranged() {
        return lines().where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM))
                .compare(CREATED, Op.LT, Expr.param(TO)));
    }

    @Test
    void ac_exe_27_a_group_param_counts_as_bound_at_build() {
        ModelQuery<LineEntity, Long, LineView> query = ranged().keyGroups(RANGE).build();
        assertThat(query.keyGroups()).containsSame(RANGE);
        // Without the groups, the params are read and bound by nothing (R-COL-40).
        assertCode(() -> ranged().build(), MqCode.MQ1512).hasMessageContaining("createdFrom");
        assertThat(ranged().bind(FROM, ALL_FROM).bind(TO, ALL_FROM).build().keyGroups()).isEmpty();
    }

    @Test
    void ac_exe_27_a_key_type_unequal_to_the_primary_key_type_is_mq1708_naming_both() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        KeyGroups<Long, ?> strings = (KeyGroups) KeyGroups.by(String.class, key -> from(Long.parseLong(key)))
                .bind(FROM, from -> from).bind(TO, from -> from).assumeInRange();
        assertCode(() -> ranged().keyGroups(strings).build(), MqCode.MQ1708).hasMessageContaining("LineView")
                .hasMessageContaining("String").hasMessageContaining("Long");
        // primaryKey(...) after keyGroups(...) keeps them, and build() checks them against the new key.
        assertCode(() -> ranged().keyGroups(RANGE).primaryKey(PrimaryKey.composite(ID, ORDER_ID)).build(),
                MqCode.MQ1708).hasMessageContaining("Long").hasMessageContaining("List");
        @SuppressWarnings({"unchecked", "rawtypes"})
        KeyGroups<List<Object>, ?> composite = (KeyGroups) KeyGroups.by(List.class, key -> from((Long) key.get(0)))
                .bind(FROM, from -> from).bind(TO, from -> from).assumeInRange();
        assertThatCode(() -> ranged().primaryKey(PrimaryKey.composite(ID, ORDER_ID)).keyGroups(composite).build())
                .doesNotThrowAnyException();
    }

    @Test
    void ac_exe_27_key_groups_on_a_query_with_no_primary_key_are_mq1708() {
        ModelQuery.Builder<LineEntity, Object, LineView> keyless = ModelQuery.builder(ROOT, row -> new LineView())
                .select(SelectSet.of(ID, CREATED)).where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)));
        @SuppressWarnings({"unchecked", "rawtypes"})
        KeyGroups<Object, ?> groups = (KeyGroups) RULED;
        assertCode(() -> keyless.keyGroups(groups).build(), MqCode.MQ1708)
                .hasMessageContaining("primaryKey(...) was not set");
    }

    @Test
    void ac_exe_27_a_param_bound_by_both_bind_and_the_groups_is_mq1708() {
        assertCode(() -> ranged().bind(FROM, ALL_FROM).keyGroups(RANGE).build(), MqCode.MQ1708)
                .hasMessageContaining("createdFrom: Instant").hasMessageContaining("bound both");
        // In either order.
        assertCode(() -> ranged().keyGroups(RANGE).bind(TO, ALL_FROM).build(), MqCode.MQ1708)
                .hasMessageContaining("createdTo: Instant");
    }

    @Test
    void ac_exe_27_a_group_param_nothing_reads_is_mq1708() {
        assertCode(() -> lines().where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM))).keyGroups(RANGE).build(),
                MqCode.MQ1708).hasMessageContaining("createdTo: Instant").hasMessageContaining("nothing reads");
    }

    @Test
    void ac_exe_27_verify_missing_with_no_bind_is_mq1708_and_a_param_no_group_binds_stays_mq1512() {
        var unbound = KeyGroups.by(Long.class, KeyGroupsQueryTest::from).verifyMissing(ALL_FROM);
        assertCode(() -> lines().keyGroups(unbound).build(), MqCode.MQ1708).hasMessageContaining("bind nothing");
        assertThatCode(() -> lines().keyGroups(KeyGroups.by(Long.class, KeyGroupsQueryTest::from).assumeInRange())
                .build()).doesNotThrowAnyException();
        var fromOnly = KeyGroups.by(Long.class, KeyGroupsQueryTest::from).bind(FROM, from -> from).assumeInRange();
        assertCode(() -> ranged().keyGroups(fromOnly).build(), MqCode.MQ1512).hasMessageContaining("createdTo");
    }

    @Test
    void ac_exe_29_under_group_when_a_group_param_read_other_than_as_a_range_bound_is_mq1708() {
        List<UnaryOperator<Filters<LineView>>> refused = List.of(
                f -> f.compare(CREATED, Op.EQ, Expr.param(FROM)),
                f -> f.compare(CREATED, Op.NE, Expr.param(FROM)),
                f -> f.not(n -> n.compare(CREATED, Op.GTE, Expr.param(FROM))),
                f -> f.compare(CREATED, Op.GTE, Expr.coalesce(Expr.param(FROM), CREATED)));
        for (UnaryOperator<Filters<LineView>> where : refused) {
            assertCode(() -> lines().where(where).keyGroups(RULED).build(), MqCode.MQ1708)
                    .hasMessageContaining("createdFrom: Instant").hasMessageContaining("groupWhen");
        }
        // Any range bound of a column, on either side, upper bounds included: a query's key is unique per row.
        List<UnaryOperator<Filters<LineView>>> allowed = List.of(
                f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)),
                f -> f.compare(CREATED, Op.LT, Expr.param(FROM)),
                f -> f.compare(Expr.param(FROM), Op.LTE, CREATED));
        for (UnaryOperator<Filters<LineView>> where : allowed) {
            assertThatCode(() -> lines().where(where).keyGroups(RULED).build()).doesNotThrowAnyException();
        }
        // Without groupWhen, an EQ read is fine.
        var exact = KeyGroups.by(Long.class, KeyGroupsQueryTest::from).bind(FROM, from -> from).assumeInRange();
        assertThatCode(() -> lines().where(f -> f.compare(CREATED, Op.EQ, Expr.param(FROM))).keyGroups(exact)
                .build()).doesNotThrowAnyException();
    }

    @Test
    void ac_exe_26_with_lock_with_fetch_and_ordered_by_copies_keep_the_key_groups() {
        ModelQuery<LineEntity, Long, LineView> query = ranged().keyGroups(RANGE).build();
        assertThat(query.withLock(RowLock.exclusive()).keyGroups()).containsSame(RANGE);
        assertThat(query.withFetch(FetchPlan.of(SelectSet.of(ID))).keyGroups()).containsSame(RANGE);
        assertThat(query.orderedBy(SortSpec.of(Key.desc("createdAt"))).keyGroups()).containsSame(RANGE);
        assertThat(query.withLock(RowLock.shared()).withFetch(FetchPlan.of(SelectSet.of(ID)))
                .orderedBy(SortSpec.of(Key.asc("id"))).keyGroups()).containsSame(RANGE);
    }

    @Test
    void the_definition_key_covers_the_key_groups_and_their_rule_by_identity() {
        ModelQuery.Builder<LineEntity, Long, LineView> grouped = lines()
                .where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM))).keyGroups(RULED);
        // bind alone keeps the key (D-201).
        var bound = grouped.bind(TO, ALL_FROM);
        assertThat(bound.build().definitionKey()).isSameAs(bound.bind(TO, ALL_FROM.plusSeconds(1)).build()
                .definitionKey());
        assertThat(grouped.build().definitionKey()).isNotSameAs(lines()
                .where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM))).bind(FROM, ALL_FROM).build()
                .definitionKey());
        // An equal declaration with another GroupingRule instance is another definition.
        var otherRule = KeyGroups.by(Long.class, KeyGroupsQueryTest::from).bind(FROM, from -> from)
                .groupWhen(GroupingRule.minKeysPerGroup(300), ALL_FROM).assumeInRange();
        assertThat(grouped.keyGroups(otherRule).build().definitionKey())
                .isNotSameAs(grouped.build().definitionKey());
        assertThat(grouped.build().definitionKey()).isSameAs(grouped.build().definitionKey());
    }

    @Test
    void a_child_query_builder_drops_its_own_key_groups() {
        // A child load groups its keys by ChildQuery#keyGroups; its model's own groups would bind nothing per round.
        ChildField<LineView, LineView> child = new ChildField<>() {
            @Override
            public String name() {
                return "siblings";
            }

            @Override
            public ColumnField<LineView, ?, ?> key() {
                return ID;
            }

            @Override
            public Optional<ColumnField<LineView, ?, ?>> foreignKey() {
                return Optional.of(ORDER_ID);
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return Optional.empty();
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, LineView> query() {
                return ranged().keyGroups(RANGE);
            }

            @Override
            public LineView with(LineView parent, List<LineView> children) {
                return parent;
            }
        };
        assertThatCode(() -> FetchPlan.of(SelectSet.of(ID)).child(child, FetchPlan.of(SelectSet.of(ID, CREATED))))
                .doesNotThrowAnyException();
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertCode(ThrowingCallable call, MqCode code) {
        return assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
