package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;

/** Each {@code Expr} factory's value and Java type against a real provider, and MQ1501 to MQ1507 (api/10 AC-COL-19). */
class ExpressionTest {

    static final class View {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final TableField<NullableSortEntity, NullableSortEntity> NULLABLE =
            TableField.root(NullableSortEntity.class);

    @TckTest
    void ac_col_19_coalesce_and_null_if_over_null_and_non_null_return_the_expected_value(TckDatabase db) {
        var id = ColumnField.of(View.class, NULLABLE, "id", Long.class);
        var sortInt = ColumnField.of(View.class, NULLABLE, "sortInt", Integer.class);
        var coalesced = Expr.coalesce(sortInt, 0);
        var nullIf = Expr.nullIf(sortInt, 1);
        assertThat(coalesced.type()).isEqualTo(Integer.class);
        assertThat(nullIf.type()).isEqualTo(Integer.class);
        inSession(db, em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Tuple> q = cb.createTupleQuery();
            Root<NullableSortEntity> root = q.from(NullableSortEntity.class);
            JoinContext ctx = JoinContext.of(root, cb);
            Expression<Long> idSel = id.expression(ctx);
            Expression<Integer> rawSel = sortInt.expression(ctx);
            Expression<Integer> coalescedSel = coalesced.expression(ctx);
            Expression<Integer> nullIfSel = nullIf.expression(ctx);
            q.multiselect(idSel, rawSel, coalescedSel, nullIfSel)
                    .where(cb.lessThanOrEqualTo(id.path(ctx), 20L)).orderBy(cb.asc(id.path(ctx)));
            List<Tuple> rows = em.createQuery(q).getResultList();
            assertThat(rows).anySatisfy(row -> assertThat(row.get(rawSel)).isNull());
            assertThat(rows).anySatisfy(row -> assertThat(row.get(rawSel)).isNotNull());
            for (Tuple row : rows) {
                Long rid = row.get(idSel);
                Integer raw = row.get(rawSel);
                Integer want = raw == null ? 0 : raw;
                assertThat(row.get(coalescedSel)).as("coalesce, id " + rid).isEqualTo(want);
                assertThat(row.get(nullIfSel)).as("nullIf, id " + rid)
                        .isEqualTo(raw != null && raw == 1 ? null : raw);
            }
        });
    }

    @TckTest
    void ac_col_19_coalesce_and_null_if_over_a_null_text_return_the_expected_value(TckDatabase db) {
        var id = ColumnField.of(View.class, NULLABLE, "id", Long.class);
        var sortText = ColumnField.of(View.class, NULLABLE, "sortText", String.class);
        var coalesced = Expr.coalesce(sortText, "none");
        var nullIf = Expr.nullIf(sortText, "t01");
        assertThat(coalesced.type()).isEqualTo(String.class);
        inSession(db, em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Tuple> q = cb.createTupleQuery();
            Root<NullableSortEntity> root = q.from(NullableSortEntity.class);
            JoinContext ctx = JoinContext.of(root, cb);
            Expression<Long> idSel = id.expression(ctx);
            Expression<String> rawSel = sortText.expression(ctx);
            Expression<String> coalescedSel = coalesced.expression(ctx);
            Expression<String> nullIfSel = nullIf.expression(ctx);
            q.multiselect(idSel, rawSel, coalescedSel, nullIfSel)
                    .where(cb.lessThanOrEqualTo(id.path(ctx), 20L)).orderBy(cb.asc(id.path(ctx)));
            List<Tuple> rows = em.createQuery(q).getResultList();
            assertThat(rows).anySatisfy(row -> assertThat(row.get(rawSel)).isNull());
            for (Tuple row : rows) {
                Long rid = row.get(idSel);
                String raw = row.get(rawSel);
                assertThat(row.get(coalescedSel)).as("coalesce, id " + rid)
                        .isEqualTo(raw == null ? "none" : raw);
                assertThat(row.get(nullIfSel)).as("nullIf, id " + rid)
                        .isEqualTo("t01".equals(raw) ? null : raw);
            }
        });
    }

    @TckTest
    void ac_col_19_times_same_type_and_mixed_and_divided_by_over_decimals(TckDatabase db) {
        var id = ColumnField.of(View.class, ITEMS, "id", Long.class);
        var quantity = ColumnField.of(View.class, ITEMS, "quantity", Integer.class);
        var unitPrice = ColumnField.of(View.class, ITEMS, "unitPrice", BigDecimal.class);
        var sameType = Expr.times(unitPrice, BigDecimal.valueOf(2));
        var mixed = Expr.times(quantity, unitPrice, BigDecimal.class);
        var quotient = Expr.dividedBy(Expr.plus(unitPrice, unitPrice), unitPrice);
        assertThat(sameType.type()).isEqualTo(BigDecimal.class);
        assertThat(mixed.type()).isEqualTo(BigDecimal.class);
        assertThat(quotient.type()).isEqualTo(BigDecimal.class);
        inSession(db, em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Tuple> q = cb.createTupleQuery();
            Root<OrderItemEntity> root = q.from(OrderItemEntity.class);
            JoinContext ctx = JoinContext.of(root, cb);
            Expression<Long> idSel = id.expression(ctx);
            Expression<Integer> quantitySel = quantity.expression(ctx);
            Expression<BigDecimal> priceSel = unitPrice.expression(ctx);
            Expression<BigDecimal> sameSel = sameType.expression(ctx);
            Expression<BigDecimal> mixedSel = mixed.expression(ctx);
            Expression<BigDecimal> quotientSel = quotient.expression(ctx);
            q.multiselect(idSel, quantitySel, priceSel, sameSel, mixedSel, quotientSel)
                    .where(cb.lessThanOrEqualTo(id.path(ctx), 9L)).orderBy(cb.asc(id.path(ctx)));
            List<Tuple> rows = em.createQuery(q).getResultList();
            assertThat(rows).isNotEmpty();
            for (Tuple row : rows) {
                Long rid = row.get(idSel);
                BigDecimal price = row.get(priceSel);
                BigDecimal byTwo = row.get(sameSel);
                BigDecimal timesBoth = row.get(mixedSel);
                BigDecimal div = row.get(quotientSel);
                assertThat(byTwo).as("times, id " + rid)
                        .isEqualByComparingTo(price.multiply(BigDecimal.valueOf(2)));
                assertThat(timesBoth).as("mixed times, id " + rid)
                        .isEqualByComparingTo(BigDecimal.valueOf(row.get(quantitySel)).multiply(price));
                assertThat(div).as("dividedBy, id " + rid).isEqualByComparingTo(BigDecimal.valueOf(2));
            }
        });
    }

    @TckTest
    void ac_col_19_concat_with_a_null_operand_is_null(TckDatabase db) {
        var id = ColumnField.of(View.class, NULLABLE, "id", Long.class);
        var sortText = ColumnField.of(View.class, NULLABLE, "sortText", String.class);
        var concatenated = Expr.concat(sortText, "-x");
        assertThat(concatenated.type()).isEqualTo(String.class);
        inSession(db, em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Tuple> q = cb.createTupleQuery();
            Root<NullableSortEntity> root = q.from(NullableSortEntity.class);
            JoinContext ctx = JoinContext.of(root, cb);
            Expression<Long> idSel = id.expression(ctx);
            Expression<String> rawSel = sortText.expression(ctx);
            Expression<String> concatSel = concatenated.expression(ctx);
            q.multiselect(idSel, rawSel, concatSel)
                    .where(cb.lessThanOrEqualTo(id.path(ctx), 20L)).orderBy(cb.asc(id.path(ctx)));
            List<Tuple> rows = em.createQuery(q).getResultList();
            assertThat(rows).anySatisfy(row -> assertThat(row.get(rawSel)).isNull());
            for (Tuple row : rows) {
                Long rid = row.get(idSel);
                String raw = row.get(rawSel);
                assertThat(row.get(concatSel)).as("concat, id " + rid)
                        .isEqualTo(raw == null ? null : raw + "-x");
            }
        });
    }

    @TckTest
    void ac_col_19_a_case_with_column_and_value_branches_returns_the_expected_value(TckDatabase db) {
        var id = ColumnField.of(View.class, ITEMS, "id", Long.class);
        var quantity = ColumnField.of(View.class, ITEMS, "quantity", Integer.class);
        var unitPrice = ColumnField.of(View.class, ITEMS, "unitPrice", BigDecimal.class);
        var columnBranch = Expr.cases(View.class, BigDecimal.class).when(f -> f.gt(quantity, 4), unitPrice)
                .otherwise(BigDecimal.ZERO);
        var valueBranch = Expr.cases(View.class, BigDecimal.class).when(f -> f.gt(quantity, 4),
                new BigDecimal("1.234")).otherwise(new BigDecimal("5.678"));
        assertThat(columnBranch.type()).isEqualTo(BigDecimal.class);
        assertThat(valueBranch.type()).isEqualTo(BigDecimal.class);
        inSession(db, em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Tuple> q = cb.createTupleQuery();
            Root<OrderItemEntity> root = q.from(OrderItemEntity.class);
            JoinContext ctx = JoinContext.of(root, cb);
            Expression<Long> idSel = id.expression(ctx);
            Expression<Integer> quantitySel = quantity.expression(ctx);
            Expression<BigDecimal> priceSel = unitPrice.expression(ctx);
            Expression<BigDecimal> columnSel = columnBranch.expression(ctx);
            Expression<BigDecimal> valueSel = valueBranch.expression(ctx);
            q.multiselect(idSel, quantitySel, priceSel, columnSel, valueSel)
                    .where(cb.lessThanOrEqualTo(id.path(ctx), 9L)).orderBy(cb.asc(id.path(ctx)));
            List<Tuple> rows = em.createQuery(q).getResultList();
            assertThat(rows).anySatisfy(row -> assertThat(row.get(quantitySel)).isGreaterThan(4));
            assertThat(rows).anySatisfy(row -> assertThat(row.get(quantitySel)).isLessThanOrEqualTo(4));
            for (Tuple row : rows) {
                Long rid = row.get(idSel);
                boolean above = row.get(quantitySel) > 4;
                assertThat(row.get(columnSel)).as("column branch, id " + rid)
                        .isEqualByComparingTo(above ? row.get(priceSel) : BigDecimal.ZERO);
                assertThat(row.get(valueSel)).as("value branch, id " + rid)
                        .isEqualByComparingTo(above ? new BigDecimal("1.234") : new BigDecimal("5.678"));
            }
        });
    }

    @TckTest
    void ac_col_19_cases_over_a_primitive_type_counts_through_agg_without_mq1507(TckDatabase db) {
        var quantity = ColumnField.of(View.class, ITEMS, "quantity", Integer.class);
        // cases(View.class, int.class) boxes the primitive, so the declared type is Integer and matches what the
        // provider resolves for count(...); without the box the first resolution would throw MQ1507 (R-COL-17).
        var manyItems = Expr.cases(View.class, int.class).when(f -> f.gt(quantity, 4), 1).orNull();
        assertThat(manyItems.type()).isEqualTo(Integer.class);
        var count = Agg.count(manyItems);
        inSession(db, em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Long> q = cb.createQuery(Long.class);
            Root<OrderItemEntity> root = q.from(OrderItemEntity.class);
            JoinContext ctx = JoinContext.of(root, cb);
            q.select(count.expression(ctx));
            assertThat(em.createQuery(q).getSingleResult()).isNotNull();
        });
    }

    @TckTest
    void ac_col_19_a_function_takes_an_expr_constant_mode_argument(TckDatabase db) {
        var id = ColumnField.of(View.class, ITEMS, "id", Long.class);
        var unitPrice = ColumnField.of(View.class, ITEMS, "unitPrice", BigDecimal.class);
        var rounded = Expr.function("round", BigDecimal.class, unitPrice, Expr.constant(0));
        assertThat(rounded.type()).isEqualTo(BigDecimal.class);
        inSession(db, em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Tuple> q = cb.createTupleQuery();
            Root<OrderItemEntity> root = q.from(OrderItemEntity.class);
            JoinContext ctx = JoinContext.of(root, cb);
            Expression<Long> idSel = id.expression(ctx);
            Expression<BigDecimal> priceSel = unitPrice.expression(ctx);
            Expression<BigDecimal> roundedSel = rounded.expression(ctx);
            q.multiselect(idSel, priceSel, roundedSel)
                    .where(cb.lessThanOrEqualTo(id.path(ctx), 9L)).orderBy(cb.asc(id.path(ctx)));
            List<Tuple> rows = em.createQuery(q).getResultList();
            assertThat(rows).isNotEmpty();
            for (Tuple row : rows) {
                Long rid = row.get(idSel);
                assertThat(row.get(roundedSel)).as("round(unitPrice, 0), id " + rid)
                        .isEqualByComparingTo(row.get(priceSel).setScale(0, java.math.RoundingMode.HALF_UP));
            }
        });
    }

    @TckTest
    void ac_col_19_mq1507_a_type_the_provider_does_not_resolve_is_refused(TckDatabase db) {
        var quantity = ColumnField.of(View.class, ITEMS, "quantity", Integer.class);
        var one = ColumnField.of(View.class, ITEMS, "quantity", Integer.class);
        // Both operands are Integer, so the provider resolves Integer; the declared Double is wrong.
        var declaredDouble = Expr.plus(quantity, one, Double.class);
        inSession(db, em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            JoinContext ctx = JoinContext.of(cb.createTupleQuery().from(OrderItemEntity.class), cb);
            assertThatThrownBy(() -> declaredDouble.expression(ctx))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1507))
                    .hasMessageContaining("declared Double")
                    .hasMessageContaining("the provider resolves Integer");
        });
    }

    // ---- helpers

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }
}
