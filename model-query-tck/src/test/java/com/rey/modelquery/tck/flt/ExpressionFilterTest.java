package com.rey.modelquery.tck.flt;

import static com.rey.modelquery.core.RenderOptions.portable;
import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.flt.FiltersTest.Case;
import com.rey.modelquery.tck.flt.FiltersTest.N;
import com.rey.modelquery.tck.flt.FiltersTest.O;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/** An expression as a filter operand, in a group, a sub-select and a correlation (api/12 R-FLT-18, R-FLT-03, R-FLT-04). */
class ExpressionFilterTest {

    /** {@code total + 1}: a non-null numeric expression over a root column. */
    private static final ExpressionField<O, BigDecimal> TOTAL_PLUS = Expr.plus(FiltersTest.TOTAL, BigDecimal.ONE);
    /** {@code coalesce(status, 'NONE')}: a non-null text expression over a root column. */
    private static final ExpressionField<O, String> STATUS_TEXT = Expr.coalesce(FiltersTest.STATUS, "NONE");
    /** {@code sortInt + 1}: NULL wherever {@code sortInt} is NULL (AC-FLT-06 over an expression). */
    private static final ExpressionField<N, Integer> INT_PLUS = Expr.plus(FiltersTest.N_INT, 1);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(FiltersTest.ORDERS, "items", INNER);
    /** The order's optional to-one {@code referrer}: two thirds of the orders have none, so an INNER join drops
     * them, while the same path first needed inside {@code or} joins LEFT there (R-FLT-10, R-COL-19). */
    private static final TableField<OrderEntity, CustomerEntity> REFERRER =
            TableField.join(FiltersTest.ORDERS, "referrer", INNER);
    private static final ColumnField<O, CustomerEntity, String> REFERRER_NAME =
            ColumnField.of(O.class, REFERRER, "name", String.class);
    /** {@code coalesce(referrer.name, fallback)}: the fallback is the name of a customer a referrer carries, so a
     * LEFT join outside the {@code or} would keep the orders that have no referrer. */
    private static final ExpressionField<O, String> REFERRER_TEXT = Expr.coalesce(REFERRER_NAME, "Customer 0001");

    @TckTest
    void ac_flt_17_every_operator_over_an_expression_returns_the_rows_the_java_predicate_returns(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                List<O> orders = FiltersTest.run(em, FiltersTest.ORDER_QUERY);
                FiltersTest.check(em, FiltersTest.ORDER_QUERY, O::id, orderCases(orders));
                List<N> nullable = FiltersTest.run(em, FiltersTest.NULLABLE_QUERY);
                FiltersTest.check(em, FiltersTest.NULLABLE_QUERY, N::id, nullableCases(nullable));
            });
        }
    }

    /** One case per operator over {@code total + 1} and {@code coalesce(status, 'NONE')}, values taken from the rows. */
    private static List<Case<O>> orderCases(List<O> all) {
        var sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(O::total));
        BigDecimal lo = sorted.get(0).total().add(BigDecimal.ONE);
        BigDecimal mid = sorted.get(sorted.size() / 2).total().add(BigDecimal.ONE);
        var cases = new ArrayList<Case<O>>();
        cases.add(Case.of("eq over an expression", f -> f.eq(TOTAL_PLUS, mid),
                f -> f.eq(TOTAL_PLUS, Optional.of(mid)), f -> f.eq(TOTAL_PLUS, Optional.<BigDecimal>empty()),
                o -> plusOne(o).compareTo(mid) == 0));
        cases.add(Case.of("ne over an expression", f -> f.ne(TOTAL_PLUS, mid),
                f -> f.ne(TOTAL_PLUS, Optional.of(mid)), f -> f.ne(TOTAL_PLUS, Optional.<BigDecimal>empty()),
                o -> plusOne(o).compareTo(mid) != 0));
        cases.add(Case.of("gt over an expression", f -> f.gt(TOTAL_PLUS, mid),
                f -> f.gt(TOTAL_PLUS, Optional.of(mid)), f -> f.gt(TOTAL_PLUS, Optional.<BigDecimal>empty()),
                o -> plusOne(o).compareTo(mid) > 0));
        cases.add(Case.of("gte over an expression", f -> f.gte(TOTAL_PLUS, mid),
                f -> f.gte(TOTAL_PLUS, Optional.of(mid)), f -> f.gte(TOTAL_PLUS, Optional.<BigDecimal>empty()),
                o -> plusOne(o).compareTo(mid) >= 0));
        cases.add(Case.of("lt over an expression", f -> f.lt(TOTAL_PLUS, mid),
                f -> f.lt(TOTAL_PLUS, Optional.of(mid)), f -> f.lt(TOTAL_PLUS, Optional.<BigDecimal>empty()),
                o -> plusOne(o).compareTo(mid) < 0));
        cases.add(Case.of("lte over an expression", f -> f.lte(TOTAL_PLUS, mid),
                f -> f.lte(TOTAL_PLUS, Optional.of(mid)), f -> f.lte(TOTAL_PLUS, Optional.<BigDecimal>empty()),
                o -> plusOne(o).compareTo(mid) <= 0));
        cases.add(Case.of("range over an expression", f -> f.range(TOTAL_PLUS, Optional.of(lo), Optional.of(mid)),
                f -> f.range(TOTAL_PLUS, Optional.of(lo), Optional.of(mid)),
                f -> f.range(TOTAL_PLUS, Optional.empty(), Optional.empty()),
                o -> plusOne(o).compareTo(lo) >= 0 && plusOne(o).compareTo(mid) < 0));
        cases.add(Case.of("between over an expression", f -> f.between(TOTAL_PLUS, lo, mid),
                f -> f.between(TOTAL_PLUS, Optional.of(lo), Optional.of(mid)),
                f -> f.between(TOTAL_PLUS, Optional.empty(), Optional.empty()),
                o -> plusOne(o).compareTo(lo) >= 0 && plusOne(o).compareTo(mid) <= 0));
        cases.add(Case.of("in over an expression", f -> f.in(TOTAL_PLUS, List.of(lo, mid)),
                f -> f.in(TOTAL_PLUS, Optional.of(List.of(lo, mid))),
                f -> f.in(TOTAL_PLUS, Optional.<List<BigDecimal>>empty()),
                o -> plusOne(o).compareTo(lo) == 0 || plusOne(o).compareTo(mid) == 0));
        cases.add(Case.of("notIn over an expression", f -> f.notIn(TOTAL_PLUS, List.of(lo, mid)),
                f -> f.notIn(TOTAL_PLUS, Optional.of(List.of(lo, mid))),
                f -> f.notIn(TOTAL_PLUS, Optional.<List<BigDecimal>>empty()),
                o -> plusOne(o).compareTo(lo) != 0 && plusOne(o).compareTo(mid) != 0));
        cases.add(Case.of("eq over a text expression", f -> f.eq(STATUS_TEXT, "PAID"),
                f -> f.eq(STATUS_TEXT, Optional.of("PAID")), f -> f.eq(STATUS_TEXT, Optional.<String>empty()),
                o -> o.status().equals("PAID")));
        cases.add(Case.of("ne over a text expression", f -> f.ne(STATUS_TEXT, "PAID"),
                f -> f.ne(STATUS_TEXT, Optional.of("PAID")), f -> f.ne(STATUS_TEXT, Optional.<String>empty()),
                o -> !o.status().equals("PAID")));
        cases.add(Case.of("in over a text expression", f -> f.in(STATUS_TEXT, List.of("PAID", "SHIPPED")),
                f -> f.in(STATUS_TEXT, Optional.of(List.of("PAID", "SHIPPED"))),
                f -> f.in(STATUS_TEXT, Optional.<List<String>>empty()),
                o -> o.status().equals("PAID") || o.status().equals("SHIPPED")));
        cases.add(Case.of("notIn over a text expression", f -> f.notIn(STATUS_TEXT, List.of("PAID", "SHIPPED")),
                f -> f.notIn(STATUS_TEXT, Optional.of(List.of("PAID", "SHIPPED"))),
                f -> f.notIn(STATUS_TEXT, Optional.<List<String>>empty()),
                o -> !o.status().equals("PAID") && !o.status().equals("SHIPPED")));
        cases.add(Case.of("like over an expression", f -> f.like(STATUS_TEXT, "AID", LikeMode.CONTAINS),
                f -> f.like(STATUS_TEXT, Optional.of("AID"), LikeMode.CONTAINS),
                f -> f.like(STATUS_TEXT, Optional.empty(), LikeMode.CONTAINS),
                o -> o.status().contains("AID")));
        cases.add(Case.of("likeIgnoreCase over an expression",
                f -> f.likeIgnoreCase(STATUS_TEXT, "aid", LikeMode.CONTAINS),
                f -> f.likeIgnoreCase(STATUS_TEXT, Optional.of("aid"), LikeMode.CONTAINS),
                f -> f.likeIgnoreCase(STATUS_TEXT, Optional.empty(), LikeMode.CONTAINS),
                o -> o.status().toLowerCase(java.util.Locale.ROOT).contains("aid")));
        cases.add(Case.of("eqIgnoreCase over an expression", f -> f.eqIgnoreCase(STATUS_TEXT, "paid"),
                f -> f.eqIgnoreCase(STATUS_TEXT, Optional.of("paid")),
                f -> f.eqIgnoreCase(STATUS_TEXT, Optional.empty()),
                o -> o.status().equalsIgnoreCase("paid")));
        return cases;
    }

    /** Operators where an expression's NULLs matter: {@code ne}, {@code notIn}, {@code isNull}, {@code compare}. */
    private static List<Case<N>> nullableCases(List<N> all) {
        var cases = new ArrayList<Case<N>>();
        cases.add(Case.of("ne over a nullable expression", f -> f.ne(INT_PLUS, 8), null, null,
                n -> n.sortInt() == null || n.sortInt() + 1 != 8));
        cases.add(Case.of("notIn over a nullable expression", f -> f.notIn(INT_PLUS, List.of(8)), null, null,
                n -> n.sortInt() == null || n.sortInt() + 1 != 8));
        cases.add(Case.of("isNull over a nullable expression", f -> f.isNull(INT_PLUS), null, null,
                n -> n.sortInt() == null));
        cases.add(Case.of("isNotNull over a nullable expression", f -> f.isNotNull(INT_PLUS), null, null,
                n -> n.sortInt() != null));
        cases.add(Case.of("compare an expression against a column",
                f -> f.compare(INT_PLUS, Op.GT, FiltersTest.N_INT), null, null,
                n -> n.sortInt() != null && n.sortInt() + 1 > n.sortInt()));
        return cases;
    }

    private static BigDecimal plusOne(O order) {
        return order.total().add(BigDecimal.ONE);
    }

    @TckTest
    void ac_flt_17_a_skipped_filter_over_an_expression_adds_no_join(TckDatabase db) {
        record Id(Long id) {}
        var root = TableField.root(OrderEntity.class);
        var customer = TableField.join(root, "customer", INNER);
        var id = ColumnField.of(Id.class, root, "id", Long.class);
        var country = ColumnField.of(Id.class, customer, "country", String.class);
        ExpressionField<Id, String> countryText = Expr.coalesce(country, "NONE");
        var query = ModelQuery.builder(root, row -> new Id(row.get(id))).select(SelectSet.of(id)).orderBy(id.asc());
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                var skipped = query.where(f -> f.eq(countryText, Optional.empty())).build();
                var built = skipped.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable());
                assertThat(built.query().getRestriction()).isNull();
                assertThat(built.query().getRoots()).allSatisfy(r -> assertThat(r.getJoins()).isEmpty());
            });
        }
    }

    @TckTest
    void ac_flt_17_an_expression_rendered_inside_or_joins_as_a_plain_column_outside_it(TckDatabase db) {
        // REFERRER_TEXT appears inside an or (whose joins are LEFT) and at top level, where its columns join as plain
        // columns would: INNER, dropping the orders that have no referrer although the coalesce fallback matches. The
        // rows must equal the same filter written over the plain column (R-COL-19, R-FLT-10). Every or renders after
        // the other filters (D-26), and JoinContext keys its expression memo by join mode, so neither order shares
        // the LEFT-joined node with the top-level filter.
        var query = FiltersTest.ORDER_QUERY;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                var expression = query.where(f -> f
                        .or(a -> a.eq(REFERRER_TEXT, "Customer 0001"), b -> b.eq(FiltersTest.ID, 1L))
                        .eq(REFERRER_TEXT, "Customer 0001"));
                var plain = query.where(f -> f
                        .or(a -> a.eq(REFERRER_NAME, "Customer 0001"), b -> b.eq(FiltersTest.ID, 1L))
                        .eq(REFERRER_NAME, "Customer 0001"));
                assertThat(FiltersTest.run(em, expression).stream().map(O::id).toList())
                        .as("the expression drops the rows no referrer matches, exactly as the plain column does")
                        .isNotEmpty()
                        .isEqualTo(FiltersTest.run(em, plain).stream().map(O::id).toList());
            });
        }
    }

    @Test
    void ac_flt_18_an_expression_outside_the_exists_path_throws_mq1302() {
        assertThatThrownBy(() -> FiltersTest.ORDER_QUERY.where(
                f -> f.exists(ORDER_ITEMS, i -> i.eq(TOTAL_PLUS, BigDecimal.ONE))))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1302))
                .hasMessageContaining("outside the exists(...) path");
    }

    @Test
    void ac_flt_18_an_expression_in_a_sub_select_where_over_another_root_throws_mq1003() {
        var customerRoot = TableField.root(CustomerEntity.class);
        var customerId = ColumnField.of(CustomerEntity.class, customerRoot, "id", Long.class);
        var orderTotal = ColumnField.of(CustomerEntity.class, FiltersTest.ORDERS, "total", BigDecimal.class);
        ExpressionField<CustomerEntity, BigDecimal> expression = Expr.plus(orderTotal, BigDecimal.ONE);
        SubSelect<CustomerEntity, Long> sub = SubSelect.of(customerId);
        assertThatThrownBy(() -> sub.where(f -> f.eq(expression, BigDecimal.TEN)))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1003))
                .hasMessageContaining("not on the sub-select's root");
    }

    @Test
    void ac_flt_18_a_correlation_whose_only_lifted_column_sits_in_an_expression_is_not_mq1309() {
        var items = TableField.root(OrderItemEntity.class);
        var itemId = ColumnField.of(OrderItemEntity.class, items, "id", Long.class);
        SubSelect<OrderItemEntity, Long> sub = SubSelect.of(itemId);
        // The lifted outer id is wrapped in an expression: FilterGroup still counts it as a lift (R-FLT-18).
        var built = FiltersTest.ORDER_QUERY.where(f -> f.exists(sub, (s, outer) ->
                s.compare(itemId, Op.EQ, Expr.plus(outer.column(FiltersTest.ID), 0L)))).build();
        assertThat(built).isNotNull();
    }
}
