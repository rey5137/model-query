package com.rey.modelquery.sample.plainjpa;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/** A record model whose items a fetch plan loads: they read as empty until the plan names them. */
@QueryModel(root = OrderEntity.class)
public record OrderWithItems(
        @PrimaryKey Long id,
        String status,
        @Child(foreignKey = "order.id") List<ItemView> items) {}
