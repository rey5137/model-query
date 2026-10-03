package com.rey.modelquery.test;

import static com.rey.modelquery.test.FilterMatchers.and;
import static com.rey.modelquery.test.FilterMatchers.between;
import static com.rey.modelquery.test.FilterMatchers.compare;
import static com.rey.modelquery.test.FilterMatchers.custom;
import static com.rey.modelquery.test.FilterMatchers.eq;
import static com.rey.modelquery.test.FilterMatchers.eqIgnoreCase;
import static com.rey.modelquery.test.FilterMatchers.exists;
import static com.rey.modelquery.test.FilterMatchers.gt;
import static com.rey.modelquery.test.FilterMatchers.gte;
import static com.rey.modelquery.test.FilterMatchers.in;
import static com.rey.modelquery.test.FilterMatchers.isNotNull;
import static com.rey.modelquery.test.FilterMatchers.isNull;
import static com.rey.modelquery.test.FilterMatchers.like;
import static com.rey.modelquery.test.FilterMatchers.likeIgnoreCase;
import static com.rey.modelquery.test.FilterMatchers.lt;
import static com.rey.modelquery.test.FilterMatchers.lte;
import static com.rey.modelquery.test.FilterMatchers.ne;
import static com.rey.modelquery.test.FilterMatchers.not;
import static com.rey.modelquery.test.FilterMatchers.notExists;
import static com.rey.modelquery.test.FilterMatchers.notIn;
import static com.rey.modelquery.test.FilterMatchers.or;
import static com.rey.modelquery.test.FilterMatchers.outer;
import static com.rey.modelquery.test.FilterMatchers.range;
import static com.rey.modelquery.test.QueryAssertions.assertThatQuery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import jakarta.persistence.criteria.JoinType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** The assertions and matchers over a hand-built model, with no persistence provider (api/16, R-INS-06/07). */
class QueryAssertTest {

    static final class Order {}

    static final class Item {}

    static final class OrderView {}

    enum Status { OPEN, CLOSED }

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Order, Item> ITEMS = TableField.join(ROOT, "items", JoinType.INNER);
    private static final TableField<Order, Item> OTHER_PARENT = TableField.join(ROOT, "other", JoinType.INNER);
    private static final TableField<Item, Item> NESTED_ITEMS = TableField.join(OTHER_PARENT, "items", JoinType.INNER);
    private static final OrderedColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    private static final OrderedColumnField<OrderView, Order, String> NAME =
            ColumnField.of(OrderView.class, ROOT, "name", String.class);
    private static final OrderedColumnField<OrderView, Order, Integer> TOTAL =
            ColumnField.of(OrderView.class, ROOT, "total", Integer.class);
    private static final OrderedColumnField<OrderView, Order, Integer> DISCOUNT =
            ColumnField.of(OrderView.class, ROOT, "discount", Integer.class);
    private static final OrderedColumnField<OrderView, Order, Status> STATUS =
            ColumnField.of(OrderView.class, ROOT, "status", Status.class);
    private static final ColumnField<OrderView, Item, Integer> QTY =
            ColumnField.of(OrderView.class, ITEMS, "qty", Integer.class);
    private static final AggregateField<OrderView, Long> COUNT = Agg.count(ROOT);

    private static ModelQuery<Order, Long, OrderView> query(UnaryOperator<Filters<OrderView>> f) {
        return ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(ID, NAME, TOTAL))
                .primaryKey(PrimaryKey.of(ID))
                .where(f)
                .orderBy(NAME.asc(), ID.desc())
                .build();
    }

    /** A service under test: turns a request into a query and hands it to the executor. */
    record Search(Optional<Status> status, Optional<String> name) {}

    static final class OrderService {
        private final ModelQueryExecutor<Order> executor;

        OrderService(ModelQueryExecutor<Order> executor) {
            this.executor = executor;
        }

        List<OrderView> search(Search search) {
            return executor.list(query(f -> f.eq(STATUS, search.status()).eq(NAME, search.name())), Limit.of(10));
        }
    }

    /** A stand-in executor that records the queries it is given, in place of a mocking library. */
    @SuppressWarnings("unchecked")
    private static ModelQueryExecutor<Order> capturing(List<ModelQuery<?, ?, OrderView>> captured) {
        return (ModelQueryExecutor<Order>) Proxy.newProxyInstance(QueryAssertTest.class.getClassLoader(),
                new Class<?>[] {ModelQueryExecutor.class}, (proxy, method, args) -> {
                    captured.add((ModelQuery<?, ?, OrderView>) args[0]);
                    return List.of();
                });
    }

    @Test
    void ac_ins_05_asserts_a_query_captured_from_a_mocked_executor_with_no_provider_on_the_classpath() {
        assertThat(ServiceLoader.load(jakarta.persistence.spi.PersistenceProvider.class)).isEmpty();
        assertThatThrownBy(() -> Class.forName("org.hibernate.Session")).isInstanceOf(ClassNotFoundException.class);

        var captured = new ArrayList<ModelQuery<?, ?, OrderView>>();
        new OrderService(capturing(captured)).search(new Search(Optional.of(Status.OPEN), Optional.empty()));

        assertThat(captured).hasSize(1);
        assertThatQuery(captured.get(0))
                .hasFilters(eq(STATUS, Status.OPEN))
                .containsFilter(eq(STATUS, Status.OPEN))
                .isOrderedBy(NAME.asc(), ID.desc())
                .hasSelection(ID, NAME, TOTAL)
                .selectionContains(NAME)
                .hasHaving()
                .hasNoHaving()
                .hasNoFetchPlan();
        assertThatQuery(query(f -> f)).hasNoFilters();
    }

    @Test
    void ac_ins_05_each_matcher_matches_the_filter_it_is_named_after() {
        var q = query(f -> f
                .eq(NAME, "a").ne(NAME, "b").gt(TOTAL, 1).gte(TOTAL, 2).lt(TOTAL, 3).lte(TOTAL, 4)
                .range(TOTAL, Optional.of(5), Optional.of(6)).between(TOTAL, 7, 8)
                .in(TOTAL, List.of(9, 10)).notIn(TOTAL, List.of(11))
                .like(NAME, "c", LikeMode.CONTAINS).likeIgnoreCase(NAME, "D", LikeMode.STARTS_WITH)
                .eqIgnoreCase(NAME, "E").isNull(NAME).isNotNull(TOTAL)
                .compare(TOTAL, Op.GT, DISCOUNT));

        assertThatQuery(q).hasFilters(
                compare(TOTAL, Op.GT, DISCOUNT), isNotNull(TOTAL), isNull(NAME), eqIgnoreCase(NAME, "E"),
                likeIgnoreCase(NAME, "D", LikeMode.STARTS_WITH), like(NAME, "c", LikeMode.CONTAINS),
                notIn(TOTAL, List.of(11)), in(TOTAL, List.of(10, 9)), between(TOTAL, 7, 8), range(TOTAL, 5, 6),
                lte(TOTAL, 4), lt(TOTAL, 3), gte(TOTAL, 2), gt(TOTAL, 1), ne(NAME, "b"), eq(NAME, "a"));
    }

    @Test
    void ac_ins_05_groups_exists_custom_and_having_match_by_their_operands() {
        var q = ModelQuery.builder(ROOT, row -> new OrderView())
                .select(SelectSet.of(NAME, COUNT))
                .where(f -> f
                        .or(a -> a.eq(STATUS, Status.OPEN).gt(TOTAL, 5), b -> b.isNull(NAME))
                        .not(n -> n.eq(NAME, "x"))
                        .exists(ITEMS, i -> i.gt(QTY, 0))
                        .notExists(ITEMS, i -> i.eq(QTY, 9))
                        .add("region visible to user", (ctx, cb) -> cb.conjunction()))
                .groupBy(NAME)
                .having(h -> h.gt(COUNT, 1L).in(COUNT, List.of(2L, 3L)).isNotNull(COUNT))
                .build();

        assertThatQuery(q)
                .containsFilter(or(and(eq(STATUS, Status.OPEN), gt(TOTAL, 5)), isNull(NAME)))
                .containsFilter(not(eq(NAME, "x")))
                .containsFilter(exists(ITEMS, gt(QTY, 0)))
                .containsFilter(notExists(ITEMS, eq(QTY, 9)))
                .containsFilter(custom("region visible to user"))
                .hasHaving(isNotNull(COUNT), FilterMatchers.in(COUNT, List.of(3L, 2L)), FilterMatchers.gt(COUNT, 1L))
                .containsHaving(FilterMatchers.gt(COUNT, 1L));
    }

    @Test
    void ac_ins_08_sub_select_matchers_match_and_a_failure_prints_the_tree() {
        SubSelect<OrderView, Integer> sub = SubSelect.of(TOTAL).where(f -> f.gt(TOTAL, 0));
        var q = query(f -> f.in(TOTAL, sub).notIn(TOTAL, sub)
                .exists(sub, (s, outer) -> s.compare(TOTAL, Op.EQ, outer.column(TOTAL)))
                .notExists(sub, (s, outer) -> s.isNull(outer.column(NAME))));

        assertThatQuery(q).hasFilters(
                in(TOTAL, sub), notIn(TOTAL, sub),
                exists(sub, compare(TOTAL, Op.EQ, outer(TOTAL))),
                notExists(sub, isNull(outer(NAME))));
        assertThatQuery(q).containsFilter(in(TOTAL, sub));

        // A sub-select built from different filters is not the recorded one, and the failure prints the tree.
        SubSelect<OrderView, Integer> other = SubSelect.of(TOTAL);
        assertThatThrownBy(() -> assertThatQuery(q).containsFilter(in(TOTAL, other)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("IN_SUBSELECT(OrderView.total, sub-select(")
                .hasMessageContaining("EXISTS_SUBSELECT(sub-select(")
                .hasMessageContaining("outer.OrderView.name");
    }

    @Test
    void ac_ins_08_a_lifted_column_does_not_match_the_plain_column_it_lifts() {
        SubSelect<OrderView, Integer> sub = SubSelect.of(TOTAL).where(f -> f.gt(TOTAL, 0));
        // The correlation records ID as a plain column, and lifts NAME so MQ1309 is satisfied.
        var plain = query(f -> f.exists(sub, (s, outer) -> s
                .compare(ID, Op.EQ, ID)
                .compare(NAME, Op.EQ, outer.column(NAME))));
        assertThatThrownBy(() -> assertThatQuery(plain).containsFilter(exists(sub, compare(ID, Op.EQ, outer(ID)))))
                .isInstanceOf(AssertionError.class);

        // The reverse: the correlation records the lift, and a matcher naming the plain column does not match.
        var lifted = query(f -> f.exists(sub, (s, outer) -> s
                .compare(ID, Op.EQ, outer.column(ID))
                .compare(NAME, Op.EQ, outer.column(NAME))));
        assertThatThrownBy(() -> assertThatQuery(lifted).containsFilter(exists(sub, compare(ID, Op.EQ, ID))))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void ac_ins_05_a_matcher_does_not_match_another_kind_column_value_or_operand_order() {
        var q = query(f -> f.eq(NAME, "a").in(TOTAL, List.of(1, 2)).or(a -> a.isNull(NAME), b -> b.isNull(ID)));

        assertThatThrownBy(() -> assertThatQuery(q).containsFilter(ne(NAME, "a"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatQuery(q).containsFilter(eq(NAME, "A"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatQuery(q).containsFilter(in(TOTAL, List.of(1))))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatQuery(q).containsFilter(in(TOTAL, List.of(1, 1))))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatQuery(q).containsFilter(or(isNull(ID), isNull(NAME))))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatQuery(q).isOrderedBy(NAME.desc(), ID.desc()))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatQuery(q).hasSelection(NAME, ID, TOTAL)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatQuery(q).hasFetchSelection(ID)).hasMessageContaining("no");
    }

    @Test
    void ac_ins_05_a_blank_custom_label_is_refused() {
        assertThatThrownBy(() -> custom(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_ins_06_a_failing_has_filters_names_the_missing_and_the_unexpected_conditions() {
        var q = query(f -> f.eq(STATUS, Status.OPEN).or(a -> a.isNull(NAME), b -> b.eq(NAME, "secret")));

        assertThatThrownBy(() -> assertThatQuery(q).hasFilters(eq(STATUS, Status.OPEN), gte(TOTAL, 10)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the query of OrderView")
                .hasMessageContaining("missing (no condition matched):")
                .hasMessageContaining("GTE(OrderView.total, 10)")
                .hasMessageContaining("unexpected (no expectation matched):")
                .hasMessageContaining("OR(IS_NULL(OrderView.name), EQ(OrderView.name, \"secret\"))")
                .hasMessageContaining("actual where conditions:")
                .hasMessageContaining("  EQ(OrderView.status, OPEN)")
                .hasMessageContaining("  OR()")
                .hasMessageContaining("    EQ(OrderView.name, \"secret\")");
    }

    @Test
    void ac_ins_06_a_failing_contains_filter_and_has_no_filters_print_the_whole_tree() {
        var q = query(f -> f.exists(ITEMS, i -> i.gt(QTY, 3)));

        assertThatThrownBy(() -> assertThatQuery(q).containsFilter(exists(ITEMS, gt(QTY, 4))))
                .hasMessageContaining("EXISTS(Order.items (INNER), GT(OrderView.qty, 4))")
                .hasMessageContaining("  EXISTS(Order.items (INNER))")
                .hasMessageContaining("    GT(OrderView.qty, 3)");
        assertThatThrownBy(() -> assertThatQuery(q).hasNoFilters())
                .hasMessageContaining("unexpected (no expectation matched):")
                .hasMessageContaining("EXISTS(Order.items (INNER), GT(OrderView.qty, 3))");
    }

    @Test
    void ac_ins_06_exists_on_a_same_named_join_under_another_parent_does_not_match() {
        var q = query(f -> f.exists(ITEMS, i -> i.gt(QTY, 3)));

        assertThatQuery(q).containsFilter(exists(ITEMS, gt(QTY, 3)));
        assertThatThrownBy(() -> assertThatQuery(q).containsFilter(exists(NESTED_ITEMS, gt(QTY, 3))))
                .isInstanceOf(AssertionError.class);
    }
}
