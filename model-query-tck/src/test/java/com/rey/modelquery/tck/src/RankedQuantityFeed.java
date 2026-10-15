package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** The outer model over {@link RankedQuantitySource}: the group's rank read back as a plain column. */
@QueryModel(root = RankedQuantitySource.class)
public record RankedQuantityFeed(
        @PrimaryKey Integer quantity, Long lineCount, BigDecimal revenue, Long ranking) {}
