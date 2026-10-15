package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Column;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderStatus;

/**
 * A source over a JPA-converted column: {@code statusCode} carries {@code OrderStatus.TextConverter}, so the CTE's
 * column holds the enum and the outer column must not convert it again (R-SRC-10, R-COL-08).
 */
@SourceModel(root = OrderEntity.class)
public record CodeSource(@PrimaryKey Long id, @Column(attribute = "statusCode") OrderStatus status) {}
