package com.rey.modelquery.tck.proc;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionDefinition;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.OrderEntity;

/** The group key of {@link OrderWeightTotals}: {@code status} coalesced to a non-null value (R-PROC-16). */
public final class WeightBand implements ExpressionDefinition<OrderWeightTotals, String> {

    public static final WeightBand INSTANCE = new WeightBand();

    private WeightBand() {}

    @Override
    public ExpressionField<OrderWeightTotals, String> expression() {
        return Expr.coalesce(ColumnField.of(OrderWeightTotals.class, TableField.root(OrderEntity.class), "status",
                String.class), "none");
    }
}
