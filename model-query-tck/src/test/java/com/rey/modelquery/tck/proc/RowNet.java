package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** {@code total * 2} over {@code orders}, typed to the record model {@link OrderNet} (R-PROC-21). */
public final class RowNet implements ExpressionDefinition<OrderNet, BigDecimal> {

    public static final RowNet INSTANCE = new RowNet();

    private RowNet() {}

    @Override
    public ExpressionField<OrderNet, BigDecimal> expression() {
        return Expr.times(ColumnField.of(OrderNet.class, TableField.root(OrderEntity.class), "total",
                BigDecimal.class), BigDecimal.valueOf(2));
    }
}
