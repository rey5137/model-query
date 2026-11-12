package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** Level 3: an order line. */
@QueryModel(root = Entities.SpLine.class)
public record LineRow(@PrimaryKey Long id, String product) {}
