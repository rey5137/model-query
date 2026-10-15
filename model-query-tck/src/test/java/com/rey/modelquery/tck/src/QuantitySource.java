package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.OrderItemEntity;
import java.math.BigDecimal;

/**
 * A grouped source: the order lines by quantity, which is a primitive and so non-nullable in its mapping (R-SRC-02).
 * Nine groups, so a keyset export over them has several pages. Each field names a CTE column as it is (R-SRC-12), so
 * none is a vendor's reserved word: {@code lines} would be MySQL's.
 */
@SourceModel(root = OrderItemEntity.class)
public record QuantitySource(
        @PrimaryKey @GroupBy Integer quantity,
        @Aggregate(fn = AggregateFunction.COUNT) Long lineCount,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "unitPrice") BigDecimal revenue) {}
