package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/** A plain source: one row per order, keyed by the order's id (R-SRC-01, R-SRC-02). */
@SourceModel(root = OrderEntity.class)
public record OrderSource(@PrimaryKey Long id, String status, BigDecimal total) {}
