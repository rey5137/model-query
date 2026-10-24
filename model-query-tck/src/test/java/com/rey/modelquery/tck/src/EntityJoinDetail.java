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
@JoinEntity(name = "manager", entity = CustomerEntity.class, on = @JoinEntity.On(join = "keys", field = "id"))
@JoinEntity(name = "customer", entity = CustomerEntity.class,
        on = @JoinEntity.On(join = "keys", field = "customerId"))
@JoinEntity(name = "copy", entity = OrderEntity.class, on = @JoinEntity.On(join = "keys", field = "id"))
@FilterColumn(name = "MANAGER_VIP", join = "manager", path = "vip")
@FilterColumn(name = "CUSTOMER_VIP", join = "customer", path = "vip")
public record EntityJoinDetail(
        @PrimaryKey Long id,
        @Column(join = "manager", attribute = "name") String managerName,
        @Column(join = "customer", attribute = "name") String customerName,
        @Column(join = "copy", attribute = "customer.name") String viaOrder) {}
