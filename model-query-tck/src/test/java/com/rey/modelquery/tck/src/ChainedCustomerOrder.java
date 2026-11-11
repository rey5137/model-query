package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.util.Optional;

/** The customer read through a nested model whose entity joins chain (AC-GEN-48). */
@QueryModel(root = OrderEntity.class)
public record ChainedCustomerOrder(@PrimaryKey Long id, @Join Optional<ChainedCustomer> customer) {}
