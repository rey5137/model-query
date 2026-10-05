package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/**
 * A grouped model whose aggregate sums an expression over a generated {@code @FilterColumn} constant and a generated
 * {@code @Computed} constant (R-GEN-27, R-PROC-22, D-115). The aggregate constant must initialise after both, or the
 * definition reads null during {@code <clinit>} and the class fails to load.
 */
@QueryModel(root = OrderEntity.class)
@FilterColumn(name = "ORDER_TOTAL", path = "total")
public record OrderWeightTotals(
        @GroupBy @Computed(WeightBand.class) String band,
        @Computed(WeightDoubled.class) BigDecimal doubled,
        @Aggregate(fn = AggregateFunction.SUM, expression = WeightedTotal.class) BigDecimal weighted) {}
