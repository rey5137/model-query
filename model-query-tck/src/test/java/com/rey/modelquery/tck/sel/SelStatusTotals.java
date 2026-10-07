package com.rey.modelquery.tck.sel;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.Selected;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.tck.col.OrderEntity;

/** A grouped model with a {@code @Selected} set (D-120). */
@QueryModel(root = OrderEntity.class)
public record SelStatusTotals(
        @GroupBy String status,
        @Aggregate(fn = AggregateFunction.COUNT) Long orders,
        @Selected SelectSet<SelStatusTotals> selected) {}
