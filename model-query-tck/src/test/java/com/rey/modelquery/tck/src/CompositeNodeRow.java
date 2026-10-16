package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/**
 * The outer model over the recursive {@link CompositeNode}: one row per node the step reached, carrying the composite
 * key the source cycles on (spec api/17 AC-SRC-21, R-SRC-07, R-SRC-10).
 */
@QueryModel(root = CompositeNode.class)
public record CompositeNodeRow(@PrimaryKey Integer tenantId, @PrimaryKey Long id, Long parentId, String name,
        Integer level) {}
