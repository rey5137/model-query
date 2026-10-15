package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;
import java.util.List;

/**
 * The outer model over the plain {@link OrderSource} read with a fetch plan, so its parent read is the statement a plan
 * runs on, and carrying a {@code @Child} whose parent-side key names the source field {@code id} (R-SRC-12, R-SRC-10,
 * api/17 R-SRC-10).
 */
@QueryModel(root = OrderSource.class)
public record OrderItemsFeed(@PrimaryKey Long id, String status, BigDecimal total,
        @Child(key = "id", foreignKey = "order.id") List<OrderItemLine> items) {}
