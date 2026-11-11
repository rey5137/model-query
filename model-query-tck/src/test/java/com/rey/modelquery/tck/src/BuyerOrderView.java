package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

@QueryModel(root = OrderEntity.class)
public record BuyerOrderView(
        @PrimaryKey Long id,
        String status,
        @Join Optional<BuyerOrderCustomer> customer) {}
