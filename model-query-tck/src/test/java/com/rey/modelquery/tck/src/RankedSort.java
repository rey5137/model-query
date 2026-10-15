package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.time.LocalDateTime;

/** The outer model over {@link RankedSortRow}: the top-N read of one source, filtered and paged like any query. */
@QueryModel(root = RankedSortRow.class)
public record RankedSort(@PrimaryKey Long id, Integer sortInt, LocalDateTime sortTs, Long ranking) {}
