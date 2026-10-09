package com.rey.modelquery.tck.exe;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** The single-row reads {@code one}, {@code first} and {@code one(q, key)} on every vendor (engine/20 R-EXE-12). */
class SingleRowTest {

    record OrderRow(Long id, String status, BigDecimal total) {}

    record Group(Long customerId, BigDecimal total) {}

    record NullableRow(Long id, Integer sortInt) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);

    private static final ColumnField<OrderRow, OrderEntity, Long> ID =
            ColumnField.of(OrderRow.class, ORDERS, "id", Long.class);
    private static final ColumnField<OrderRow, OrderEntity, String> STATUS =
            ColumnField.of(OrderRow.class, ORDERS, "status", String.class);
    private static final ColumnField<OrderRow, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(OrderRow.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<OrderRow, OrderItemEntity, String> ITEM_PRODUCT_FILTER =
            ColumnField.of(OrderRow.class, ITEMS, "productCode", String.class);

    private static final ColumnField<Group, CustomerEntity, Long> GROUP_CUSTOMER =
            ColumnField.of(Group.class, CUSTOMER, "id", Long.class);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER = TableField.join(ORDERS, "referrer", LEFT);
    private static final ColumnField<Group, CustomerEntity, Long> GROUP_REFERRER =
            ColumnField.of(Group.class, REFERRER, "id", Long.class);
    private static final AggregateField<Group, BigDecimal> GROUP_TOTAL =
            Agg.sum(ColumnField.of(Group.class, ORDERS, "total", BigDecimal.class));

    private static final TableField<NullableSortEntity, NullableSortEntity> NULLABLE =
            TableField.root(NullableSortEntity.class);
    private static final ColumnField<NullableRow, NullableSortEntity, Long> NULLABLE_ID =
            ColumnField.of(NullableRow.class, NULLABLE, "id", Long.class);
    private static final ColumnField<NullableRow, NullableSortEntity, Integer> SORT_INT =
            ColumnField.of(NullableRow.class, NULLABLE, "sortInt", Integer.class);

    /** Every order, keyed by id, in no order. */
    private static final ModelQuery.Builder<OrderEntity, Long, OrderRow> ORDER_ROWS = ModelQuery
            .builder(ORDERS, row -> new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL)))
            .select(SelectSet.of(ID, STATUS, TOTAL))
            .primaryKey(PrimaryKey.of(ID));

    // ---- AC-EXE-11

    @TckTest
    void ac_exe_11_one_reads_at_a_limit_of_two_and_returns_the_single_row(TckDatabase db) {
        List<Optional<OrderRow>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-11-one", ds -> withExecutor(ds,
                executor -> results.add(executor.one(ORDER_ROWS.where(f -> f.eq(ID, 7L)).build()))));
        assertThat(sql).hasSize(1);
        assertThat(results).singleElement().satisfies(row -> assertThat(row).map(OrderRow::id).contains(7L));
        withExecutor(db, executor -> assertThat(executor.one(ORDER_ROWS.where(f -> f.eq(ID, -1L)).build()))
                .isEmpty());
    }

    @TckTest
    void ac_exe_11_an_enricher_never_sees_the_extra_row(TckDatabase db) {
        List<Integer> enriched = new ArrayList<>();
        var enrichedRows = ORDER_ROWS.fetch(FetchPlan.of(SelectSet.of(ID, STATUS, TOTAL)).enrich(Enricher.of(page -> {
            enriched.add(page.size());
            return page;
        })));
        withExecutor(db, executor -> {
            assertMq(() -> executor.one(enrichedRows.where(f -> f.eq(STATUS, "NEW")).build()), MqCode.MQ2003,
                    "OrderRow: one(query) found more than one row");
            assertThat(enriched).isEmpty();
            assertThat(executor.one(enrichedRows.where(f -> f.eq(ID, 1L)).build())).isPresent();
            assertThat(enriched).containsExactly(1);
        });
    }

    @TckTest
    void ac_exe_11_a_filter_only_to_many_join_names_the_join_and_filters_exists(TckDatabase db) {
        // Order 1 holds four items of product P001, so the filter's join repeats it four times.
        var throughItems = ORDER_ROWS.where(f -> f.eq(ID, 1L).eq(ITEM_PRODUCT_FILTER, "P001")).build();
        withExecutor(db, executor -> assertMq(() -> executor.one(throughItems), MqCode.MQ2003,
                "OrderRow: one(query) found more than one row; if the to-many join OrderEntity.items, which no "
                        + "selected column reads, repeats the row once per matching child, filter with "
                        + "Filters.exists(...) instead"));
    }

    // ---- AC-EXE-12

    @TckTest
    void ac_exe_12_first_on_a_nullable_non_id_key_gives_the_same_row_on_every_vendor(TckDatabase db) {
        // sort_int is NULL on rows 5 and 10, 3 and 4 on rows 3 and 4: NULLs last leaves row 3 first everywhere.
        var byNullableKey = ModelQuery
                .builder(NULLABLE, row -> new NullableRow(row.get(NULLABLE_ID), row.get(SORT_INT)))
                .select(SelectSet.of(NULLABLE_ID, SORT_INT))
                .primaryKey(PrimaryKey.of(SORT_INT))
                .where(f -> f.in(NULLABLE_ID, List.of(5L, 10L, 4L, 3L)))
                .build();
        List<Optional<NullableRow>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-12-first-nullable-key", ds -> withExecutor(ds,
                NullableSortEntity.class, executor -> results.add(executor.first(byNullableKey))));
        assertThat(sql).hasSize(1);
        assertThat(results).containsExactly(Optional.of(new NullableRow(3L, 3)));
    }

    @TckTest
    void ac_exe_12_a_grouped_first_orders_by_its_group_keys(TckDatabase db) {
        var groups = ModelQuery.builder(ORDERS, row -> new Group(row.get(GROUP_CUSTOMER), row.get(GROUP_TOTAL)))
                .select(SelectSet.of(GROUP_CUSTOMER, GROUP_TOTAL))
                .groupBy(GROUP_CUSTOMER);
        withExecutor(db, executor -> {
            assertThat(executor.first(groups.build())).map(Group::customerId).contains(1L);
            assertThat(executor.first(groups.orderBy(GROUP_CUSTOMER.desc()).build())).map(Group::customerId)
                    .contains(1_000L);
            assertThat(executor.first(groups.orderBy(GROUP_TOTAL.desc()).build()))
                    .isEqualTo(executor.list(groups.orderBy(GROUP_TOTAL.desc(), GROUP_CUSTOMER.asc()).build(),
                            Limit.of(1)).stream().findFirst());
        });
    }

    @TckTest
    void ac_exe_12_a_key_read_through_a_left_join_sorts_nulls_last_though_its_attribute_is_the_id(TckDatabase db) {
        // The referrer's @Id is never NULL, but the LEFT join makes it NULL for two thirds of the orders.
        var groups = ModelQuery.builder(ORDERS, row -> new Group(row.get(GROUP_REFERRER), row.get(GROUP_TOTAL)))
                .select(SelectSet.of(GROUP_REFERRER, GROUP_TOTAL))
                .groupBy(GROUP_REFERRER);
        withExecutor(db, executor -> assertThat(executor.first(groups.build())).map(Group::customerId).isPresent());
    }

    // ---- AC-EXE-13

    @TckTest
    void ac_exe_13_one_by_key_ands_the_key_and_contradictory_filters_give_empty(TckDatabase db) {
        var first = ORDER_ROWS.where(f -> f.eq(ID, 1L)).build();
        List<Optional<OrderRow>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-13-one-by-key",
                ds -> withExecutor(ds, executor -> results.add(executor.one(first, 2L))));
        assertThat(sql).hasSize(1);
        assertThat(results).containsExactly(Optional.empty());
        withExecutor(db, executor -> {
            assertThat(executor.one(first, 1L)).map(OrderRow::id).contains(1L);
            assertThat(executor.one(ORDER_ROWS.build(), 42L)).map(OrderRow::id).contains(42L);
        });
    }

    private static void assertMq(Runnable call, MqCode code, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessage(code.code() + ": " + message);
    }

    private static void withExecutor(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        withExecutor(ds, OrderEntity.class, work);
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
