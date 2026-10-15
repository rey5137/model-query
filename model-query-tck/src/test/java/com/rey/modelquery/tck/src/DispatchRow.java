package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;

/** An outer model over {@link DispatchFeed}: its key is the source's own, branch then id (R-SRC-10). */
@QueryModel(root = DispatchFeed.class)
public record DispatchRow(@PrimaryKey String branch, @PrimaryKey Long id, String label) {}
