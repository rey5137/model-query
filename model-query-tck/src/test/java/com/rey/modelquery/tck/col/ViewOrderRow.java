package com.rey.modelquery.tck.col;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** A row of the {@code order_view} root (TCK AC-COL-29). */
@QueryModel(root = ViewOrderEntity.class)
public record ViewOrderRow(@PrimaryKey Long id, Long customerId, String status, BigDecimal total) {}
