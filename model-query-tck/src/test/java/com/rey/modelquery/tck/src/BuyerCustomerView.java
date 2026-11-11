package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;

@QueryModel(root = CustomerEntity.class)
public record BuyerCustomerView(
        @PrimaryKey Long id,
        String name,
        String email) {}
