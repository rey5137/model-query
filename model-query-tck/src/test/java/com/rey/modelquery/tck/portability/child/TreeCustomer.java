package com.rey.modelquery.tck.portability.child;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/** Level 1: a customer, its orders and their lines; also the nested model of {@link OrderWithTree}. */
@QueryModel(root = ChildEntities.TreeCustomerEntity.class)
public record TreeCustomer(@PrimaryKey Long id, String name,
        @Child(foreignKey = "customer.id") List<TreeOrder> orders) {}
