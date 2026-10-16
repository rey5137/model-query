package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Depth;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.tck.col.CompositeNodeEntity;

/**
 * A recursive source over {@code composite_nodes}, whose node key is two columns ({@code tenantId} and {@code id}): the
 * seed is the row its {@code where} keeps, and every step reads the children of a row and adds one to its level
 * (spec api/17 AC-SRC-21, R-SRC-07; RFC 0014 §7).
 */
@SourceModel(root = CompositeNodeEntity.class)
public record CompositeNode(@PrimaryKey Integer tenantId, @PrimaryKey Long id, Long parentId, String name,
        @Depth Integer level) {}
