package com.rey.modelquery.tck.col;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;

/**
 * Joins through a non-key {@code referencedColumnName}, a Hibernate {@code @JoinFormula} and an ad-hoc
 * {@code TableField.on(...)} (spec api/10, D-111 item 7).
 */
class NonKeyJoinTest {

    // ---- AC-COL-15: @JoinColumn(referencedColumnName) on a unique non-key column

    /** A line and the joined product's values, as the database holds them. */
    private record SkuExpect(Long id, Integer quantity, Long productId, String sku, String name, BigDecimal price) {}

    private static List<SkuExpect> skuExpected(TckDatabase db, String extraWhere) {
        return rows(db, "select l.id, l.quantity, p.id, p.sku, p.name, p.price from sku_order_lines l "
                        + "join sku_products p on p.sku = l.product_sku " + extraWhere + " order by l.id").stream()
                .map(r -> new SkuExpect(((Number) r[0]).longValue(), ((Number) r[1]).intValue(),
                        ((Number) r[2]).longValue(), (String) r[3], (String) r[4], (BigDecimal) r[5]))
                .toList();
    }

    private static ModelQuery<SkuOrderLineEntity, Long, SkuLineView> skuLines() {
        return QSkuLineView.query()
                .select(SelectSet.of(QSkuLineView.ID, QSkuLineView.QUANTITY, QSkuLineView.PRODUCT_ID,
                        QSkuLineView.PRODUCT_SKU, QSkuLineView.PRODUCT_NAME, QSkuLineView.PRODUCT_PRICE))
                .orderBy(QSkuLineView.ID.asc())
                .build();
    }

    @TckTest
    void ac_col_15_a_join_through_a_non_key_referenced_column_selects_filters_and_sorts(TckDatabase db) {
        // The association joins on sku_products.sku, not its surrogate id: the nested values come from the row whose
        // sku is the line's product_sku (R-COL-01, R-COL-03).
        List<SkuExpect> expected = skuExpected(db, "");
        withExecutor(db, SkuOrderLineEntity.class, executor -> {
            assertSkuRows(executor.list(skuLines(), Limit.unlimited()), expected);

            var filtered = QSkuLineView.query()
                    .select(SelectSet.of(QSkuLineView.ID, QSkuLineView.QUANTITY, QSkuLineView.PRODUCT_ID,
                            QSkuLineView.PRODUCT_SKU, QSkuLineView.PRODUCT_NAME, QSkuLineView.PRODUCT_PRICE))
                    .where(f -> f.eq(QSkuLineView.PRODUCT_NAME, Optional.of("Sku 03")))
                    .orderBy(QSkuLineView.ID.asc())
                    .build();
            assertSkuRows(executor.list(filtered, Limit.unlimited()), skuExpected(db, "where p.name = 'Sku 03'"));

            var sorted = QSkuLineView.query()
                    .select(SelectSet.of(QSkuLineView.ID, QSkuLineView.PRODUCT_ID, QSkuLineView.PRODUCT_PRICE))
                    .orderBy(QSkuLineView.PRODUCT_PRICE.desc(), QSkuLineView.ID.asc())
                    .build();
            assertThat(executor.list(sorted, Limit.unlimited()))
                    .extracting(row -> row.product().orElseThrow().price())
                    .isSortedAccordingTo(Comparator.reverseOrder());
        });
    }

    @TckTest
    void ac_col_15_a_fetch_plan_loads_the_nested_model_through_a_non_key_column(TckDatabase db) {
        // A plan re-roots the nested model's selection under the join (api/15 R-FCH-07).
        FetchPlan<SkuLineView> plan = FetchPlan.of(QSkuLineView.ALL)
                .join(QSkuLineView.PRODUCT_JOIN, FetchPlan.of(QSkuProductView.ALL));
        List<SkuExpect> expected = skuExpected(db, "");
        withExecutor(db, SkuOrderLineEntity.class, executor -> assertSkuRows(
                executor.list(QSkuLineView.query().fetch(plan).orderBy(QSkuLineView.ID.asc()).build(),
                        Limit.unlimited()),
                expected));
    }

    private static void assertSkuRows(List<SkuLineView> actual, List<SkuExpect> expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            SkuExpect e = expected.get(i);
            SkuLineView a = actual.get(i);
            assertThat(a.id()).as("line %s", e.id()).isEqualTo(e.id());
            assertThat(a.quantity()).as("quantity of line %s", e.id()).isEqualTo(e.quantity());
            assertThat(a.product()).as("product of line %s", e.id()).hasValueSatisfying(p -> {
                assertThat(p.id()).isEqualTo(e.productId());
                assertThat(p.sku()).isEqualTo(e.sku());
                assertThat(p.name()).isEqualTo(e.name());
                assertThat(p.price()).isEqualByComparingTo(e.price());
            });
        }
    }

    // ---- AC-COL-16: a Hibernate @JoinFormula

    /** A line and the joined product's values, as the database holds them. */
    private record FormulaExpect(Long id, Integer quantity, String code, String name, BigDecimal price) {}

    private static List<FormulaExpect> formulaExpected(TckDatabase db, String extraWhere) {
        return rows(db, "select l.id, l.quantity, p.code, p.name, p.price from formula_lines l join formula_products p "
                        + "on p.code = upper(l.product_code) " + extraWhere + " order by l.id").stream()
                .map(r -> new FormulaExpect(((Number) r[0]).longValue(), ((Number) r[1]).intValue(),
                        (String) r[2], (String) r[3], (BigDecimal) r[4]))
                .toList();
    }

    private static List<FormulaExpect> rowsOf(List<FormulaLineView> lines) {
        return lines.stream().map(line -> {
            FormulaProductView p = line.product().orElseThrow();
            return new FormulaExpect(line.id(), line.quantity(), p.code(), p.name(), p.price());
        }).toList();
    }

    @TckTest
    void ac_col_16_a_join_formula_selects_filters_and_sorts(TckDatabase db) {
        // The association's key is upper(product_code), which matches the product's String @Id (D-111 item 7).
        withExecutor(db, FormulaLineEntity.class, executor -> {
            List<FormulaLineView> all = executor.list(formulaLines(), Limit.unlimited());
            assertThat(rowsOf(all)).isEqualTo(formulaExpected(db, ""));

            var filtered = QFormulaLineView.query()
                    .select(SelectSet.of(QFormulaLineView.ID, QFormulaLineView.QUANTITY, QFormulaLineView.PRODUCT_CODE,
                            QFormulaLineView.PRODUCT_NAME, QFormulaLineView.PRODUCT_PRICE))
                    .where(f -> f.eq(QFormulaLineView.PRODUCT_NAME, Optional.of("Formula 03")))
                    .orderBy(QFormulaLineView.ID.asc())
                    .build();
            assertThat(rowsOf(executor.list(filtered, Limit.unlimited())))
                    .isEqualTo(formulaExpected(db, "where p.name = 'Formula 03'"));

            var sorted = QFormulaLineView.query()
                    .select(SelectSet.of(QFormulaLineView.ID, QFormulaLineView.PRODUCT_CODE,
                            QFormulaLineView.PRODUCT_PRICE))
                    .orderBy(QFormulaLineView.PRODUCT_PRICE.desc(), QFormulaLineView.ID.asc())
                    .build();
            assertThat(executor.list(sorted, Limit.unlimited()))
                    .extracting(row -> row.product().orElseThrow().price())
                    .isSortedAccordingTo(Comparator.reverseOrder());
        });
    }

    @TckTest
    void ac_col_16_a_fetch_plan_loads_the_nested_model_through_a_join_formula(TckDatabase db) {
        FetchPlan<FormulaLineView> plan = FetchPlan.of(QFormulaLineView.ALL)
                .join(QFormulaLineView.PRODUCT_JOIN, FetchPlan.of(QFormulaProductView.ALL));
        withExecutor(db, FormulaLineEntity.class, executor -> assertThat(rowsOf(executor.list(
                        QFormulaLineView.query().fetch(plan).orderBy(QFormulaLineView.ID.asc()).build(),
                        Limit.unlimited())))
                .isEqualTo(formulaExpected(db, "")));
    }

    private static ModelQuery<FormulaLineEntity, Long, FormulaLineView> formulaLines() {
        return QFormulaLineView.query()
                .select(SelectSet.of(QFormulaLineView.ID, QFormulaLineView.QUANTITY, QFormulaLineView.PRODUCT_CODE,
                        QFormulaLineView.PRODUCT_NAME, QFormulaLineView.PRODUCT_PRICE))
                .orderBy(QFormulaLineView.ID.asc())
                .build();
    }

    // ---- AC-COL-17: an ad-hoc TableField.on(...) join, the replacement for @Join(on = ...)

    private record CheapLine(Long id, String productName) {}

    private static final TableField<SkuOrderLineEntity, SkuOrderLineEntity> LINES =
            TableField.root(SkuOrderLineEntity.class);
    private static final TableField<SkuOrderLineEntity, SkuProductEntity> CHEAP =
            TableField.<SkuOrderLineEntity, SkuProductEntity>join(LINES, "product", LEFT).as("cheap")
                    .on((p, cb) -> cb.le(p.<BigDecimal>get("price"), new BigDecimal("1.50")));
    private static final ColumnField<CheapLine, SkuOrderLineEntity, Long> LINE_ID =
            ColumnField.of(CheapLine.class, LINES, "id", Long.class);
    private static final ColumnField<CheapLine, SkuProductEntity, String> CHEAP_NAME =
            ColumnField.of(CheapLine.class, CHEAP, "name", String.class);

    @TckTest
    void ac_col_17_an_adhoc_on_join_narrows_the_join_and_keeps_the_left_rows(TckDatabase db) {
        // The extra ON condition goes through Join#on, so a line whose product is not cheap stays with a NULL name
        // (R-COL-03, R-COL-04).
        ModelQuery<SkuOrderLineEntity, Long, CheapLine> q = ModelQuery
                .builder(LINES, row -> new CheapLine(row.get(LINE_ID), row.get(CHEAP_NAME)))
                .select(SelectSet.of(LINE_ID, CHEAP_NAME))
                .primaryKey(PrimaryKey.of(LINE_ID))
                .orderBy(LINE_ID.asc())
                .build();
        List<String> expected = rows(db, "select l.id, p.name from sku_order_lines l left join sku_products p "
                        + "on p.sku = l.product_sku and p.price <= 1.50 order by l.id").stream()
                .map(r -> (String) r[1]).toList();
        // Some lines match the extra condition and some keep a NULL, so neither outcome is vacuous.
        assertThat(expected).containsNull().hasAtLeastOneElementOfType(String.class);
        withExecutor(db, SkuOrderLineEntity.class, executor -> assertThat(
                        executor.list(q, Limit.unlimited()))
                .extracting(CheapLine::productName).containsExactlyElementsOf(expected));
    }

    // ---- support

    private static List<Object[]> rows(TckDatabase db, String sql) {
        List<Object[]> out = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> out.addAll(em.createNativeQuery(sql).getResultList()));
        }
        return out;
    }

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
