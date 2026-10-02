package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.grouped;
import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ChildQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.LabelEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.PatronEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Children loaded {@code through} an association path from the parent's root: a unidirectional and a bidirectional
 * many-to-many, a path back to the parent's own entity, a path through a join entity, children shared by parents, and
 * the child filters, order, customizer and bound applied under the path's join (spec api/15 R-FCH-04, R-FCH-11,
 * R-FCH-14, D-99, D-100; AC-FCH-11).
 */
class ThroughChildTest {

    private static final FetchPlan<OrderLines> ORDER = FetchPlan.of(QOrderLines.ALL);

    /** Labels 1 to 10 with their orders, loaded by {@code child}, by id. */
    private static ModelQuery<LabelEntity, Long, LabelOrders> labels(ChildField<LabelOrders, OrderLines> field,
            UnaryOperator<ChildQuery<OrderLines>> child) {
        return QLabelOrders.query().orderBy(QLabelOrders.ID.asc())
                .fetch(FetchPlan.of(QLabelOrders.ALL).child(field, ORDER, child)).build();
    }

    /** The labelled orders of every label, by label, where {@code where} holds for the order {@code o}. */
    private static Map<Long, List<Long>> ordersOf(TckDatabase db, String where, String order) {
        return grouped(db, "SELECT ol.label_id, o.id FROM order_labels ol JOIN orders o ON o.id = ol.order_id WHERE "
                + where + " ORDER BY " + order);
    }

    /** The order ids of each loaded label, by label id. */
    private static Map<Long, List<Long>> loaded(List<LabelOrders> labels) {
        var result = new LinkedHashMap<Long, List<Long>>();
        labels.stream().filter(label -> !label.orders().isEmpty()).forEach(label ->
                result.put(label.id(), label.orders().stream().map(OrderLines::id).toList()));
        return result;
    }

    @TckTest
    void ac_fch_11_a_unidirectional_many_to_many_to_its_own_entity_loads_through_its_path(TckDatabase db) {
        // Customer 501 placed order 1500, which it referred itself; customers 1 to 30 have one referrer at most,
        // reached once per referred order.
        var q = QPatronReferrers.query()
                .where(f -> f.or(a -> a.lte(QPatronReferrers.ID, 30L), b -> b.eq(QPatronReferrers.ID, 501L)))
                .orderBy(QPatronReferrers.ID.asc())
                .fetch(FetchPlan.of(QPatronReferrers.ALL)
                        .child(QPatronReferrers.REFERRERS, FetchPlan.of(QReferrer.ALL)))
                .build();
        Map<Long, List<Long>> expected = grouped(db, "SELECT DISTINCT customer_id, referrer_id FROM orders WHERE "
                + "referrer_id IS NOT NULL AND (customer_id <= 30 OR customer_id = 501) ORDER BY referrer_id");

        SqlSnapshots.assertMatches(db, "fch-11-through-unidirectional", ds -> withExecutor(ds, PatronEntity.class,
                executor -> {
                    List<PatronReferrers> loaded = executor.list(q, Limit.unlimited());
                    assertThat(loaded).hasSize(31).allSatisfy(customer -> {
                        assertThat(customer.referrers()).extracting(Referrer::id)
                                .containsExactlyElementsOf(expected.getOrDefault(customer.id(), List.of()));
                        assertThat(customer.referrers()).allSatisfy(referrer ->
                                assertThat(referrer.name()).isEqualTo(String.format("Customer %04d", referrer.id())));
                    });
                    assertThat(loaded.get(30).referrers()).extracting(Referrer::id).containsExactly(501L);
                    assertThat(loaded).filteredOn(customer -> !customer.referrers().isEmpty()).isNotEmpty();
                }));
    }

    @TckTest
    void ac_fch_11_a_bidirectional_many_to_many_loads_alike_by_foreign_key_and_through(TckDatabase db) {
        // Orders 191 to 200 have labels, 201 to 210 none; each label belongs to many of them.
        var byForeignKey = QOrderLabels.query().where(f -> f.between(QOrderLabels.ID, 191L, 210L))
                .orderBy(QOrderLabels.ID.asc())
                .fetch(FetchPlan.of(QOrderLabels.ALL).child(QOrderLabels.LABELS, FetchPlan.of(QLabelView.ALL)))
                .build();
        var through = QOrderLabelSet.query().where(f -> f.between(QOrderLabelSet.ID, 191L, 210L))
                .orderBy(QOrderLabelSet.ID.asc())
                .fetch(FetchPlan.of(QOrderLabelSet.ALL).child(QOrderLabelSet.LABELS, FetchPlan.of(QLabelView.ALL)))
                .build();

        SqlSnapshots.assertMatches(db, "fch-11-through-bidirectional", ds -> withExecutor(ds, OrderEntity.class,
                executor -> {
                    List<OrderLabelSet> loaded = executor.list(through, Limit.unlimited());
                    assertThat(loaded).hasSize(20).allSatisfy(order -> assertThat(order.labels())
                            .extracting(LabelView::id).containsExactlyElementsOf(TckFixture.labelsOf(order.id())));
                    assertThat(loaded).extracting(OrderLabelSet::labels).containsExactlyElementsOf(executor
                            .list(byForeignKey, Limit.unlimited()).stream().map(OrderLabels::labels).toList());
                }));
    }

    @TckTest
    void ac_fch_11_a_child_shared_by_two_parents_in_one_round_appears_under_both(TckDatabase db) {
        var q = labels(QLabelOrders.ORDERS, UnaryOperator.identity());
        Map<Long, List<Long>> expected = ordersOf(db, "1 = 1", "o.id");

        withExecutor(JoinTestSupport.dataSource(db), LabelEntity.class, executor -> {
            List<LabelOrders> loaded = executor.list(q, Limit.unlimited());
            assertThat(loaded).hasSize(TckFixture.LABELS);
            assertThat(loaded(loaded)).isEqualTo(expected);
            // Order 2 carries labels 3 and 7, and is mapped under each.
            assertThat(TckFixture.labelsOf(2)).containsExactly(3L, 7L);
            assertThat(loaded.get(2).orders()).extracting(OrderLines::id).contains(2L);
            assertThat(loaded.get(6).orders()).extracting(OrderLines::id).contains(2L);
            assertThat(loaded.get(2).orders().stream().filter(order -> order.id() == 2L).findFirst())
                    .isEqualTo(loaded.get(6).orders().stream().filter(order -> order.id() == 2L).findFirst());
        });
    }

    @TckTest
    void ac_fch_11_a_path_through_a_join_entity_loads_each_child_once_per_parent(TckDatabase db) {
        var q = QLabelBuyers.query().orderBy(QLabelBuyers.ID.asc())
                .fetch(FetchPlan.of(QLabelBuyers.ALL).child(QLabelBuyers.BUYERS, FetchPlan.of(QBuyer.ALL)))
                .build();
        Map<Long, List<Long>> expected = grouped(db, "SELECT DISTINCT ol.label_id, o.customer_id FROM order_labels ol "
                + "JOIN orders o ON o.id = ol.order_id ORDER BY o.customer_id");

        SqlSnapshots.assertMatches(db, "fch-11-through-join-entity", ds -> withExecutor(ds, LabelEntity.class,
                executor -> assertThat(executor.list(q, Limit.unlimited())).hasSize(TckFixture.LABELS)
                        .allSatisfy(label -> assertThat(label.buyers()).extracting(Buyer::id)
                                .containsExactlyElementsOf(expected.get(label.id())))));
    }

    @TckTest
    void ac_fch_11_a_to_one_then_collection_path_reaches_the_parent_entity_itself(TckDatabase db) {
        var q = QOrderSiblings.query().where(f -> f.lte(QOrderSiblings.ID, 5L)).orderBy(QOrderSiblings.ID.asc())
                .fetch(FetchPlan.of(QOrderSiblings.ALL)
                        .child(QOrderSiblings.SIBLINGS, FetchPlan.of(QOrderRef.ALL)))
                .build();
        Map<Long, List<Long>> expected = grouped(db, "SELECT o.id, s.id FROM orders o JOIN orders s "
                + "ON s.customer_id = o.customer_id WHERE o.id <= 5 ORDER BY s.id");

        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor ->
                assertThat(executor.list(q, Limit.unlimited())).hasSize(5).allSatisfy(order -> {
                    assertThat(order.siblings()).extracting(OrderRef::id)
                            .containsExactlyElementsOf(expected.get(order.id())).contains(order.id());
                }));
    }

    @TckTest
    void ac_fch_11_child_filters_and_order_apply_through_the_path_and_the_child_plan_runs(TckDatabase db) {
        var plan = FetchPlan.of(QLabelOrders.ALL).child(QLabelOrders.ORDERS,
                FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS, FetchPlan.of(QLine.ALL)),
                c -> c.where(f -> f.eq(QOrderLines.STATUS, "PAID")).orderBy(QOrderLines.TOTAL.desc()));
        var q = QLabelOrders.query().where(f -> f.lte(QLabelOrders.ID, 3L)).orderBy(QLabelOrders.ID.asc())
                .fetch(plan).build();
        Map<Long, List<Long>> expected =
                ordersOf(db, "ol.label_id <= 3 AND o.status = 'PAID'", "o.total DESC, o.id");
        Map<Long, List<Long>> items = grouped(db, "SELECT order_id, id FROM order_items ORDER BY id");

        SqlSnapshots.assertMatches(db, "fch-11-through-filter-order", ds -> withExecutor(ds, LabelEntity.class,
                executor -> {
                    List<LabelOrders> loaded = executor.list(q, Limit.unlimited());
                    assertThat(loaded(loaded)).isEqualTo(expected);
                    assertThat(loaded).flatExtracting(LabelOrders::orders).isNotEmpty().allSatisfy(order -> {
                        assertThat(order.status()).isEqualTo("PAID");
                        assertThat(order.items()).extracting(Line::id)
                                .containsExactlyElementsOf(items.get(order.id()));
                    });
                }));
    }

    @TckTest
    void ac_fch_11_a_child_filter_through_a_to_many_join_reads_each_child_once(TckDatabase db) {
        // Every order has four items, so the filter through the to-many join reads each order four times.
        var q = labels(QLabelOrders.ORDERS,
                c -> c.where(f -> f.like(QOrderLines.ITEM_CODE, "P0", LikeMode.STARTS_WITH)));

        withExecutor(JoinTestSupport.dataSource(db), LabelEntity.class, executor ->
                assertThat(loaded(executor.list(q, Limit.unlimited()))).isEqualTo(ordersOf(db, "1 = 1", "o.id")));
    }

    @TckTest
    void ac_fch_11_or_exists_and_custom_filters_resolve_under_the_through_join(TckDatabase db) {
        var q = labels(QLabelOrders.ORDERS, c -> c.where(f -> f
                .or(a -> a.eq(QOrderLines.STATUS, "PAID"), b -> b.eq(QOrderLines.ITEM_CODE, "P001"))
                .exists(QOrderLines.ITEMS_INNER_TABLE, i -> i.like(QOrderLines.ITEM_CODE, "P00", LikeMode.STARTS_WITH))
                .add((joins, cb) -> cb.lt(QOrderLines.ROOT.resolve(joins).<Long>get("id"), 150L))));
        String item = "EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id AND i.product_code ";
        Map<Long, List<Long>> expected = ordersOf(db, "(o.status = 'PAID' OR " + item + "= 'P001')) AND " + item
                + "LIKE 'P00%') AND o.id < 150", "o.id");

        SqlSnapshots.assertMatches(db, "fch-11-through-or-exists-add", ds -> withExecutor(ds, LabelEntity.class,
                executor -> assertThat(loaded(executor.list(q, Limit.unlimited()))).isNotEmpty()
                        .isEqualTo(expected)));
    }

    @TckTest
    void ac_fch_11_a_customizer_on_the_child_model_resolves_under_the_through_join(TckDatabase db) {
        var roots = new ArrayList<Class<?>>();
        QueryCustomizer customizer = (spec, joins, query, cb, phase) -> {
            query.getRoots().forEach(root -> roots.add(root.getJavaType()));
            Predicate own = query.getRestriction();
            Predicate cheap = cb.lt(QOrderLines.TOTAL.path(joins), new BigDecimal("300.00"));
            query.where(own == null ? cheap : cb.and(own, cheap));
        };
        var q = labels(customized(QLabelOrders.ORDERS, customizer), UnaryOperator.identity());
        Map<Long, List<Long>> expected = ordersOf(db, "o.total < 300", "o.id");

        withExecutor(JoinTestSupport.dataSource(db), LabelEntity.class, executor ->
                assertThat(loaded(executor.list(q, Limit.unlimited()))).isNotEmpty().isEqualTo(expected));
        // The child query is rooted at the parent's entity; its first-run check builds the child model on its own.
        assertThat(roots).contains(LabelEntity.class);
    }

    @TckTest
    void ac_fch_11_max_per_parent_bounds_a_through_child_with_mq2603(TckDatabase db) {
        var plan = FetchPlan.of(QLabelOrders.ALL).child(QLabelOrders.ORDERS, ORDER, c -> c.maxPerParent(5));
        var q = QLabelOrders.query().where(f -> f.eq(QLabelOrders.ID, 1L)).fetch(plan).build();

        withExecutor(JoinTestSupport.dataSource(db), LabelEntity.class, executor ->
                assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2603))
                        .hasMessage("MQ2603: LabelOrders.orders: key 1 has 6 children, more than maxPerParent(5); "
                                + "raise the bound or narrow the child filters"));
    }

    /** The generated {@code field}, its child model customized by {@code customizer}. */
    private static ChildField<LabelOrders, OrderLines> customized(ChildField<LabelOrders, OrderLines> field,
            QueryCustomizer customizer) {
        return new ChildField<>() {
            @Override
            public String name() {
                return field.name();
            }

            @Override
            public ColumnField<LabelOrders, ?, ?> key() {
                return field.key();
            }

            @Override
            public Optional<ColumnField<OrderLines, ?, ?>> foreignKey() {
                return field.foreignKey();
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return field.through();
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, OrderLines> query() {
                return field.query().customize(customizer);
            }

            @Override
            public LabelOrders with(LabelOrders parent, List<OrderLines> children) {
                return field.with(parent, children);
            }
        };
    }
}
