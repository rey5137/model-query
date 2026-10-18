package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** Outer query model over {@link RankedFolderNode} (AC-SRC-29, AC-SRC-30, AC-SRC-32). */
@QueryModel(root = RankedFolderNode.class)
public record RankedFolderRow(@PrimaryKey Long id, Long parentId, String name, Integer level, Long rank) {}
