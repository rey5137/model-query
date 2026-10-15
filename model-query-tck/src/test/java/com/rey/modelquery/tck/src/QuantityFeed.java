package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/**
 * The outer model over the grouped {@link QuantitySource}: ungrouped, so it reads one row per group and pages them by
 * keyset (api/17 R-SRC-10).
 */
@QueryModel(root = QuantitySource.class)
public record QuantityFeed(@PrimaryKey Integer quantity, Long lineCount, BigDecimal revenue) {}
