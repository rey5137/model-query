package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.OrderEntity;

/** {@code coalesce(status, 'none')} over {@code orders}: the computed group key of {@link OrderBandTotals}. */
public final class BandStatus implements ExpressionDefinition<OrderBandTotals, String> {

    public static final BandStatus INSTANCE = new BandStatus();

    private BandStatus() {}

    @Override
    public ExpressionField<OrderBandTotals, String> expression() {
        return Expr.coalesce(ColumnField.of(OrderBandTotals.class, TableField.root(OrderEntity.class), "status",
                String.class), "none");
    }
}
