package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** {@code total * 2} over {@code orders}, typed to the class model {@link OrderNetView} (R-PROC-21). */
public final class NetTotal implements ExpressionDefinition<OrderNetView, BigDecimal> {

    public static final NetTotal INSTANCE = new NetTotal();

    private NetTotal() {}

    @Override
    public ExpressionField<OrderNetView, BigDecimal> expression() {
        return Expr.times(ColumnField.of(OrderNetView.class, TableField.root(OrderEntity.class), "total",
                BigDecimal.class), BigDecimal.valueOf(2));
    }
}
