package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderItemEntity;

/** The child of {@link OrderItemsFeed}: one order line per {@code order_items} row, matched on its order's id. */
@QueryModel(root = OrderItemEntity.class)
public record OrderItemLine(@PrimaryKey Long id, String productCode) {}
