package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** The outer model over {@link ReservedSource}. */
@QueryModel(root = ReservedSource.class)
public record ReservedFeed(@PrimaryKey Long id, BigDecimal rank, String year) {}
