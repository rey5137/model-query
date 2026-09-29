package com.rey.modelquery.tck.col;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/** Table fields and join sharing (spec api/10 R-COL-01..05). */
class ColumnsJoinsTest {

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ROOT, "items", INNER);
    private static final TableField<OrderItemEntity, OrderEntity> ITEM_ORDER = TableField.join(ITEMS, "order", INNER);

    @TckTest
    void ac_col_01_select_filter_and_order_on_one_path_render_one_join(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "col-01-one-path-one-join", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<Tuple> q = cb.createTupleQuery();
                    Root<OrderEntity> root = q.from(OrderEntity.class);
                    JoinContext ctx = JoinContext.of(root, cb);
                    // A second, separately built definition of the same path shares the join (R-COL-02).
                    var again = TableField.join(TableField.root(OrderEntity.class), "items", INNER);
                    q.multiselect(root.<Long>get("id"), ITEMS.resolve(ctx).get("productCode"))
                            .where(cb.gt(again.resolve(ctx).<Integer>get("quantity"), 5))
                            .orderBy(cb.asc(ITEMS.resolve(ctx).get("id")));
                    assertThat(em.createQuery(q).setMaxResults(5).getResultList()).hasSize(5);
                });
            }
        });
    }

    @TckTest
    void ac_col_01_as_renders_two_joins_and_children_of_an_aliased_join_stay_separate(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "col-01-alias-two-joins", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<Tuple> q = cb.createTupleQuery();
                    Root<OrderEntity> root = q.from(OrderEntity.class);
                    JoinContext ctx = JoinContext.of(root, cb);
                    var itemsB = TableField.join(ROOT, "items", INNER).as("b");
                    var orderOfItemsB = TableField.join(itemsB, "order", INNER);
                    q.multiselect(root.<Long>get("id"), ITEMS.resolve(ctx).<Long>get("id"),
                                    itemsB.resolve(ctx).<Long>get("id"),
                                    ITEM_ORDER.resolve(ctx).<Long>get("id"),
                                    orderOfItemsB.resolve(ctx).<Long>get("id"))
                            .where(cb.equal(root.get("id"), 1L));
                    em.createQuery(q).getResultList();
                });
            }
        });
    }

    @TckTest
    void ac_col_02_on_without_an_alias_throws(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                JoinContext ctx = JoinContext.of(cb.createTupleQuery().from(OrderEntity.class), cb);
                var noAlias = TableField.join(ROOT, "items", LEFT).on((i, b) -> b.gt(i.get("quantity"), 1));
                assertThatThrownBy(() -> noAlias.resolve(ctx))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1102));
            });
        }
    }

    @Test
    void ac_col_02_as_and_on_on_a_root_throw_mq1104_and_with_parent_leaves_a_root_as_is() {
        assertMq1104(() -> ROOT.as("x"), "MQ1104: root OrderEntity: as(...) applies to a join");
        assertMq1104(() -> ROOT.on((o, cb) -> cb.isNotNull(o.get("id"))),
                "MQ1104: root OrderEntity: on(...) applies to a join");
        assertThat(ROOT.withParent(ROOT)).isSameAs(ROOT);
    }

    private static void assertMq1104(Runnable call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1104))
                .hasMessageStartingWith(message);
    }

    @TckTest
    void ac_col_02_same_key_with_different_conditions_throws_mq1101(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                JoinContext ctx = JoinContext.of(cb.createTupleQuery().from(OrderEntity.class), cb);
                var first = TableField.join(ROOT, "items", LEFT).as("x").on((i, b) -> b.gt(i.get("quantity"), 1));
                var second = TableField.join(ROOT, "items", LEFT).as("x").on((i, b) -> b.gt(i.get("quantity"), 2));
                assertThat(first.resolve(ctx)).isNotNull();
                assertThatThrownBy(() -> second.resolve(ctx))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1101));
            });
        }
    }

    @TckTest
    void ac_col_03_on_on_a_left_join_keeps_rows_with_no_matching_child(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "col-03-on-left-join", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<Tuple> q = cb.createTupleQuery();
                    Root<CustomerEntity> root = q.from(CustomerEntity.class);
                    JoinContext ctx = JoinContext.of(root, cb);
                    var customer = TableField.root(CustomerEntity.class);
                    var paid = TableField.join(customer, "orders", LEFT)
                            .as("paid")
                            .on((o, b) -> b.equal(o.get("status"), "PAID"));
                    q.multiselect(root.<Long>get("id"), paid.resolve(ctx).<Long>get("id"));
                    List<Tuple> rows = em.createQuery(q).getResultList();
                    // Every customer's orders share one status, so a quarter of customers have PAID orders (5 each)
                    // and the other 750 survive with a NULL child. A WHERE predicate would have dropped them.
                    assertThat(rows.stream().map(t -> t.get(0, Long.class)).distinct().count())
                            .isEqualTo(TckFixture.CUSTOMERS);
                    assertThat(rows.stream().filter(t -> t.get(1) == null).count()).isEqualTo(750);
                    assertThat(rows).hasSize(2_000);
                });
            }
        });
    }
}
