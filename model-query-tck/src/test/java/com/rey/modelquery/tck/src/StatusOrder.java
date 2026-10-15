package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;

/** The child of {@link CodeOrdersFeed}: an order, matched on its converted status column. */
@QueryModel(root = OrderEntity.class)
public record StatusOrder(@PrimaryKey Long id, String status) {}
