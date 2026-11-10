package com.rey.modelquery.tck.fch;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import java.util.List;

/** A customer and its orders, a to-many child matched on its foreign key, for key groups (R-FCH-18). */
@QueryModel(root = CustomerEntity.class)
public record StampedCustomer(@PrimaryKey Long id, @Child(foreignKey = "customer.id") List<StampedOrder> orders) {}
