package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "buyer", entity = CustomerEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "referrerId"))
@JoinEntity(name = "slot", entity = NullableSortEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "id"))
@JoinEntity(name = "orderCopy", entity = OrderEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "id"))
public record OrderWithBuyerModel(
        @PrimaryKey Long id,
        Long referrerId,
        @Join(entityJoin = "buyer") Optional<BuyerCustomerView> buyer,
        @Join(entityJoin = "slot") Optional<BuyerSlotView> slot,
        @Join(entityJoin = "orderCopy") Optional<BuyerOrderView> orderCopy) {}
