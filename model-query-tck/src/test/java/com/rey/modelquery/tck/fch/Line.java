package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderItemEntity;

/** An order item: the bottom of a three-level plan. */
@QueryModel(root = OrderItemEntity.class)
public record Line(@PrimaryKey Long id, String productCode, Integer quantity) {}
