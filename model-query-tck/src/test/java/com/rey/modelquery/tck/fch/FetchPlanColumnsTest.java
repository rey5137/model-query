package com.rey.modelquery.tck.fch;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * The columns a fetch plan needs are selected as the selection is, and one read through a to-many join is refused on
 * first execution (spec api/15 R-FCH-02, AC-FCH-06, AC-FCH-07).
 */
class FetchPlanColumnsTest {

    record OrderRow(Long id, String email) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", LEFT);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER =
            TableField.join(ORDERS, "customer", LEFT);
    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(OrderRow.class, ITEMS, "productCode", String.class);
    private static final ColumnField<OrderRow, CustomerEntity, String> CUSTOMER_EMAIL =
            ColumnField.of(OrderRow.class, CUSTOMER, "email", String.class);

    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ORDER_ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(CUSTOMER_EMAIL)))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());

    @TckTest
    void ac_fch_07_a_plan_column_through_a_to_many_join_throws_mq1702_on_first_execution(TckDatabase db) {
        var plan = FetchPlan.of(SelectSet.of(ID)).enrich(Enricher.of(UnaryOperator.identity(), ITEM_PRODUCT));
        // Not at build(), which has no metamodel to tell a to-many join from a to-one.
        ModelQuery<OrderEntity, Long, OrderRow> q = ORDER_ROWS.fetch(plan).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, executor -> {
            // A check that threw is not remembered, so the second call throws too.
            for (int call = 0; call < 2; call++) {
                assertThatThrownBy(() -> executor.list(q, Limit.of(10)))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1702))
                        .hasMessage("MQ1702: OrderRow.productCode: the fetch plan needs it, but it is read through "
                                + "the to-many join OrderEntity.items, where one row holds several of its values; "
                                + "key the child or the enricher by a column on a to-one path");
            }
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_fch_07_count_runs_with_a_plan_column_through_a_to_many_join_and_list_still_throws(TckDatabase db) {
        var plan = FetchPlan.of(SelectSet.of(ID)).enrich(Enricher.of(UnaryOperator.identity(), ITEM_PRODUCT));
        ModelQuery<OrderEntity, Long, OrderRow> q = ORDER_ROWS.fetch(plan).build();

        withExecutor(JoinTestSupport.dataSource(db), executor -> {
            // count loads no children and runs no enricher, so it logs a WARNING and counts.
            assertThat(executor.count(q)).isPositive();
            // The failed check is not remembered, so a call that returns models still throws.
            assertThatThrownBy(() -> executor.list(q, Limit.of(10)))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1702));
        });
    }

    @TckTest
    void ac_fch_06_an_enricher_column_on_a_to_one_path_is_selected_and_mapped(TckDatabase db) {
        var plan = FetchPlan.of(SelectSet.of(ID)).enrich(Enricher.of(UnaryOperator.identity(), CUSTOMER_EMAIL));
        ModelQuery<OrderEntity, Long, OrderRow> q = ORDER_ROWS.fetch(plan).build();

        assertThat(q.select().fields()).containsExactly(ID);
        withExecutor(JoinTestSupport.dataSource(db), executor -> assertThat(executor.list(q, Limit.of(3)))
                .extracting(OrderRow::email).doesNotContainNull().hasSize(3));
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }
}
