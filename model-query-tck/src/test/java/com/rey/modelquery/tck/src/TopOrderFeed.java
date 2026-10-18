package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** Outer query model over the three-level {@link TopOrderSource} chain (AC-SRC-31, R-SRC-18). */
@QueryModel(root = TopOrderSource.class)
public record TopOrderFeed(@PrimaryKey Long id, String status, BigDecimal total, Long rank, Long overallRank) {}
