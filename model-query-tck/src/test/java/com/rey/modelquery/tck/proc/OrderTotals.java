package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** A whole-table summary: no {@code @GroupBy}, so one row and no primary key. */
@QueryModel(root = OrderEntity.class, singleGroup = true)
public record OrderTotals(
        @Aggregate(fn = AggregateFunction.COUNT) Long orders,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "total") BigDecimal revenue) {}
