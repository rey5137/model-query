package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** The outer model over the plain {@link OrderSource}: one row per order (R-SRC-10). */
@QueryModel(root = OrderSource.class)
public record OrderFeed(@PrimaryKey Long id, String status, BigDecimal total) {}
