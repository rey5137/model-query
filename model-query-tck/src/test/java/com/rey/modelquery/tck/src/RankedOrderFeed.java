package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** Outer query model over {@link RankedOrderSource} (AC-SRC-26, R-SRC-18). */
@QueryModel(root = RankedOrderSource.class)
public record RankedOrderFeed(@PrimaryKey Long id, String status, BigDecimal total, Long rank) {}
