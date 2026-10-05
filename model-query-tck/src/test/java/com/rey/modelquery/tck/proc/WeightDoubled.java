package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** {@code total * 2} over {@code orders}, typed to {@link OrderWeightTotals} (R-PROC-21, D-115). */
public final class WeightDoubled implements ExpressionDefinition<OrderWeightTotals, BigDecimal> {

    public static final WeightDoubled INSTANCE = new WeightDoubled();

    private WeightDoubled() {}

    @Override
    public ExpressionField<OrderWeightTotals, BigDecimal> expression() {
        return Expr.times(ColumnField.of(OrderWeightTotals.class, TableField.root(OrderEntity.class), "total",
                BigDecimal.class), BigDecimal.valueOf(2));
    }
}
