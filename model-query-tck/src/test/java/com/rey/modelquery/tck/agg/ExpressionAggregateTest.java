package com.rey.modelquery.tck.agg;

import static com.rey.modelquery.core.RenderOptions.portable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/** {@code Agg} over an {@code ExpressionField}, and a conditional count and sum through {@code cases} (api/13 R-AGG-13). */
class ExpressionAggregateTest {

    record ItemTotals(Long count, Long countDistinct, Long sumAsLong, Double avg, Integer min, Integer max,
            BigDecimal priceSum, BigDecimal priceMin, BigDecimal priceMax, Long conditionalCount) {}

    /** A single-column projection, for the conditional-sum and {@code having} queries. */
    record Money(BigDecimal value) {}

    /** One product and its total, the row of the {@code having} query. */
    record ProductTotal(String product, BigDecimal total) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemTotals, OrderItemEntity, Integer> QUANTITY =
            ColumnField.of(ItemTotals.class, ITEMS, "quantity", Integer.class);
    private static final ColumnField<ItemTotals, OrderItemEntity, BigDecimal> UNIT_PRICE =
            ColumnField.of(ItemTotals.class, ITEMS, "unitPrice", BigDecimal.class);
    private static final OrderedColumnField<ItemTotals, OrderItemEntity, String> PRODUCT =
            ColumnField.of(ItemTotals.class, ITEMS, "productCode", String.class);

    /** {@code quantity + 1}: an integral expression, whose {@code Agg.sum} is refused with MQ1403. */
    private static final ExpressionField<ItemTotals, Integer> QUANTITY_PLUS = Expr.plus(QUANTITY, 1);
    /** {@code unitPrice * 2}: a decimal expression. */
    private static final ExpressionField<ItemTotals, BigDecimal> PRICE_DOUBLED =
            Expr.times(UNIT_PRICE, BigDecimal.valueOf(2));
    /** The rows whose quantity is above 4, NULL everywhere else: a conditional {@code count}. */
    private static final ExpressionField<ItemTotals, Integer> MANY_ITEMS =
            Expr.cases(ItemTotals.class, Integer.class).when(f -> f.gt(QUANTITY, 4), 1).orNull();
    /** The unit price where quantity is above 4, zero everywhere else: a conditional {@code sum}. */
    private static final ExpressionField<ItemTotals, BigDecimal> MANY_ITEMS_PRICE =
            Expr.cases(ItemTotals.class, BigDecimal.class).when(f -> f.gt(QUANTITY, 4), UNIT_PRICE)
                    .otherwise(BigDecimal.ZERO);

    private static final AggregateField<ItemTotals, Long> COUNT = Agg.count(QUANTITY);
    private static final AggregateField<ItemTotals, Long> COUNT_DISTINCT = Agg.countDistinct(QUANTITY_PLUS);
    private static final AggregateField<ItemTotals, Long> SUM_AS_LONG = Agg.sumAsLong(QUANTITY_PLUS);
    private static final AggregateField<ItemTotals, Double> AVG = Agg.avg(QUANTITY_PLUS);
    private static final AggregateField<ItemTotals, Integer> MIN = Agg.min(QUANTITY_PLUS);
    private static final AggregateField<ItemTotals, Integer> MAX = Agg.max(QUANTITY_PLUS);
    private static final AggregateField<ItemTotals, BigDecimal> PRICE_SUM = Agg.sum(PRICE_DOUBLED);
    private static final AggregateField<ItemTotals, BigDecimal> PRICE_MIN = Agg.min(PRICE_DOUBLED);
    private static final AggregateField<ItemTotals, BigDecimal> PRICE_MAX = Agg.max(PRICE_DOUBLED);
    private static final AggregateField<ItemTotals, Long> CONDITIONAL_COUNT = Agg.count(MANY_ITEMS);
    private static final AggregateField<ItemTotals, BigDecimal> CONDITIONAL_SUM = Agg.sum(MANY_ITEMS_PRICE);

    private static final ModelQuery.Builder<OrderItemEntity, Object, ItemTotals> TOTALS = ModelQuery.builder(ITEMS,
            row -> new ItemTotals(row.get(COUNT), row.get(COUNT_DISTINCT), row.get(SUM_AS_LONG), row.get(AVG),
                    row.get(MIN), row.get(MAX), row.get(PRICE_SUM), row.get(PRICE_MIN), row.get(PRICE_MAX),
                    row.get(CONDITIONAL_COUNT)));

    @Test
    void ac_agg_14_each_aggregate_over_an_expression_declares_the_r_agg_03_type() {
        assertThat(COUNT.type()).isEqualTo(Long.class);
        assertThat(COUNT_DISTINCT.type()).isEqualTo(Long.class);
        assertThat(SUM_AS_LONG.type()).isEqualTo(Long.class);
        assertThat(AVG.type()).isEqualTo(Double.class);
        assertThat(MIN.type()).isEqualTo(Integer.class);
        assertThat(MAX.type()).isEqualTo(Integer.class);
        assertThat(PRICE_SUM.type()).isEqualTo(BigDecimal.class);
        assertThat(PRICE_MIN.type()).isEqualTo(BigDecimal.class);
        assertThat(PRICE_MAX.type()).isEqualTo(BigDecimal.class);
        assertThat(CONDITIONAL_COUNT.type()).isEqualTo(Long.class);
        assertThat(CONDITIONAL_SUM.type()).isEqualTo(BigDecimal.class);
    }

    @Test
    void ac_agg_14_sum_over_an_integer_expression_throws_mq1403_and_sum_as_long_accepts_it() {
        assertThatThrownBy(() -> Agg.sum(QUANTITY_PLUS))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1403))
                .hasMessageContaining("Agg.sum does not take expression type Integer")
                .hasMessageContaining("use Agg.sumAsLong");
        assertThat(Agg.sumAsLong(QUANTITY_PLUS).type()).isEqualTo(Long.class);
    }

    @Test
    void ac_agg_14_two_equal_aggregates_over_equal_expressions_are_one_selection() {
        AggregateField<ItemTotals, BigDecimal> again = Agg.sum(Expr.times(UNIT_PRICE, BigDecimal.valueOf(2)));
        assertThat(again).isNotSameAs(PRICE_SUM).isEqualTo(PRICE_SUM).hasSameHashCodeAs(PRICE_SUM);
        assertThat(SelectSet.of(PRICE_SUM, again).fields()).hasSize(1);
    }

    @TckTest
    void ac_agg_14_aggregates_over_an_expression_return_the_values_the_database_computes(TckDatabase db) {
        var query = TOTALS.select(SelectSet.of(COUNT, COUNT_DISTINCT, SUM_AS_LONG, AVG, MIN, MAX, PRICE_SUM, PRICE_MIN,
                PRICE_MAX, CONDITIONAL_COUNT)).build();
        List<ItemTotals> results = new ArrayList<>();
        inSession(db, em -> results.addAll(run(em, query)));
        assertThat(results).hasSize(1);
        ItemTotals totals = results.get(0);
        // quantity ranges over 1..9, so every aggregate is non-NULL and count(case ...) counts the rows above 4.
        assertThat(totals.count()).isNotNull();
        assertThat(totals.conditionalCount()).isGreaterThan(0L);
        Object[] expected = new Object[10];
        inSession(db, em -> System.arraycopy(em.createQuery("select count(i.quantity), count(distinct i.quantity),"
                + " sum(i.quantity + 1), avg(i.quantity + 1), min(i.quantity + 1), max(i.quantity + 1),"
                + " sum(i.unitPrice * 2), min(i.unitPrice * 2), max(i.unitPrice * 2),"
                + " count(case when i.quantity > 4 then 1 else null end)"
                + " from OrderItemEntity i", Object[].class).getSingleResult(), 0, expected, 0, expected.length));
        assertThat(totals.count()).isEqualTo(expected[0]);
        assertThat(totals.countDistinct()).isEqualTo(expected[1]);
        assertThat(totals.sumAsLong()).isEqualTo(((Number) expected[2]).longValue());
        assertThat(totals.avg()).isEqualTo(((Number) expected[3]).doubleValue());
        assertThat(totals.min()).isEqualTo(((Number) expected[4]).intValue());
        assertThat(totals.max()).isEqualTo(((Number) expected[5]).intValue());
        assertThat(totals.priceSum()).isEqualByComparingTo((BigDecimal) expected[6]);
        assertThat(totals.priceMin()).isEqualByComparingTo((BigDecimal) expected[7]);
        assertThat(totals.priceMax()).isEqualByComparingTo((BigDecimal) expected[8]);
        assertThat(totals.conditionalCount()).isEqualTo(((Number) expected[9]).longValue());
    }

    @TckTest
    void ac_agg_14_a_conditional_sum_through_cases_equals_the_filtered_sum(TckDatabase db) {
        var quantity = ColumnField.of(Money.class, ITEMS, "quantity", Integer.class);
        var unitPrice = ColumnField.of(Money.class, ITEMS, "unitPrice", BigDecimal.class);
        var conditionalSum = Agg.sum(Expr.cases(Money.class, BigDecimal.class)
                .when(f -> f.gt(quantity, 4), unitPrice).otherwise(BigDecimal.ZERO));
        var filteredSum = Agg.sum(unitPrice);
        ModelQuery.Builder<OrderItemEntity, Object, Money> conditional = ModelQuery.builder(ITEMS,
                row -> new Money(row.get(conditionalSum))).select(SelectSet.of(conditionalSum));
        ModelQuery.Builder<OrderItemEntity, Object, Money> filtered = ModelQuery.builder(ITEMS,
                row -> new Money(row.get(filteredSum))).select(SelectSet.of(filteredSum))
                .where(f -> f.gt(quantity, 4));
        List<Money> sums = new ArrayList<>();
        inSession(db, em -> {
            sums.add(run(em, conditional.build()).get(0));
            sums.add(run(em, filtered.build()).get(0));
        });
        assertThat(sums.get(1).value()).isNotNull();
        assertThat(sums.get(0).value()).isEqualByComparingTo(sums.get(1).value());
    }

    @TckTest
    void ac_agg_14_having_on_an_aggregate_over_an_expression_filters_groups(TckDatabase db) {
        List<BigDecimal> totals = new ArrayList<>();
        inSession(db, em -> totals.addAll(em.createQuery("select sum(i.unitPrice * 2) from OrderItemEntity i"
                + " group by i.productCode order by i.productCode", BigDecimal.class).getResultList()));
        totals.sort(Comparator.naturalOrder());
        BigDecimal threshold = totals.get(totals.size() / 2);
        var product = ColumnField.of(ProductTotal.class, ITEMS, "productCode", String.class);
        var doubled = Expr.times(ColumnField.of(ProductTotal.class, ITEMS, "unitPrice", BigDecimal.class),
                BigDecimal.valueOf(2));
        var total = Agg.sum(doubled);
        ModelQuery.Builder<OrderItemEntity, Object, ProductTotal> grouped = ModelQuery.builder(ITEMS,
                row -> new ProductTotal(row.get(product), row.get(total))).select(SelectSet.of(product, total))
                .groupBy(product).having(h -> h.gt(total, threshold)).orderBy(product.asc());
        List<String> products = new ArrayList<>();
        inSession(db, em -> products.addAll(run(em, grouped.build()).stream().map(ProductTotal::product).toList()));
        List<String> expected = new ArrayList<>();
        inSession(db, em -> expected.addAll(em.createQuery("select i.productCode from OrderItemEntity i"
                + " group by i.productCode having sum(i.unitPrice * 2) > :threshold order by i.productCode",
                String.class).setParameter("threshold", threshold).getResultList()));
        assertThat(expected).isNotEmpty().hasSizeLessThan(totals.size());
        assertThat(products).isEqualTo(expected);
    }

    // ---- helpers

    private static <V> List<V> run(EntityManager em, ModelQuery<?, ?, V> query) {
        BuiltQuery<V> built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable());
        return em.createQuery(built.query()).getResultList().stream().map(built::map).toList();
    }

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }
}
