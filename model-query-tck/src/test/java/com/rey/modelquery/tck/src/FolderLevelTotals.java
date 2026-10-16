package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/**
 * A grouped query over the recursive {@link FolderNode}: one row per level, keyed by the source's own key (R-SRC-03)
 * and grouped by the level the CTE assigns each node. The source's key is not a group key, so only the group key and
 * the aggregate are selectable (MQ1401); the count is what reads it. A count of it is a count over the groups, which
 * reads the source's checks from its group rows (spec api/17 AC-SRC-13, R-SRC-08; RFC 0014 §3).
 */
@QueryModel(root = FolderNode.class)
public record FolderLevelTotals(
        @PrimaryKey Long id,
        @GroupBy Integer level,
        @Aggregate(fn = AggregateFunction.COUNT) Long nodes) {}
