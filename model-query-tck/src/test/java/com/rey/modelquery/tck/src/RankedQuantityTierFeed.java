package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** Outer query model over {@link RankedQuantityTier} (AC-SRC-27, R-SRC-18). */
@QueryModel(root = RankedQuantityTier.class)
public record RankedQuantityTierFeed(@PrimaryKey Integer quantity, Long lineCount, BigDecimal revenue, Long rank) {}
