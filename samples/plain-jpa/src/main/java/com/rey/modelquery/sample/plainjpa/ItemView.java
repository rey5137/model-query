package com.rey.modelquery.sample.plainjpa;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** A record model: one line of an order, the child of {@link OrderWithItems}. */
@QueryModel(root = OrderItemEntity.class)
public record ItemView(@PrimaryKey Long id, String sku, Integer quantity) {}
