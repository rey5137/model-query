package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import java.util.List;

/** A customer and its orders: the top of a three-level plan. */
@QueryModel(root = CustomerEntity.class)
public record CustomerOrders(@PrimaryKey Long id, String name,
        @Child(foreignKey = "customer.id") List<OrderLines> orders) {}
