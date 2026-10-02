package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;

/** An order's key alone. */
@QueryModel(root = OrderEntity.class)
public record OrderRef(@PrimaryKey Long id) {}
