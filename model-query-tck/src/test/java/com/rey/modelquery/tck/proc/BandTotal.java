package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** {@code total * 2} over {@code orders}: the expression {@link OrderBandTotals} sums. */
public final class BandTotal implements ExpressionDefinition<OrderBandTotals, BigDecimal> {

    public static final BandTotal INSTANCE = new BandTotal();

    private BandTotal() {}

    @Override
    public ExpressionField<OrderBandTotals, BigDecimal> expression() {
        return Expr.times(ColumnField.of(OrderBandTotals.class, TableField.root(OrderEntity.class), "total",
                BigDecimal.class), BigDecimal.valueOf(2));
    }
}
