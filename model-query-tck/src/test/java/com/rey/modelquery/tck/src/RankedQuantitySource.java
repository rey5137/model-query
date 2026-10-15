package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.annotations.WindowFunction;
import com.rey.modelquery.tck.col.OrderItemEntity;
import java.math.BigDecimal;

/**
 * A grouped source that ranks its groups (R-SRC-04): the window's order term names an {@code @Aggregate} field, so it
 * renders that aggregate's own expression, {@code rank() over (order by sum(unit_price) desc)}. {@code RANK} counts
 * peers, so no key is appended to it and its order stays as written. The field is named {@code ranking}, because a CTE
 * column called {@code placing} is PostgreSQL's reserved word and {@code rank} is MySQL's (R-SRC-12).
 */
@SourceModel(root = OrderItemEntity.class)
public record RankedQuantitySource(
        @PrimaryKey @GroupBy Integer quantity,
        @Aggregate(fn = AggregateFunction.COUNT) Long lineCount,
        @Aggregate(fn = AggregateFunction.SUM, attribute = "unitPrice") BigDecimal revenue,
        @Window(function = WindowFunction.RANK, orderBy = "revenue desc") Long ranking) {}
