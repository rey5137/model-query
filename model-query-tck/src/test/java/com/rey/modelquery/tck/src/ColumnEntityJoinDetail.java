package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.JoinEntity;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.OrderEntity;

/** Generated column-anchored joins over the root, with a {@code when} term (AC-PROC-49, AC-SRC-59). */
@QueryModel(root = OrderEntity.class)
@JoinEntity(name = "manager", entity = CustomerEntity.class, type = JoinKind.LEFT,
        on = @JoinEntity.On(column = "id"),
        when = @JoinEntity.When(column = "STATUS", value = "PAID"))
@JoinEntity(name = "copy", entity = OrderEntity.class, type = JoinKind.INNER,
        on = @JoinEntity.On(column = "id"))
@FilterColumn(name = "STATUS", path = "status")
public record ColumnEntityJoinDetail(
        @PrimaryKey Long id,
        @Column(entityJoin = "manager", attribute = "name") String managerName,
        @Column(entityJoin = "copy", attribute = "customer.name") String viaOrder) {}
