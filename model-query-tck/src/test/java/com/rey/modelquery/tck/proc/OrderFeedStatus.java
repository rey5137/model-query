package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.JoinSource;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.SourceColumn;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.src.OrderSource;

/** A model that left-joins {@link OrderSource} on the order's id and reads its status (R-PROC-44, AC-SRC-42). */
@QueryModel(root = OrderEntity.class)
@JoinSource(name = "feed", source = OrderSource.class, on = @JoinSource.On(column = "id", key = "id"))
public record OrderFeedStatus(
        @PrimaryKey Long id,
        @SourceColumn(join = "feed", field = "status") String feedStatus) {}
