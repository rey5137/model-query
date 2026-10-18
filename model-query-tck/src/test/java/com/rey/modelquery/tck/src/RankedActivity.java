package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.annotations.WindowFunction;

/** A source over the union shape {@link DispatchFeed}, ranking combined rows per label (AC-SRC-28, RFC 0015 §4). */
@SourceModel(root = DispatchFeed.class)
public record RankedActivity(
        @PrimaryKey String branch,
        @PrimaryKey Long id,
        String label,
        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "label", orderBy = "id desc") Long rank) {}
