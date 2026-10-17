package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.JoinType;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** AC-COL-40: ordered scalar sub-queries on every Tier-1 vendor and both Hibernate lines. */
class CorrelatedFirstTest {

    record Value(Long id, Long value) {}

    static final class Inner {}

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<Value, CustomerEntity, Long> ID =
            ColumnField.of(Value.class, CUSTOMERS, "id", Long.class);
    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER =
            TableField.join(ORDERS, "customer", JoinType.INNER);
    private static final ColumnField<Value, OrderEntity, Long> ORDER_ID =
            ColumnField.of(Value.class, ORDERS, "id", Long.class);
    private static final ColumnField<Value, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(Value.class, CUSTOMER, "name", String.class);
    private static final ColumnField<Inner, OrderEntity, Long> INNER_ID =
            ColumnField.of(Inner.class, ORDERS, "id", Long.class);
    private static final ColumnField<Inner, OrderEntity, String> STATUS =
            ColumnField.of(Inner.class, ORDERS, "status", String.class);
    private static final ColumnField<Inner, OrderEntity, Long> REFERRER =
            ColumnField.of(Inner.class, ORDERS, "referrerId", Long.class);
    private static final ColumnField<Inner, CustomerEntity, Long> CUSTOMER_ID =
            ColumnField.of(Inner.class, CUSTOMER, "id", Long.class);
    private static final ColumnField<Inner, CustomerEntity, String> INNER_CUSTOMER_NAME =
            ColumnField.of(Inner.class, CUSTOMER, "name", String.class);

    @TckTest
    void ac_col_40_first_per_parent_breaks_ties_by_id_and_returns_null_without_a_match(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "col-40-first-ties-empty", ds -> withExecutor(ds, executor -> {
            // All five orders of each customer have the same status, so only the appended id breaks the tie.
            assertValues(executor, first(STATUS.desc()), 1000L, 973L, 946L, 919L);
            var missing = Correlated.first(Value.class, SubSelect.of(INNER_ID).where(f -> f.lt(INNER_ID, 1000L)),
                    (f, outer) -> f.compare(CUSTOMER_ID, Op.EQ, outer.column(ID)), STATUS.desc());
            assertValues(executor, missing, null, 973L, 946L, 919L);
        }));
    }

    @TckTest
    void ac_col_40_explicit_null_precedence_selects_the_same_row_on_every_vendor(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "col-40-first-null-precedence", ds -> withExecutor(ds, executor -> {
            assertValues(executor, first(REFERRER.asc().nullsFirst()), 1000L, 973L, 946L, 919L);
            assertValues(executor, first(REFERRER.asc().nullsLast()), 3000L, 2973L, 2946L, 2919L);
            assertValues(executor, first(REFERRER.desc().nullsFirst()), 1000L, 973L, 946L, 919L);
            assertValues(executor, first(REFERRER.desc().nullsLast()), 3000L, 2973L, 2946L, 2919L);
        }));
    }

    @TckTest
    void ac_col_40_default_leaves_null_precedence_to_the_database(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "col-40-first-default", ds -> withExecutor(ds,
                executor -> executor.list(selecting(first(REFERRER.asc())).orderBy(ID.asc()).build(),
                        Limit.unlimited())));
        // The selected row is vendor-dependent under DEFAULT; only its SQL is specified.
        assertThat(sql).singleElement().asString().contains("order by").doesNotContain("nulls first", "nulls last",
                "case when");
    }

    @TckTest
    void ac_col_40_first_in_selection_filter_and_order_is_reusable_across_statements(TckDatabase db) {
        var first = first(STATUS.desc());
        var query = selecting(first).where(f -> f.lte(ID, 4L).gte(first(STATUS.desc()), 950L))
                .orderBy(first(STATUS.desc()).desc()).build();
        SqlSnapshots.assertMatches(db, "col-40-first-scalar-positions", ds -> withExecutor(ds, executor -> {
            for (int i = 0; i < 2; i++) {
                assertThat(executor.list(query, Limit.unlimited()))
                        .containsExactly(new Value(1L, 1000L), new Value(2L, 973L));
            }
        }));
    }

    @TckTest
    void ac_col_40_first_correlates_through_a_lifted_to_one(TckDatabase db) {
        var first = Correlated.first(Value.class, SubSelect.of(INNER_ID),
                (f, outer) -> f.compare(INNER_CUSTOMER_NAME, Op.EQ, outer.column(CUSTOMER_NAME)), INNER_ID.desc());
        var query = ModelQuery.builder(ORDERS, row -> new Value(row.get(ORDER_ID), row.get(first)))
                .select(SelectSet.of(ORDER_ID, first)).where(f -> f.lte(ORDER_ID, 3L)).orderBy(ORDER_ID.asc()).build();
        SqlSnapshots.assertMatches(db, "col-40-first-lifted-to-one", ds -> {
            try (var sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> assertThat(ModelQueryExecutor.create(em, OrderEntity.class,
                        ModelQueryConfig.defaults()).list(query, Limit.unlimited()))
                        .containsExactly(new Value(1L, 4001L), new Value(2L, 4002L), new Value(3L, 4003L)));
            }
        });
    }

    @TckTest
    void ac_col_40_first_without_model_query_hibernate_is_mq4014_before_any_statement(TckDatabase db) {
        var query = selecting(first(STATUS.asc())).where(f -> f.lte(ID, 4L).isNotNull(first(STATUS.asc()))).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> JoinTestSupport.withoutServices(() ->
                withExecutor(ds, executor -> {
                    assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                            .hasMessageContaining("SubqueryRenderer");
                    assertThatThrownBy(() -> executor.count(query))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                            .hasMessageContaining("SubqueryRenderer");
                })));
        assertThat(sql).isEmpty();
        var count = Correlated.count(Value.class, SubSelect.of(INNER_ID),
                (f, outer) -> f.compare(CUSTOMER_ID, Op.EQ, outer.column(ID)));
        List<String> countSql = SqlSnapshots.capture(db, ds -> JoinTestSupport.withoutServices(() ->
                withExecutor(ds, executor -> assertValues(executor, count, 5L, 5L, 5L, 5L))));
        assertThat(countSql).singleElement().asString().contains("count(");
    }

    private static ExpressionField<Value, Long> first(OrderField<Inner, ?> order) {
        return Correlated.first(Value.class, SubSelect.of(INNER_ID),
                (f, outer) -> f.compare(CUSTOMER_ID, Op.EQ, outer.column(ID)), order);
    }

    private static ModelQuery.Builder<CustomerEntity, Object, Value> selecting(ExpressionField<Value, Long> first) {
        return ModelQuery.builder(CUSTOMERS, row -> new Value(row.get(ID), row.get(first)))
                .select(SelectSet.of(ID, first)).where(f -> f.lte(ID, 4L));
    }

    private static void assertValues(ModelQueryExecutor<CustomerEntity> executor, ExpressionField<Value, Long> first,
            Long... expected) {
        assertThat(executor.list(selecting(first).orderBy(ID.asc()).build(), Limit.unlimited()))
                .extracting(Value::value).containsExactly(expected);
    }

    private static void withExecutor(DataSource dataSource, Consumer<ModelQueryExecutor<CustomerEntity>> work) {
        try (var sf = JoinTestSupport.sessionFactory(dataSource)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, CustomerEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }
}
