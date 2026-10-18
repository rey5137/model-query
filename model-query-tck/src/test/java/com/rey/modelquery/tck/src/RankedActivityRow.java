package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** Outer query model over {@link RankedActivity} (AC-SRC-28, R-SRC-18). */
@QueryModel(root = RankedActivity.class)
public record RankedActivityRow(@PrimaryKey String branch, @PrimaryKey Long id, String label, Long rank) {}
