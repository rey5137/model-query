package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/** An entity join with a nested entity join below it (processor/31 R-GEN-43, AC-GEN-50). */
@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "referred", entity = CustomerEntity.class, on = @JoinEntity.On(column = "referrerId"))
public record OrderWithNestedEntityJoin(
        @PrimaryKey Long id,
        Long referrerId,
        @Join(attribute = "customer") Optional<BuyerWithLabel> customer,
        @Join(entityJoin = "referred") Optional<BuyerWithLabel> referred) {}
