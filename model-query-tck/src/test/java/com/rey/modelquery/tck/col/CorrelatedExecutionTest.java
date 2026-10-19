package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;

/** Scalar sub-queries execute in every supported position, without joining their inner rows to the result. */
class CorrelatedExecutionTest {

    record Value(Long id, Object value) {}

    record Group(String status, Long count) {}

    static final class Inner {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final ColumnField<Value, OrderEntity, Long> ID =
            ColumnField.of(Value.class, ROOT, "id", Long.class);
    private static final ColumnField<Value, OrderEntity, String> STATUS =
            ColumnField.of(Value.class, ROOT, "status", String.class);
    private static final ColumnField<Inner, OrderEntity, Long> INNER_ID =
            ColumnField.of(Inner.class, ROOT, "id", Long.class);
    private static final ColumnField<Inner, OrderEntity, String> INNER_STATUS =
            ColumnField.of(Inner.class, ROOT, "status", String.class);
    private static final ColumnField<Inner, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(Inner.class, ROOT, "total", BigDecimal.class);
    private static final ColumnField<Inner, OrderEntity, Long> REFERRER =
            ColumnField.of(Inner.class, ROOT, "referrerId", Long.class);
    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<Value, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(Value.class, ITEMS, "id", Long.class);
    private static final ColumnField<Value, OrderItemEntity, String> ITEM_CODE =
            ColumnField.of(Value.class, ITEMS, "productCode", String.class);
    private static final ColumnField<Inner, OrderItemEntity, Long> OTHER_ID =
            ColumnField.of(Inner.class, ITEMS, "id", Long.class);
    private static final ColumnField<Inner, OrderItemEntity, String> OTHER_CODE =
            ColumnField.of(Inner.class, ITEMS, "productCode", String.class);
    private static final ExpressionField<Value, Long> SAME_CODE = Correlated.count(Value.class,
            SubSelect.of(OTHER_ID).where(f -> f.lte(OTHER_ID, 75L)),
            (f, outer) -> f.compare(OTHER_CODE, Op.EQ, outer.column(ITEM_CODE)));
    private static final ExpressionField<Value, Long> COUNT = count(INNER_ID);
    private static final ExpressionField<Value, Long> NULLABLE_COUNT = count(REFERRER);
    private static final ExpressionField<Value, Boolean> EXISTS = Correlated.exists(Value.class, rows(REFERRER),
            (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));
    private static final ExpressionField<Value, BigDecimal> MIN = Correlated.min(Value.class, rows(TOTAL),
            (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));
    private static final ExpressionField<Value, BigDecimal> MAX = Correlated.max(Value.class, rows(TOTAL),
            (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));
    private static final ExpressionField<Value, BigDecimal> SUM = Correlated.sum(Value.class, rows(TOTAL),
            (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));

    // AC-COL-36
    @TckTest
    void ac_col_36_count_counts_non_null_values_returns_zero_and_composes(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "col-36-correlated-count", ds -> withExecutor(ds, executor -> {
            assertValues(executor, COUNT, 2L, 2L, 1L, 0L, 2L, 2L, 1L, 0L);
            assertValues(executor, NULLABLE_COUNT, 0L, 1L, 1L, 0L, 0L, 1L, 1L, 0L);
            assertValues(executor, Expr.plus(COUNT, 10L), 12L, 12L, 11L, 10L, 12L, 12L, 11L, 10L);
            assertFiltered(executor, COUNT, 2L, 1L, 2L, 5L, 6L);
            assertThat(executor.list(selecting(COUNT).orderBy(COUNT.asc(), ID.asc()).build(), Limit.unlimited()))
                    .extracting(Value::id).containsExactly(4L, 8L, 3L, 7L, 1L, 2L, 5L, 6L);
        }));
    }

    // AC-COL-37
    @TckTest
    void ac_col_37_exists_is_true_for_null_columns_false_for_no_rows_and_never_null(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "col-37-correlated-exists", ds ->
                withExecutor(ds, executor -> {
                    assertValues(executor, EXISTS, true, true, true, false, true, true, true, false);
                    assertFiltered(executor, EXISTS, true, 1L, 2L, 3L, 5L, 6L, 7L);
                    assertFiltered(executor, EXISTS, false, 4L, 8L);
                    assertOrdered(executor, EXISTS, 4L, 8L, 1L, 2L, 3L, 5L, 6L, 7L);
                }));
        assertThat(sql).allSatisfy(statement -> assertThat(statement)
                .contains("case when exists(select 1").contains("then").contains("else"));
    }

    // AC-COL-38
    @TckTest
    void ac_col_38_min_and_max_preserve_extrema_and_return_null_without_values(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "col-38-correlated-extrema", ds -> withExecutor(ds, executor -> {
            assertValues(executor, MIN, money("13.17"), money("26.34"), money("39.51"), null,
                    money("13.17"), money("26.34"), money("39.51"), null);
            assertValues(executor, MAX, money("65.85"), money("79.02"), money("39.51"), null,
                    money("65.85"), money("79.02"), money("39.51"), null);
            assertFiltered(executor, MIN, money("13.17"), 1L, 5L);
            assertFiltered(executor, MAX, money("79.02"), 2L, 6L);
            assertNulls(executor, MIN, 4L, 8L);
            assertNulls(executor, MAX, 4L, 8L);
            assertOrdered(executor, MIN, 1L, 5L, 2L, 6L, 3L, 7L);
            assertOrdered(executor, MAX, 3L, 7L, 1L, 5L, 2L, 6L);
            var nullableMin = Correlated.min(Value.class, rows(REFERRER),
                    (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));
            var nullableMax = Correlated.max(Value.class, rows(REFERRER),
                    (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));
            assertValues(executor, nullableMin, null, 67L, 34L, null, null, 67L, 34L, null);
            assertValues(executor, nullableMax, null, 67L, 34L, null, null, 67L, 34L, null);
        }));
    }

    // AC-COL-39
    @TckTest
    void ac_col_39_sum_preserves_sum_typing_and_null_without_values(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "col-39-correlated-sum", ds -> withExecutor(ds, executor -> {
            assertValues(executor, SUM, money("79.02"), money("105.36"), money("39.51"), null,
                    money("79.02"), money("105.36"), money("39.51"), null);
            assertFiltered(executor, SUM, money("79.02"), 1L, 5L);
            assertNulls(executor, SUM, 4L, 8L);
            var nullableSum = Correlated.sum(Value.class, rows(REFERRER),
                    (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));
            assertValues(executor, nullableSum, null, 67L, 34L, null, null, 67L, 34L, null);
            assertThat(executor.list(orderedSum().build(), Limit.unlimited())).extracting(Value::id)
                    .containsExactly(3L, 7L, 1L, 5L, 2L, 6L);
        }));
    }

    @TckTest
    void ac_col_36_equal_fields_share_one_node_per_statement_across_select_filter_and_order(TckDatabase db) {
        var equal = count(INNER_ID).named("sameCount");
        Set<Expression<?>> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        var query = selecting(COUNT).select(SelectSet.of(ID, COUNT, equal))
                .where(f -> f.lte(ID, 8L).eq(equal, 2L)).orderBy(equal.asc(), ID.asc())
                .customize((spec, joins, criteria, cb, phase) -> {
                    Expression<Long> node = COUNT.expression(joins);
                    assertThat(equal.expression(joins)).isSameAs(node);
                    assertThat(criteria.getSelection().getCompoundSelectionItems()).hasSize(2);
                    assertThat(criteria.getSelection().getCompoundSelectionItems().get(1)).isSameAs(node);
                    assertThat(criteria.getOrderList().get(0).getExpression()).isSameAs(node);
                    nodes.add(node);
                }).build();
        SqlSnapshots.assertMatches(db, "col-36-correlated-equality", ds -> {
            try (var sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    for (int run = 0; run < 2; run++) {
                        var built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, RenderOptions.portable());
                        assertThat(em.createQuery(built.query()).getResultList()).extracting(tuple -> tuple.get(0))
                                .containsExactly(1L, 2L, 5L, 6L);
                    }
                });
            }
        });
        assertThat(nodes).as("each statement owns its node").hasSize(2);
    }

    @TckTest
    void ac_col_36_distinct_custom_conditions_keep_their_own_selected_values(TckDatabase db) {
        BiFunction<JoinContext, CriteriaBuilder, Predicate> small = (ctx, cb) -> cb.le(INNER_ID.path(ctx), 2L);
        BiFunction<JoinContext, CriteriaBuilder, Predicate> large = (ctx, cb) -> cb.le(INNER_ID.path(ctx), 6L);
        SqlSnapshots.assertMatches(db, "col-36-correlated-custom-equality", ds -> withExecutor(ds, executor -> {
            for (boolean labelled : List.of(false, true)) {
                UnaryOperator<Filters<Inner>> a = f -> labelled ? f.add("limit", small) : f.add(small);
                UnaryOperator<Filters<Inner>> b = f -> labelled ? f.add("limit", large) : f.add(large);
                for (boolean ownWhere : List.of(false, true)) {
                    var firstRows = ownWhere ? SubSelect.of(INNER_ID).where(a) : SubSelect.of(INNER_ID);
                    var secondRows = ownWhere ? SubSelect.of(INNER_ID).where(b) : SubSelect.of(INNER_ID);
                    var first = Correlated.count(Value.class, firstRows, (f, outer) -> {
                        f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS));
                        return ownWhere ? f : a.apply(f);
                    });
                    var second = Correlated.count(Value.class, secondRows, (f, outer) -> {
                        f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS));
                        return ownWhere ? f : b.apply(f);
                    });
                    var query = ModelQuery.builder(ROOT,
                                    row -> new Value(row.get(ID), List.of(row.get(first), row.get(second))))
                            .select(SelectSet.of(ID, first, second)).where(f -> f.lte(ID, 3L))
                            .orderBy(ID.asc()).build();
                    assertThat(executor.list(query, Limit.unlimited())).extracting(Value::value)
                            .containsExactly(List.of(1L, 2L), List.of(1L, 2L), List.of(0L, 1L));
                }
            }
        }));
    }

    // AC-COL-41
    @TckTest
    void ac_col_41_grouped_where_can_lift_a_non_group_key(TckDatabase db) {
        var status = ColumnField.of(Group.class, ROOT, "status", String.class);
        var id = ColumnField.of(Group.class, ROOT, "id", Long.class);
        var count = Agg.<Group>count(ROOT);
        var matches = Correlated.count(Group.class, rows(INNER_ID),
                (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(id)));
        var query = ModelQuery.builder(ROOT, row -> new Group(row.get(status), row.get(count)))
                .select(SelectSet.of(status, count)).groupBy(status)
                .where(f -> f.gt(matches, 0L)).orderBy(status.asc()).build();
        SqlSnapshots.assertMatches(db, "col-41-correlated-grouped-where", ds -> withExecutor(ds, executor ->
                assertThat(executor.list(query, Limit.unlimited())).containsExactly(
                        new Group("CANCELLED", 1L), new Group("PAID", 2L), new Group("SHIPPED", 2L))));
    }

    // AC-COL-41
    @TckTest
    void ac_col_41_count_omits_select_only_correlated_values_and_retains_correlated_where(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "col-41-correlated-count-statement", ds ->
                withExecutor(ds, executor -> {
                    assertThat(executor.count(selecting(COUNT).build())).isEqualTo(8L);
                    assertThat(executor.count(selecting(EXISTS).build())).isEqualTo(8L);
                    assertThat(executor.count(selecting(SUM).build())).isEqualTo(8L);
                    assertThat(executor.count(selecting(COUNT).where(f -> f.lte(ID, 8L).eq(COUNT, 2L)).build()))
                            .isEqualTo(4L);
                    assertThat(executor.count(selecting(EXISTS).where(f -> f.lte(ID, 8L).eq(EXISTS, false)).build()))
                            .isEqualTo(2L);
                    assertThat(executor.count(selecting(SUM).where(f -> f.lte(ID, 8L).isNull(SUM)).build()))
                            .isEqualTo(2L);
                }));
        assertThat(sql).hasSize(6);
        assertThat(sql.subList(0, 3)).allSatisfy(statement -> {
            assertThat(statement.split("select ", -1)).hasSize(2);
            assertThat(statement).doesNotContain("sum(", "case when", " join ");
        });
        assertThat(sql.get(3)).contains("(select count(");
        assertThat(sql.get(4)).contains("case when exists(select 1");
        assertThat(sql.get(5)).contains("(select sum(");
    }

    // AC-COL-39, AC-COL-41
    @TckTest
    void ac_col_39_ac_col_41_correlated_order_keys_page_export_and_primary_key_first(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "col-41-correlated-paging", ds ->
                withExecutor(ds, executor -> {
                    var plain = orderedSum().build();
                    var page = executor.page(plain, PageSpec.ofOffset(2, 3), CountMode.COUNT);
                    assertThat(page.total().orElseThrow()).isEqualTo(6L);
                    assertThat(page.content()).extracting(Value::id).containsExactly(1L, 5L, 2L);
                    var twoStep = orderedSum().primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
                    assertThat(executor.page(twoStep, PageSpec.ofOffset(2, 3), CountMode.NO_COUNT).content())
                            .isEqualTo(page.content());
                    var exported = new ArrayList<Value>();
                    assertThat(executor.export(plain, ExportOptions.of(2), values -> values, exported::add))
                            .isEqualTo(6L);
                    assertThat(exported).extracting(Value::id).containsExactly(3L, 7L, 1L, 5L, 2L, 6L);
                }));
        assertThat(sql.get(2).substring(0, sql.get(2).indexOf(" from ")))
                .as("primary-key-first selects keys before their models").doesNotContain("sum(");
        assertThat(sql).hasSize(8);
    }

    // AC-COL-43
    @TckTest
    void ac_col_43_an_update_where_over_the_targets_own_table_matches_the_same_rows_on_every_vendor(TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED")
                .where(f -> f.lte(ID, 8L).eq(COUNT, 2L)).build();
        List<Long> marked = new ArrayList<>();
        long[] written = new long[1];

        SqlSnapshots.assertMatches(db, "col-43-correlated-update", ds -> inRolledBackTransaction(ds, em -> {
            written[0] = executor(em).update(update);
            marked.addAll(ids(em, "o.status = 'MARKED'"));
        }));

        assertThat(written[0]).isEqualTo(4L);
        assertThat(marked).containsExactly(1L, 2L, 5L, 6L);
    }

    // AC-COL-43
    @TckTest
    void ac_col_43_a_delete_where_over_the_targets_own_table_matches_the_same_rows_on_every_vendor(TckDatabase db) {
        // Item codes repeat every 50 ids, so of ids 1..75 the codes of 1..25 are held twice and 26..50 once.
        var delete = ModelDelete.builder(ITEMS).primaryKey(PrimaryKey.of(ITEM_ID))
                .where(f -> f.lte(ITEM_ID, 75L).eq(SAME_CODE, 2L)).build();
        List<Long> left = new ArrayList<>();
        long[] written = new long[1];

        SqlSnapshots.assertMatches(db, "col-43-correlated-delete", ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, OrderItemEntity.class, ModelQueryConfig.defaults())
                    .delete(delete);
            left.addAll(em.createQuery("select i.id from OrderItemEntity i where i.id <= 75 order by i.id",
                    Long.class).getResultList());
        }));

        assertThat(written[0]).isEqualTo(50L);
        assertThat(left).hasSize(25).first().isEqualTo(26L);
    }

    private static ModelQueryExecutor<OrderEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
    }

    private static List<Long> ids(EntityManager em, String predicate) {
        return em.createQuery("select o.id from OrderEntity o where " + predicate + " order by o.id", Long.class)
                .getResultList();
    }

    private static void inRolledBackTransaction(DataSource dataSource, Consumer<EntityManager> work) {
        try (var sf = JoinTestSupport.sessionFactory(dataSource)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.accept(em);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }

    private static <C> SubSelect<Inner, C> rows(ColumnField<Inner, OrderEntity, C> column) {
        return SubSelect.of(column).where(f -> f.lte(INNER_ID, 6L).ne(INNER_STATUS, "NEW"));
    }

    private static ExpressionField<Value, Long> count(ColumnField<Inner, OrderEntity, Long> column) {
        return Correlated.count(Value.class, rows(column),
                (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));
    }

    private static ModelQuery.Builder<OrderEntity, Long, Value> selecting(ExpressionField<Value, ?> field) {
        return ModelQuery.builder(ROOT, row -> new Value(row.get(ID), row.get(field)))
                .select(SelectSet.of(ID, field)).primaryKey(PrimaryKey.of(ID)).where(f -> f.lte(ID, 8L));
    }

    private static ModelQuery.Builder<OrderEntity, Long, Value> orderedSum() {
        return selecting(SUM).where(f -> f.lte(ID, 8L).isNotNull(SUM)).orderBy(SUM.asc(), ID.asc());
    }

    private static void assertValues(ModelQueryExecutor<OrderEntity> executor, ExpressionField<Value, ?> field,
            Object... values) {
        assertThat(executor.list(selecting(field).orderBy(ID.asc()).build(), Limit.unlimited()))
                .extracting(Value::value).containsExactly(values);
    }

    private static <C> void assertFiltered(ModelQueryExecutor<OrderEntity> executor, ExpressionField<Value, C> field,
            C value, Long... ids) {
        assertThat(executor.list(selecting(field).where(f -> f.lte(ID, 8L).eq(field, value))
                .orderBy(ID.asc()).build(), Limit.unlimited())).extracting(Value::id).containsExactly(ids);
    }

    private static void assertNulls(ModelQueryExecutor<OrderEntity> executor, ExpressionField<Value, ?> field,
            Long... ids) {
        assertThat(executor.list(selecting(field).where(f -> f.lte(ID, 8L).isNull(field))
                .orderBy(ID.asc()).build(), Limit.unlimited())).extracting(Value::id).containsExactly(ids);
    }

    private static void assertOrdered(ModelQueryExecutor<OrderEntity> executor, ExpressionField<Value, ?> field,
            Long... ids) {
        assertThat(executor.list(selecting(field).where(f -> f.lte(ID, 8L).isNotNull(field))
                .orderBy(field.asc(), ID.asc()).build(), Limit.unlimited())).extracting(Value::id).containsExactly(ids);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static void withExecutor(DataSource dataSource, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (var sf = JoinTestSupport.sessionFactory(dataSource)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }
}
