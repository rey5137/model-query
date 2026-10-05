package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.Computed;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** A grouped summary whose key is a computed expression and whose one aggregate sums another (R-PROC-16, R-PROC-22). */
@QueryModel(root = OrderEntity.class)
public record OrderBandTotals(
        @GroupBy @Computed(BandStatus.class) String band,
        @Aggregate(fn = AggregateFunction.SUM, expression = BandTotal.class) BigDecimal doubled) {}
