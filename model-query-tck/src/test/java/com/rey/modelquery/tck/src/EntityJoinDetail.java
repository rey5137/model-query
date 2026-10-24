package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.JoinSource;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;

/** Generated counterpart of the hand-written entity-join definition (AC-SRC-53, AC-PROC-48). */
@QueryModel(root = OrderEntity.class)
@JoinSource(name = "keys", source = EntityJoinKeys.class, on = @JoinSource.On(column = "id", key = "id"))
@JoinEntity(name = "manager", source = "keys", entity = CustomerEntity.class, on = @JoinEntity.On(field = "id"))
@JoinEntity(name = "customer", source = "keys", entity = CustomerEntity.class,
        on = @JoinEntity.On(field = "customerId"))
@JoinEntity(name = "copy", source = "keys", entity = OrderEntity.class, on = @JoinEntity.On(field = "id"))
@FilterColumn(name = "MANAGER_VIP", entityJoin = "manager", path = "vip")
@FilterColumn(name = "CUSTOMER_VIP", entityJoin = "customer", path = "vip")
public record EntityJoinDetail(
        @PrimaryKey Long id,
        @Column(entityJoin = "manager", attribute = "name") String managerName,
        @Column(entityJoin = "customer", attribute = "name") String customerName,
        @Column(entityJoin = "copy", attribute = "customer.name") String viaOrder) {}
