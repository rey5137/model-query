package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;

/** Grouped source over {@link FolderNode}, counting nodes per depth level (AC-SRC-27, AC-SRC-29, AC-SRC-30). */
@SourceModel(root = FolderNode.class)
public record SubtreeSize(
        @PrimaryKey @GroupBy Integer level,
        @Aggregate(fn = AggregateFunction.COUNT, attribute = "id") Long nodes) {}
