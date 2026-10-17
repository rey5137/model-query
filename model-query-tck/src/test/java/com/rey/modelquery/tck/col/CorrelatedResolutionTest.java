package com.rey.modelquery.tck.col;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** First-resolution refusals use the real metamodel, before executing a statement (R-COL-33). */
class CorrelatedResolutionTest {

    record Model(Object value) {}

    static final class Inner {}

    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ROOT, "items", INNER);
    private static final ColumnField<Model, OrderEntity, Long> ID =
            ColumnField.of(Model.class, ROOT, "id", Long.class);
    private static final ColumnField<Inner, OrderEntity, Long> INNER_ID =
            ColumnField.of(Inner.class, ROOT, "id", Long.class);
    private static final ColumnField<Inner, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(Inner.class, ROOT, "total", BigDecimal.class);
    private static final ColumnField<Inner, OrderItemEntity, BigDecimal> PRICE =
            ColumnField.of(Inner.class, ITEMS, "unitPrice", BigDecimal.class);
    private static final ColumnField<Inner, OrderItemEntity, String> CODE =
            ColumnField.of(Inner.class, ITEMS, "productCode", String.class);
    private static final ExpressionField<Model, Long> COUNT = Correlated.count(Model.class, SubSelect.of(INNER_ID),
            (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID)));

    @TckTest
    void ac_col_42_count_and_sum_refuse_to_many_in_the_column_own_where_and_correlation(TckDatabase db) {
        var values = new ArrayList<ExpressionField<Model, ?>>();
        for (SubSelect<Inner, BigDecimal> rows : List.of(SubSelect.of(PRICE),
                SubSelect.of(TOTAL).where(f -> f.eq(CODE, "P007")))) {
            values.add(Correlated.count(Model.class, rows,
                    (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID))));
            values.add(Correlated.sum(Model.class, rows,
                    (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID))));
        }
        values.add(Correlated.count(Model.class, SubSelect.of(TOTAL),
                (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID)).eq(CODE, "P007")));
        values.add(Correlated.sum(Model.class, SubSelect.of(TOTAL),
                (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID)).eq(CODE, "P007")));
        for (ExpressionField<Model, ?> value : values) {
            assertRefused(db, selecting(wrapped(value)).build(), MqCode.MQ1511, "Filters.exists");
        }
    }

    @TckTest
    void ac_col_42_exists_filters_avoid_fanout_and_min_max_exists_accept_to_many(TckDatabase db) {
        var rows = SubSelect.of(TOTAL).where(f -> f.exists(ITEMS, item -> item.eq(CODE, "P007")));
        var values = List.<ExpressionField<Model, ?>>of(
                Correlated.count(Model.class, rows,
                        (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID))),
                Correlated.sum(Model.class, rows,
                        (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID))),
                Correlated.min(Model.class, SubSelect.of(PRICE),
                        (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID))),
                Correlated.max(Model.class, SubSelect.of(PRICE),
                        (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID))),
                Correlated.exists(Model.class, SubSelect.of(PRICE),
                        (f, outer) -> f.compare(INNER_ID, Op.EQ, outer.column(ID))));
        try (var sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                for (ExpressionField<Model, ?> value : values) {
                    List<Model> result = executor.list(selecting(value).orderBy(ID.asc()).build(), Limit.of(5));
                    assertThat(result).hasSize(5);
                }
            });
        }
    }

    @TckTest
    void ac_col_41_agg_of_refuses_correlated_values_even_after_selection_memoizes_them(TckDatabase db) {
        var aggregate = Agg.<Model, Long>of("nested", Long.class, (ctx, cb) -> cb.sum(COUNT.expression(ctx)));
        var query = selecting(COUNT).select(SelectSet.of(COUNT, aggregate)).groupBy(ID).build();
        assertRefused(db, query, MqCode.MQ1510, "@Computed");
    }

    @TckTest
    void ac_col_41_agg_of_guard_is_restored_before_the_next_scalar_selection(TckDatabase db) {
        var aggregate = Agg.<Model, Long>of("caught", Long.class, (ctx, cb) -> {
            Throwable refused = catchThrowable(() -> COUNT.expression(ctx));
            assertThat(refused).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                    error -> assertThat(error.code()).isEqualTo(MqCode.MQ1510));
            return cb.count(ID.expression(ctx));
        });
        // The aggregate renders first; the subsequent scalar must resolve outside its aggregate scope.
        var query = selecting(COUNT).select(SelectSet.of(aggregate, COUNT)).groupBy(ID).build();
        try (var sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> assertThat(ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())
                    .list(query, Limit.of(1))).hasSize(1));
        }
    }

    private static <C> ExpressionField<Model, C> wrapped(ExpressionField<Model, C> value) {
        return Expr.coalesce(value, value);
    }

    private static ModelQuery.Builder<OrderEntity, Object, Model> selecting(SelectField<Model, ?> value) {
        return ModelQuery.builder(ROOT, row -> new Model(row.get(value))).select(SelectSet.of(value));
    }

    private static void assertRefused(TckDatabase db, ModelQuery<OrderEntity, ?, Model> query, MqCode code,
            String message) {
        var errors = new ArrayList<Throwable>();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (var sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> errors.add(catchThrowable(() -> ModelQueryExecutor
                        .create(em, OrderEntity.class, ModelQueryConfig.defaults()).list(query, Limit.unlimited()))));
            }
        });
        assertThat(sql).as("no statement ran").isEmpty();
        assertThat(errors).hasSize(1).allSatisfy(error -> assertThat(error)
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class, failure ->
                        assertThat(failure.code()).isEqualTo(code)).hasMessageContaining(message));
    }
}
