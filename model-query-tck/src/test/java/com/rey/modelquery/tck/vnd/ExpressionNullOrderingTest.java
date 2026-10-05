package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.util.Arrays;
import java.util.List;

/**
 * Null precedence over a binding expression group key: its value comes from a bound parameter, so the provider's own
 * renderer keeps the key out of the ORDER BY text where it can, and otherwise the engine renders the portable null
 * key inside {@code MIN(...)}, which a database can still tie to its GROUP BY item (R-COL-12, R-COL-19, R-AGG-14).
 */
class ExpressionNullOrderingTest {

    record Group(Integer key, Long count) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<Group, OrderItemEntity, Integer> QUANTITY =
            ColumnField.of(Group.class, ITEMS, "quantity", Integer.class);
    private static final ColumnField<Group, OrderItemEntity, Long> ID =
            ColumnField.of(Group.class, ITEMS, "id", Long.class);
    /** {@code quantity + 1}: a group key that binds a value, so the database renders it as one select item. */
    private static final ExpressionField<Group, Integer> KEY = Expr.plus(QUANTITY, 1);
    private static final AggregateField<Group, Long> COUNT = Agg.count(ID);
    /** {@code nullIf(quantity + 1, 2)}: NULL where the key is 2, so an explicit null precedence is visible. */
    private static final ExpressionField<Group, Integer> NULLABLE_KEY = Expr.nullIf(KEY, 2);
    private static final ModelQuery.Builder<OrderItemEntity, Object, Group> GROUPS =
            ModelQuery.builder(ITEMS, row -> new Group(row.get(KEY), row.get(COUNT)));
    private static final ModelQuery.Builder<OrderItemEntity, Object, Group> NULLABLE_GROUPS =
            ModelQuery.builder(ITEMS, row -> new Group(row.get(NULLABLE_KEY), row.get(COUNT)));

    /** The nine keys of the fixture, quantity 1..9 shifted by one. */
    private static final List<Integer> ASCENDING = List.of(2, 3, 4, 5, 6, 7, 8, 9, 10);
    private static final List<Integer> DESCENDING = List.of(10, 9, 8, 7, 6, 5, 4, 3, 2);
    /** The nullable key's values, with NULL first for {@code nullsFirst()} and last for {@code nullsLast()}. */
    private static final List<Integer> NULLABLE_FIRST = Arrays.asList(null, 3, 4, 5, 6, 7, 8, 9, 10);
    private static final List<Integer> NULLABLE_LAST = Arrays.asList(3, 4, 5, 6, 7, 8, 9, 10, null);

    @TckTest
    void ac_agg_15_orders_an_expression_group_key_with_explicit_null_precedence(TckDatabase db) {
        assertOrders(db, true, GROUPS, KEY, KEY.asc().nullsLast(), ASCENDING);
        assertOrders(db, true, GROUPS, KEY, KEY.desc().nullsFirst(), DESCENDING);
    }

    @TckTest
    void ac_agg_15_orders_an_expression_group_key_by_the_portable_null_key(TckDatabase db) {
        assertOrders(db, false, GROUPS, KEY, KEY.asc().nullsLast(), ASCENDING);
        assertOrders(db, false, GROUPS, KEY, KEY.desc().nullsFirst(), DESCENDING);
    }

    @TckTest
    void ac_agg_15_orders_a_nullable_expression_group_key_with_explicit_null_precedence(TckDatabase db) {
        assertOrders(db, true, NULLABLE_GROUPS, NULLABLE_KEY, NULLABLE_KEY.asc().nullsFirst(), NULLABLE_FIRST);
        assertOrders(db, true, NULLABLE_GROUPS, NULLABLE_KEY, NULLABLE_KEY.asc().nullsLast(), NULLABLE_LAST);
    }

    @TckTest
    void ac_agg_15_orders_a_nullable_expression_group_key_by_the_portable_null_key(TckDatabase db) {
        assertOrders(db, false, NULLABLE_GROUPS, NULLABLE_KEY, NULLABLE_KEY.asc().nullsFirst(), NULLABLE_FIRST);
        assertOrders(db, false, NULLABLE_GROUPS, NULLABLE_KEY, NULLABLE_KEY.asc().nullsLast(), NULLABLE_LAST);
    }

    private static void assertOrders(TckDatabase db, boolean hibernate,
            ModelQuery.Builder<OrderItemEntity, Object, Group> groups, ExpressionField<Group, Integer> key,
            OrderField<Group, ?> order, List<Integer> expected) {
        var query = groups.select(SelectSet.of(key, COUNT)).groupBy(key).orderBy(order).build();
        JoinTestSupport.withExecutor(JoinTestSupport.sessionFactory(db), hibernate, OrderItemEntity.class,
                ModelQueryConfig.defaults(), executor -> assertThat(executor.list(query, Limit.unlimited()))
                        .as("%s, hibernate %s", order, hibernate).extracting(Group::key)
                        .containsExactlyElementsOf(expected));
    }
}
