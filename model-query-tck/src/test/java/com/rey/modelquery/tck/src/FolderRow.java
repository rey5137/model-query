package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/**
 * The outer model over the recursive {@link FolderNode}: one row per node the step reached, carrying the level the CTE
 * assigned it (spec api/17 R-SRC-07, R-SRC-10).
 */
@QueryModel(root = FolderNode.class)
public record FolderRow(@PrimaryKey Long id, Long parentId, String name, Integer level) {}
