package com.rey.modelquery.sample.plainjpa;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** A summary model: orders and revenue per status. */
@QueryModel(root = OrderEntity.class)
public record OrderTotals(
        @GroupBy String status,
        @Aggregate(fn = AggregateFunction.COUNT) Long orders,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "total") BigDecimal revenue) {}
