package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A summary model: orders grouped by status, with the aggregates a report would read. */
@QueryModel(root = OrderEntity.class)
public record StatusSummary(
        @GroupBy String status,
        @Aggregate(fn = AggregateFunction.COUNT) Long orders,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "total") BigDecimal revenue,
        @Aggregate(fn = AggregateFunction.MIN, attribute = "placedAt") LocalDateTime firstPlaced) {}
