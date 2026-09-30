package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/**
 * One association joined twice: each {@code @Join} takes its field's name as alias (R-PROC-09). A filter path with
 * one of those aliases is on that {@code @Join}, and one with none is on the first (R-PROC-11, R-PROC-12).
 */
@QueryModel(root = OrderEntity.class)
@FilterColumn(name = "CUSTOMER_VIP", path = "customer.vip")
@FilterColumn(name = "BUYER_VIP", path = "customer.vip", alias = "buyer")
public record OrderBuyers(
        @PrimaryKey Long id,
        @Join Optional<CustomerView> customer,
        @Join(attribute = "customer") Optional<CustomerView> buyer) {}
