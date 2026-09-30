package com.rey.modelquery.tck.proc;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/** One association joined twice: each {@code @Join} takes its field's name as alias (R-PROC-09). */
@QueryModel(root = OrderEntity.class)
public record OrderBuyers(
        @PrimaryKey Long id,
        @Join Optional<CustomerView> customer,
        @Join(attribute = "customer") Optional<CustomerView> buyer) {}
