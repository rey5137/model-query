package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.CustomerEntity;

/** One row per customer: the source a nested {@code JoinBuyer} joins, and a query over it can leave rows out. */
@SourceModel(root = CustomerEntity.class)
public record CustomerOrderCount(
        @PrimaryKey @GroupBy Long id,
        @Aggregate(fn = AggregateFunction.COUNT, attribute = "id") Long rows) {}
