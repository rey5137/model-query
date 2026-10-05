package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import java.math.BigDecimal;

/**
 * {@code total + total * 2} over {@code orders}, read from {@link OrderWeightTotals}'s own generated constants: the
 * {@code @FilterColumn} constant {@code ORDER_TOTAL} and the {@code @Computed} constant {@code DOUBLED} (R-GEN-27,
 * R-PROC-22). It builds the expression inside {@code expression()} and caches nothing in a static field.
 */
public final class WeightedTotal implements ExpressionDefinition<OrderWeightTotals, BigDecimal> {

    public static final WeightedTotal INSTANCE = new WeightedTotal();

    private WeightedTotal() {}

    @Override
    public ExpressionField<OrderWeightTotals, BigDecimal> expression() {
        return Expr.plus(QOrderWeightTotals.ORDER_TOTAL, QOrderWeightTotals.DOUBLED);
    }
}
