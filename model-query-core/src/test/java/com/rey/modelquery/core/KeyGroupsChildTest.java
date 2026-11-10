package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.KeyGroups.GroupingRule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * The plan-build checks of {@code ChildQuery#keyGroups}: {@code MQ1708} and the narrowed {@code MQ1512}, all raised
 * when the plan names the child, so before any statement (R-FCH-18, D-138, AC-FCH-21).
 */
class KeyGroupsChildTest {

    static final class OrderEntity {}

    static final class LineEntity {}

    static final class OrderView {}

    static final class LineView {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final TableField<LineEntity, LineEntity> LINE_ROOT = TableField.root(LineEntity.class);
    private static final OrderedColumnField<OrderView, OrderEntity, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<LineView, LineEntity, Long> LINE_ID =
            ColumnField.of(LineView.class, LINE_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<LineView, LineEntity, Long> LINE_ORDER =
            ColumnField.of(LineView.class, LINE_ROOT, "orderId", Long.class).named("orderId");
    private static final OrderedColumnField<LineView, LineEntity, Instant> CREATED =
            ColumnField.of(LineView.class, LINE_ROOT, "createdAt", Instant.class).named("createdAt");
    private static final FetchPlan<LineView> LINE = FetchPlan.of(SelectSet.of(LINE_ID, CREATED));

    static final QueryParam<Instant> FROM = QueryParam.of("createdFrom", Instant.class);
    static final QueryParam<Instant> TO = QueryParam.of("createdTo", Instant.class);
    static final Instant ALL = Instant.parse("1000-01-01T00:00:00Z");

    /** A key's group: the start of its thousand seconds. */
    static Instant from(long key) {
        return Instant.ofEpochSecond(key / 1000 * 1000);
    }

    static final KeyGroups<Long, Instant> LOWER = KeyGroups.by(Long.class, KeyGroupsChildTest::from)
            .bind(FROM, from -> from).assumeInRange();
    static final KeyGroups<Long, Instant> RULED = KeyGroups.by(Long.class, KeyGroupsChildTest::from)
            .bind(FROM, from -> from).groupWhen(GroupingRule.minKeysPerGroup(300), ALL).assumeInRange();
    static final KeyGroups<Long, Instant> VERIFIED = KeyGroups.by(Long.class, KeyGroupsChildTest::from)
            .bind(FROM, from -> from).verifyMissing(ALL);

    /** The order's lines matched on their foreign key, to-many. */
    private static final ChildField<OrderView, LineView> LINES = child(LINE_ORDER, null, true, () -> lineQuery());
    /** The order's lines reached through its association as a List, to-many too (D-209). */
    private static final ChildField<OrderView, LineView> THROUGH = child(null,
            TableField.<OrderEntity, LineEntity>join(ROOT, "lines", INNER), true, () -> lineQuery());
    /** An order's line reached through its association as an Optional, at most one row per key. */
    private static final ChildField<OrderView, LineView> ONE = child(null,
            TableField.<OrderEntity, LineEntity>join(ROOT, "lines", INNER), false, () -> lineQuery());

    private static ModelQuery.Builder<LineEntity, Long, LineView> lineQuery() {
        return ModelQuery.builder(LINE_ROOT, row -> new LineView()).primaryKey(PrimaryKey.of(LINE_ID));
    }

    private static FetchPlan<OrderView> plan(ChildField<OrderView, LineView> field,
            UnaryOperator<ChildQuery<LineView>> query) {
        return FetchPlan.of(SelectSet.of(ID)).child(field, LINE, query);
    }

    @Test
    void ac_fch_21_a_key_type_unequal_to_the_matched_key_attribute_type_is_mq1708_naming_both() {
        var strings = KeyGroups.by(String.class, key -> from(Long.parseLong(key))).bind(FROM, from -> from)
                .assumeInRange();
        assertCode(() -> plan(LINES, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))
                .keyGroups(strings)), MqCode.MQ1708).hasMessageContaining("OrderView.lines")
                .hasMessageContaining("String").hasMessageContaining("Long");
        // A through child groups on the parent's key, here the order id.
        assertCode(() -> plan(THROUGH, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))
                .keyGroups(strings)), MqCode.MQ1708).hasMessageContaining("String");
    }

    @Test
    void ac_fch_21_a_group_param_nothing_reads_is_mq1708() {
        assertCode(() -> plan(LINES, q -> q.keyGroups(LOWER)), MqCode.MQ1708)
                .hasMessageContaining("createdFrom: Instant").hasMessageContaining("nothing reads");
    }

    @Test
    void ac_fch_21_verify_missing_with_no_bind_is_mq1708() {
        var unbound = KeyGroups.by(Long.class, KeyGroupsChildTest::from).verifyMissing(ALL);
        assertCode(() -> plan(ONE, q -> q.keyGroups(unbound)), MqCode.MQ1708)
                .hasMessageContaining("bind nothing");
        // With no bind, assumeInRange() only splits the rounds by group.
        var split = KeyGroups.by(Long.class, KeyGroupsChildTest::from).assumeInRange();
        assertThatCode(() -> plan(LINES, q -> q.keyGroups(split))).doesNotThrowAnyException();
    }

    @Test
    void ac_fch_21_verify_missing_on_a_to_many_child_is_mq1708() {
        assertCode(() -> plan(LINES, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))
                .keyGroups(VERIFIED)), MqCode.MQ1708).hasMessageContaining("foreignKey")
                .hasMessageContaining("assumeInRange()");
        // A List child loaded through an association path is to-many too (D-209).
        assertCode(() -> plan(THROUGH, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))
                .keyGroups(VERIFIED)), MqCode.MQ1708).hasMessageContaining("as a List through")
                .hasMessageContaining("assumeInRange()");
        assertThatCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))
                .keyGroups(VERIFIED))).doesNotThrowAnyException();
    }

    @Test
    void ac_fch_21_on_a_to_one_child_verify_missing_restricts_a_group_param_read_as_group_when_does() {
        List<UnaryOperator<Filters<LineView>>> refused = List.of(
                f -> f.compare(CREATED, Op.EQ, Expr.param(FROM)),
                f -> f.compare(CREATED, Op.NE, Expr.param(FROM)),
                f -> f.not(n -> n.compare(CREATED, Op.GTE, Expr.param(FROM))),
                f -> f.compare(CREATED, Op.GTE, Expr.coalesce(Expr.param(FROM), CREATED)),
                f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)).compare(CREATED, Op.LT, Expr.param(FROM)));
        for (UnaryOperator<Filters<LineView>> where : refused) {
            assertCode(() -> plan(ONE, q -> q.where(where).keyGroups(VERIFIED)), MqCode.MQ1708)
                    .hasMessageContaining("createdFrom: Instant").hasMessageContaining("catch-all");
        }
        assertThatCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.LT, Expr.param(FROM)))
                .keyGroups(VERIFIED))).doesNotThrowAnyException();
        // assumeInRange() without groupWhen has no catch-all, so any read stays.
        assertThatCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.EQ, Expr.param(FROM)))
                .keyGroups(LOWER))).doesNotThrowAnyException();
    }

    @Test
    void ac_fch_21_on_a_to_many_child_a_group_param_read_as_an_upper_bound_or_otherwise_is_mq1708() {
        List<UnaryOperator<Filters<LineView>>> refused = List.of(
                f -> f.compare(CREATED, Op.LT, Expr.param(FROM)),
                f -> f.compare(CREATED, Op.LTE, Expr.param(FROM)),
                f -> f.compare(Expr.param(FROM), Op.GT, CREATED),
                f -> f.compare(CREATED, Op.NE, Expr.param(FROM)),
                f -> f.not(n -> n.compare(CREATED, Op.GTE, Expr.param(FROM))), // a NOT turns it into an upper bound
                f -> f.compare(CREATED, Op.GTE, Expr.coalesce(Expr.param(FROM), CREATED)));
        for (UnaryOperator<Filters<LineView>> where : refused) {
            assertCode(() -> plan(LINES, q -> q.where(where).keyGroups(LOWER)), MqCode.MQ1708)
                    .hasMessageContaining("createdFrom: Instant").hasMessageContaining("lower bound");
            // A List child loaded through an association path takes a lower bound only too (D-209).
            assertCode(() -> plan(THROUGH, q -> q.where(where).keyGroups(LOWER)), MqCode.MQ1708)
                    .hasMessageContaining("createdFrom: Instant").hasMessageContaining("to-many child");
        }
        assertThatCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.LT, Expr.param(FROM)))
                .keyGroups(LOWER))).doesNotThrowAnyException();
        // A group param a @Computed-like selected expression reads is refused too.
        var shifted = Expr.<LineView, Instant>coalesce(Expr.param(FROM), CREATED).named("shifted");
        assertCode(() -> FetchPlan.of(SelectSet.of(ID)).child(LINES, FetchPlan.of(SelectSet.of(LINE_ID, shifted)),
                q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM))).keyGroups(LOWER)), MqCode.MQ1708)
                .hasMessageContaining("shifted");
    }

    @Test
    void ac_fch_21_on_a_to_many_child_a_lower_bound_either_way_round_or_eq_passes() {
        List<UnaryOperator<Filters<LineView>>> allowed = List.of(
                f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)),
                f -> f.compare(CREATED, Op.GT, Expr.param(FROM)),
                f -> f.compare(Expr.param(FROM), Op.LTE, CREATED),
                f -> f.compare(Expr.param(FROM), Op.LT, CREATED),
                f -> f.compare(CREATED, Op.EQ, Expr.param(FROM)),
                f -> f.or(a -> a.compare(CREATED, Op.GTE, Expr.param(FROM)), b -> b.eq(LINE_ID, 1L)));
        for (UnaryOperator<Filters<LineView>> where : allowed) {
            assertThatCode(() -> plan(LINES, q -> q.where(where).keyGroups(LOWER))).doesNotThrowAnyException();
        }
    }

    @Test
    void ac_fch_25_under_group_when_a_group_param_read_with_eq_is_mq1708_at_the_plan_build() {
        assertCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.EQ, Expr.param(FROM)))
                .keyGroups(RULED)), MqCode.MQ1708).hasMessageContaining("createdFrom: Instant")
                .hasMessageContaining("groupWhen");
        assertCode(() -> plan(LINES, q -> q.where(f -> f.compare(CREATED, Op.EQ, Expr.param(FROM)))
                .keyGroups(RULED)), MqCode.MQ1708).hasMessageContaining("groupWhen");
        assertCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.GTE,
                Expr.coalesce(Expr.param(FROM), CREATED))).keyGroups(RULED)), MqCode.MQ1708);
        // Either bound of a to-one child passes under groupWhen; a to-many child still takes a lower bound only.
        assertThatCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.LT, Expr.param(FROM)))
                .keyGroups(RULED))).doesNotThrowAnyException();
        assertCode(() -> plan(LINES, q -> q.where(f -> f.compare(CREATED, Op.LT, Expr.param(FROM)))
                .keyGroups(RULED)), MqCode.MQ1708);
        assertCode(() -> plan(THROUGH, q -> q.where(f -> f.compare(CREATED, Op.LT, Expr.param(FROM)))
                .keyGroups(RULED)), MqCode.MQ1708);
    }

    @Test
    void r_fch_18_a_to_one_through_child_without_group_when_reads_a_group_param_any_way() {
        assertThatCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.EQ, Expr.param(FROM)))
                .keyGroups(LOWER))).doesNotThrowAnyException();
        assertThatCode(() -> plan(ONE, q -> q.where(f -> f.compare(CREATED, Op.LT,
                Expr.coalesce(Expr.param(FROM), CREATED))).keyGroups(LOWER))).doesNotThrowAnyException();
    }

    @Test
    void ac_fch_21_a_param_no_group_binds_stays_mq1512_and_one_the_groups_bind_is_not() {
        assertCode(() -> plan(LINES, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM))
                .compare(CREATED, Op.LT, Expr.param(TO))).keyGroups(LOWER)), MqCode.MQ1512)
                .hasMessageContaining("createdTo: Instant");
        // Without key groups the group-bound param is MQ1512 too; with them it is bound per round.
        assertCode(() -> plan(LINES, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))),
                MqCode.MQ1512).hasMessageContaining("createdFrom: Instant");
        FetchPlan<OrderView> grouped = plan(LINES, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))
                .keyGroups(LOWER));
        assertThat(grouped.childLoads().get(0).keyGroups()).containsSame(LOWER);
        assertThat(plan(LINES, q -> q).childLoads().get(0).keyGroups()).isEmpty();
    }

    @Test
    void ac_fch_21_a_child_models_own_key_groups_dont_bind_its_child_load_and_the_mq1512_says_so() {
        ChildField<OrderView, LineView> ownGroups = child(LINE_ORDER, null, true,
                () -> lineQuery().keyGroups(LOWER));
        assertCode(() -> plan(ownGroups, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))),
                MqCode.MQ1512).hasMessageContaining("createdFrom: Instant").hasMessageContaining("ChildQuery#keyGroups");
        // The hint is for a child model that has key groups: without them the message stays as it was.
        assertCode(() -> plan(LINES, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))),
                MqCode.MQ1512).hasMessageNotContaining("ChildQuery#keyGroups");
        // A param the model's groups don't bind gets no hint, whoever else's groups there are.
        assertCode(() -> plan(ownGroups, q -> q.where(f -> f.compare(CREATED, Op.LT, Expr.param(TO)))),
                MqCode.MQ1512).hasMessageContaining("createdTo: Instant")
                .hasMessageNotContaining("ChildQuery#keyGroups");
        // ChildQuery#keyGroups does bind it.
        assertThatCode(() -> plan(ownGroups, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))
                .keyGroups(LOWER))).doesNotThrowAnyException();
    }

    @Test
    void ac_fch_21_a_param_bound_by_both_bind_and_the_key_groups_is_mq1708() {
        var bound = child(LINE_ORDER, null, true, () -> lineQuery().bind(FROM, ALL));
        assertCode(() -> plan(bound, q -> q.where(f -> f.compare(CREATED, Op.GTE, Expr.param(FROM)))
                .keyGroups(LOWER)), MqCode.MQ1708).hasMessageContaining("both");
    }

    private static ChildField<OrderView, LineView> child(ColumnField<LineView, ?, ?> foreignKey,
            TableField<?, ?> through, boolean toMany, Supplier<ModelQuery.Builder<?, ?, LineView>> query) {
        return new ChildField<>() {
            @Override
            public String name() {
                return "lines";
            }

            @Override
            public ColumnField<OrderView, ?, ?> key() {
                return ID;
            }

            @Override
            public Optional<ColumnField<LineView, ?, ?>> foreignKey() {
                return Optional.ofNullable(foreignKey);
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return Optional.ofNullable(through);
            }

            @Override
            public boolean isToMany() {
                return toMany;
            }

            @Override
            public ModelQuery.Builder<?, ?, LineView> query() {
                return query.get();
            }

            @Override
            public OrderView with(OrderView parent, List<LineView> children) {
                return parent;
            }
        };
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertCode(ThrowingCallable call, MqCode code) {
        return assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
