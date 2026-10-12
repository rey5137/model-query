package com.rey.modelquery.tck.agg;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CompositeKeyItemEntity;
import java.math.BigDecimal;

/**
 * Two group keys, so a runtime {@code groupBy} can pick one and leave the other unselected, and the sums and counts of
 * the items of a tenant or a label (TCK AC-AGG-16).
 */
@QueryModel(root = CompositeKeyItemEntity.class)
public record ItemGroupTotals(
        @GroupBy Integer tenantId,
        @GroupBy String label,
        @Aggregate(fn = AggregateFunction.COUNT) Long items,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "amount") BigDecimal amount) {}
