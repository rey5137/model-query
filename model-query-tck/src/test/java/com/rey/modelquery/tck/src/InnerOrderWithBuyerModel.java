package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "buyer", entity = CustomerEntity.class, type = JoinKind.INNER,
        on = @JoinEntity.On(column = "referrerId"))
public record InnerOrderWithBuyerModel(
        @PrimaryKey Long id,
        Long referrerId,
        @Join(entityJoin = "buyer") Optional<BuyerCustomerView> buyer) {}
