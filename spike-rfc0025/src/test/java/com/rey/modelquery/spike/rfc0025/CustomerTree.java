package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.Child;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.util.List;

/** Level 1: a customer, its orders and their lines; also the nested model of {@link OrderWithBuyer}. */
@QueryModel(root = Entities.SpCustomer.class)
public record CustomerTree(@PrimaryKey Long id, String name,
        @Child(foreignKey = "customer.id") List<OrderLines> orders) {}
