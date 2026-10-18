package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** Outer query model over {@link SubtreeSize} (AC-SRC-27, AC-SRC-29, AC-SRC-30, AC-SRC-32). */
@QueryModel(root = SubtreeSize.class)
public record SubtreeSizeRow(@PrimaryKey Integer level, Long nodes) {}
