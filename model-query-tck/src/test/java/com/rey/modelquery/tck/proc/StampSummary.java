package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.StampedOrderEntity;
import java.util.Date;

/** The latest {@code Timestamp} per status, read as a {@code Date} through the built-in converter. */
@QueryModel(root = StampedOrderEntity.class)
public record StampSummary(
        @GroupBy String status,
        @Aggregate(fn = AggregateFunction.MAX, attribute = "placedAt") Date lastPlaced) {}
