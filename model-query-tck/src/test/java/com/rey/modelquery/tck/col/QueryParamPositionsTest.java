package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.QueryParamStatementsTest.Recorder;
import com.rey.modelquery.tck.col.QueryParamStatementsTest.Stmt;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.criteria.JoinType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.hibernate.cfg.AvailableSettings;

/**
 * A request-bound value in each position an expression takes, on a real database: a bare select item, an arithmetic
 * and a concat operand, a CASE result, a comparison operand in a CASE condition and in the filter, {@code coalesce}
 * beside a column, a {@code Correlated} correlation, and a group and order key. Each returns the bound value, and
 * {@code hibernate.criteria.value_handling_mode=inline} keeps it a bound parameter with the same SQL and binds
 * (R-COL-40, D-198, spike C.1, AC-COL-59).
 */
class QueryParamPositionsTest {

    record Pos(Long id, String tag, Long shifted, String label, String picked, String early, Long referred,
            Long below) {}

    record Bucket(Long key, Long count) {}

    record Referred(Long key, Long referrer, Long count) {}

    /** A correlated sub-select's model. */
    static final class Inner {}

    /** The fixture's order statuses, by order id modulo 4 (TckFixture). */
    private static final String[] STATUSES = {"NEW", "PAID", "SHIPPED", "CANCELLED"};
    private static final long LAST = 8;

    static final QueryParam<String> TAG = QueryParam.of("tag", String.class);
    static final QueryParam<Long> SHIFT = QueryParam.of("shift", Long.class);
    static final QueryParam<Long> UP_TO = QueryParam.of("upTo", Long.class);
    static final QueryParam<Long> FALLBACK = QueryParam.of("fallback", Long.class);
    static final QueryParam<Long> FLOOR = QueryParam.of("floor", Long.class);
    static final QueryParam<Long> FROM = QueryParam.of("from", Long.class);

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final ColumnField<Pos, OrderEntity, Long> ID = ColumnField.of(Pos.class, ROOT, "id", Long.class);
    private static final ColumnField<Pos, OrderEntity, String> STATUS =
            ColumnField.of(Pos.class, ROOT, "status", String.class);
    private static final ColumnField<Pos, OrderEntity, Long> REFERRER =
            ColumnField.of(Pos.class, ROOT, "referrerId", Long.class);
    private static final ColumnField<Inner, OrderEntity, Long> INNER_ID =
            ColumnField.of(Inner.class, ROOT, "id", Long.class);

    /** A bare select item: no operand gives the type. */
    private static final ExpressionField<Pos, String> BARE = Expr.<Pos, String>param(TAG).named("tag");
    private static final ExpressionField<Pos, Long> SHIFTED = Expr.plus(ID, Expr.param(SHIFT)).named("shifted");
    private static final ExpressionField<Pos, String> LABEL = Expr.concat(STATUS, Expr.param(TAG)).named("label");
    /** A CASE result beside plain ones, which stay bound parameters under inline value handling too (R-FLT-08). */
    private static final ExpressionField<Pos, String> PICKED = Expr.cases(Pos.class, String.class)
            .when(f -> f.lte(ID, 3L), Expr.param(TAG)).when(f -> f.lte(ID, 5L), "mid").otherwise("none")
            .named("picked");
    /** A comparison operand in a CASE condition. */
    private static final ExpressionField<Pos, String> EARLY = Expr.cases(Pos.class, String.class)
            .when(f -> f.compare(ID, Op.LTE, Expr.param(UP_TO)), STATUS).otherwise("late").named("early");
    private static final ExpressionField<Pos, Long> REFERRED =
            Expr.coalesce(REFERRER, Expr.param(FALLBACK)).named("referred");
    /** The orders from the bound floor to the outer order, exclusive: the param is in the correlation. */
    private static final ExpressionField<Pos, Long> BELOW = Correlated.count(Pos.class, SubSelect.of(INNER_ID),
            (f, outer) -> f.compare(INNER_ID, Op.LT, outer.column(ID))
                    .compare(INNER_ID, Op.GTE, Expr.param(FLOOR))).named("below");

    private static final ModelQuery.Builder<OrderEntity, Long, Pos> POSITIONS = ModelQuery
            .builder(ROOT, row -> new Pos(row.get(ID), row.get(BARE), row.get(SHIFTED), row.get(LABEL),
                    row.get(PICKED), row.get(EARLY), row.get(REFERRED), row.get(BELOW)))
            .select(SelectSet.of(ID, BARE, SHIFTED, LABEL, PICKED, EARLY, REFERRED, BELOW))
            .primaryKey(PrimaryKey.of(ID))
            .where(f -> f.compare(ID, Op.GTE, Expr.param(FROM)).lte(ID, LAST)).orderBy(ID.asc());

    private static final ColumnField<Bucket, OrderEntity, Long> BUCKET_ID =
            ColumnField.of(Bucket.class, ROOT, "id", Long.class);
    /** A group and order key: one node, so PostgreSQL matches the group key to the select item. */
    private static final ExpressionField<Bucket, Long> KEY = Expr.<Bucket, Long>param(SHIFT).named("key");
    private static final AggregateField<Bucket, Long> COUNT = Agg.count(BUCKET_ID);
    private static final ModelQuery.Builder<OrderEntity, Object, Bucket> BUCKETS = ModelQuery
            .builder(ROOT, row -> new Bucket(row.get(KEY), row.get(COUNT)))
            .select(SelectSet.of(KEY, COUNT)).where(f -> f.lte(BUCKET_ID, LAST)).groupBy(KEY).orderBy(KEY.asc());

    private static final ColumnField<Referred, OrderEntity, Long> RF_ID =
            ColumnField.of(Referred.class, ROOT, "id", Long.class);
    private static final ColumnField<Referred, OrderEntity, Long> RF_ANCHOR =
            ColumnField.of(Referred.class, ROOT, "referrerId", Long.class);
    /** An INNER entity join anchored on a root column: the grouped count runs through the provider copy (D-197). */
    private static final EntityJoin<Referred, CustomerEntity> REFERRER_JOIN =
            EntityJoin.of("ref", CustomerEntity.class, JoinKind.INNER, List.of(EntityJoin.on(RF_ANCHOR, "id")));
    private static final ColumnField<Referred, CustomerEntity, Long> RF_REFERRER =
            ColumnField.of(Referred.class, REFERRER_JOIN.root(), "id", Long.class);
    private static final ExpressionField<Referred, Long> RF_KEY = Expr.<Referred, Long>param(SHIFT).named("key");
    private static final AggregateField<Referred, Long> RF_COUNT = Agg.count(RF_ID);
    /** A param in the group key and another in the filter, over an anchored INNER entity join. */
    private static final ModelQuery.Builder<OrderEntity, Object, Referred> REFERRED_GROUPS = ModelQuery
            .builder(ROOT, row -> new Referred(row.get(RF_KEY), row.get(RF_REFERRER), row.get(RF_COUNT)))
            .joinEntity(REFERRER_JOIN).select(SelectSet.of(RF_KEY, RF_REFERRER, RF_COUNT))
            .where(f -> f.compare(RF_ID, Op.GTE, Expr.param(FROM)).lte(RF_ID, 60L)).groupBy(RF_KEY, RF_REFERRER)
            .orderBy(RF_REFERRER.asc());

    /** One call's bound values. */
    record Values(String tag, long shift, long upTo, long fallback, long floor, long from) {}

    private static final List<Values> CALLS =
            List.of(new Values("#a", 100, 3, -1, 2, 1), new Values("#b", 200, 5, -2, 4, 2));

    @TckTest
    void ac_col_59_a_param_in_each_position_returns_the_bound_value(TckDatabase db) {
        try (var recorder = new Recorder(db)) {
            List<String> texts = new ArrayList<>();
            for (Values v : CALLS) {
                recorder.serving(OrderEntity.class, executor -> {
                    List<Stmt> ran = recorder.run(() -> assertThat(executor.list(positions(v), Limit.unlimited()))
                            .containsExactlyElementsOf(expected(v)));
                    assertThat(ran).hasSize(1);
                    texts.add(ran.get(0).sql());
                });
            }
            // One text across values: a param is a bind parameter, never a per-call constant.
            assertThat(texts.get(0)).isEqualTo(texts.get(1));
        }
    }

    @TckTest
    void ac_col_59_a_param_as_group_and_order_key_renders_one_node(TckDatabase db) {
        try (var recorder = new Recorder(db)) {
            for (Values v : CALLS) {
                recorder.serving(OrderEntity.class, executor -> {
                    List<Stmt> ran = recorder.run(() -> assertThat(executor.list(buckets(v), Limit.unlimited()))
                            .containsExactly(new Bucket(v.shift(), LAST)));
                    assertThat(ran).singleElement().satisfies(s -> {
                        // The select item, the group key and the order key are one parameter, bound once.
                        assertThat(s.times(v.shift())).as(s.toString()).isEqualTo(1);
                    });
                });
            }
        }
    }

    @TckTest
    void ac_col_59_a_grouped_count_through_an_anchored_entity_join_applies_each_binding(TckDatabase db) {
        try (var recorder = new Recorder(db)) {
            for (Values v : CALLS) {
                var q = REFERRED_GROUPS.bind(SHIFT, v.shift()).bind(FROM, v.from() * 10).build();
                long groups = groupsFrom(db, v.from() * 10);
                assertThat(groups).isGreaterThan(1);
                recorder.serving(OrderEntity.class, executor -> {
                    assertThat(executor.count(q)).isEqualTo(groups);
                    var page = executor.page(q, PageSpec.of(0, 2), CountMode.COUNT);
                    assertThat(page.total()).hasValue(groups);
                    assertThat(page.content()).hasSize(2).allSatisfy(r -> assertThat(r.key()).isEqualTo(v.shift()));
                });
            }
        }
        // The two bindings count different totals, so a value kept from the first call would show.
        assertThat(groupsFrom(db, CALLS.get(0).from() * 10)).isNotEqualTo(groupsFrom(db, CALLS.get(1).from() * 10));
    }

    record Kid(Long id, String status) {}

    record Holder(Long id, Long customer, List<Kid> kids) {}

    private static final ColumnField<Kid, OrderEntity, Long> KID_ID = ColumnField.of(Kid.class, ROOT, "id", Long.class);
    private static final ColumnField<Kid, OrderEntity, String> KID_STATUS =
            ColumnField.of(Kid.class, ROOT, "status", String.class);
    private static final ColumnField<Kid, ?, Long> KID_CUSTOMER = ColumnField.of(Kid.class,
            TableField.join(ROOT, "customer", JoinType.INNER), "id", Long.class);
    private static final ColumnField<Holder, OrderEntity, Long> HOLDER_ID =
            ColumnField.of(Holder.class, ROOT, "id", Long.class);
    private static final ColumnField<Holder, ?, Long> HOLDER_CUSTOMER = ColumnField.of(Holder.class,
            TableField.join(ROOT, "customer", JoinType.INNER), "id", Long.class);

    /** Orders of customer 990's through-load reach ids 990, 1990, 2990, 3990, 4990. */
    private static ModelQuery.Builder<OrderEntity, Long, Kid> kids(QueryParam<Long> from) {
        return ModelQuery.builder(ROOT, row -> new Kid(row.get(KID_ID), row.get(KID_STATUS)))
                .select(SelectSet.of(KID_ID, KID_STATUS, KID_CUSTOMER)).primaryKey(PrimaryKey.of(KID_ID))
                .where(f -> f.compare(KID_ID, Op.GTE, Expr.param(from))).orderBy(KID_ID.asc());
    }

    private static ChildField<Holder, Kid> kidsOf(ModelQuery.Builder<?, ?, Kid> query, boolean through) {
        return new ChildField<>() {
            @Override
            public String name() {
                return "kids";
            }

            @Override
            public ColumnField<Holder, ?, ?> key() {
                return through ? HOLDER_ID : HOLDER_CUSTOMER;
            }

            @Override
            public Optional<ColumnField<Kid, ?, ?>> foreignKey() {
                return through ? Optional.empty() : Optional.of(KID_CUSTOMER);
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return through ? Optional.of(TableField.join(TableField.join(ROOT, "customer", JoinType.INNER),
                        "orders", JoinType.INNER)) : Optional.empty();
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, Kid> query() {
                return query;
            }

            @Override
            public Holder with(Holder parent, List<Kid> rows) {
                return new Holder(parent.id(), parent.customer(), rows);
            }
        };
    }

    private static ModelQuery<OrderEntity, Long, Holder> holders(ModelQuery.Builder<?, ?, Kid> kids,
            boolean through, boolean readsParam) {
        var plan = FetchPlan.of(SelectSet.of(HOLDER_ID, HOLDER_CUSTOMER)).child(kidsOf(kids, through),
                FetchPlan.of(readsParam ? SelectSet.of(KID_ID, KID_STATUS, KID_TAG)
                        : SelectSet.of(KID_ID, KID_STATUS)),
                c -> c.where(f -> f.compare(KID_ID, Op.GTE, Expr.param(FROM))));
        return ModelQuery.builder(ROOT, row -> new Holder(row.get(HOLDER_ID), row.get(HOLDER_CUSTOMER), List.of()))
                .select(SelectSet.of(HOLDER_ID, HOLDER_CUSTOMER)).primaryKey(PrimaryKey.of(HOLDER_ID))
                .where(f -> f.eq(HOLDER_ID, 990L)).fetch(plan).build();
    }

    private static final ExpressionField<Kid, String> KID_TAG = Expr.<Kid, String>param(TAG).named("tag");

    /**
     * A child load reads a param its own builder binds: the child's static definition carries the value, so two
     * bindings of one shared builder give two child loads, each with its own rows. A child that reads a param its
     * builder leaves unbound is {@code MQ1512} when the plan is built, before any statement (R-COL-40, R-FCH-04).
     */
    @TckTest
    void ac_col_59_a_child_filter_reads_the_param_its_builder_binds_through_a_foreign_key_and_a_through_path(
            TckDatabase db) {
        var shared = kids(FROM);
        for (boolean through : new boolean[] {false, true}) {
            try (var recorder = new Recorder(db)) {
                recorder.serving(OrderEntity.class, executor -> {
                    for (long from : new long[] {1000L, 3000L, 1L}) {
                        var q = holders(shared.bind(FROM, from), through, false);
                        var expected = kidsFrom(db, from);
                        assertThat(expected).isNotEmpty();
                        assertThat(executor.list(q, Limit.unlimited())).singleElement()
                                .satisfies(h -> assertThat(h.kids()).extracting(Kid::id)
                                        .containsExactlyElementsOf(expected));
                    }
                });
            }
        }
        assertThat(kidsFrom(db, 1000L)).isNotEqualTo(kidsFrom(db, 3000L));
        for (boolean through : new boolean[] {false, true}) {
            for (boolean readsParam : new boolean[] {false, true}) {
                assertThatThrownBy(() -> holders(readsParam ? kids(FROM).bind(FROM, 1L) : kids(FROM), through,
                        readsParam))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1512))
                        .hasMessageContaining("reads the param").hasMessageContaining("does not bind");
            }
        }
    }

    /** The ids of the orders of order 990's customer from {@code from}, native SQL. */
    private static List<Long> kidsFrom(TckDatabase db, long from) {
        String sql = "SELECT id FROM orders WHERE customer_id = (SELECT customer_id FROM orders WHERE id = 990)"
                + " AND id >= " + from + " ORDER BY id";
        List<Long> ids = new ArrayList<>();
        try (var connection = db.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    /** The groups the oracle counts: distinct referrers of the orders from {@code from}, native SQL. */
    private static long groupsFrom(TckDatabase db, long from) {
        String sql = "SELECT COUNT(DISTINCT o.referrer_id) FROM orders o JOIN customers c ON c.id = o.referrer_id"
                + " WHERE o.id >= " + from + " AND o.id <= 60";
        try (var connection = db.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @TckTest
    void ac_col_59_inline_value_handling_keeps_each_param_bound_with_the_same_sql_and_binds(TckDatabase db) {
        List<Stmt> standard = statements(db, Map.of());
        List<Stmt> inline = statements(db, Map.of(AvailableSettings.CRITERIA_VALUE_HANDLING_MODE, "inline"));
        assertThat(standard).hasSize(2 * CALLS.size());
        assertThat(inline).isEqualTo(standard);
    }

    /** The statements both queries run for every call, on a factory with the extra {@code settings}. */
    private static List<Stmt> statements(TckDatabase db, Map<String, Object> settings) {
        List<Stmt> ran = new ArrayList<>();
        try (var recorder = new Recorder(db, settings)) {
            for (Values v : CALLS) {
                recorder.serving(OrderEntity.class, executor -> {
                    ran.addAll(recorder.run(() -> assertThat(executor.list(positions(v), Limit.unlimited()))
                            .containsExactlyElementsOf(expected(v))));
                    ran.addAll(recorder.run(() -> assertThat(executor.list(buckets(v), Limit.unlimited()))
                            .containsExactly(new Bucket(v.shift(), LAST))));
                });
            }
        }
        return ran;
    }

    private static ModelQuery<OrderEntity, Long, Pos> positions(Values v) {
        return POSITIONS.bind(TAG, v.tag()).bind(SHIFT, v.shift()).bind(UP_TO, v.upTo())
                .bind(FALLBACK, v.fallback()).bind(FLOOR, v.floor()).bind(FROM, v.from()).build();
    }

    private static ModelQuery<OrderEntity, Object, Bucket> buckets(Values v) {
        return BUCKETS.bind(SHIFT, v.shift()).build();
    }

    /** The rows {@link #POSITIONS} reads under {@code v}, from the fixture's formulas (TckFixture). */
    private static List<Pos> expected(Values v) {
        return LongStream.rangeClosed(v.from(), LAST).mapToObj(i -> new Pos(i, v.tag(), i + v.shift(),
                status(i) + v.tag(), i <= 3 ? v.tag() : i <= 5 ? "mid" : "none", i <= v.upTo() ? status(i) : "late",
                i % 3 == 0 ? (i * 11) % 1_000 + 1 : v.fallback(), Math.max(0, i - v.floor()))).toList();
    }

    private static String status(long id) {
        return STATUSES[(int) (id % STATUSES.length)];
    }
}
