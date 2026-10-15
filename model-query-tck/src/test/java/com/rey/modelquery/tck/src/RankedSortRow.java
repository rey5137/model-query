package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.annotations.WindowFunction;
import com.rey.modelquery.tck.col.NullableSortEntity;
import java.time.LocalDateTime;

/**
 * The top-N source (R-SRC-04, INV-4): each partition of {@code sortInt} ranks its rows by {@code sortTs}, and every
 * row of a partition holds the same timestamp, so the rank of a row is decided by the source key the engine appends,
 * not by the order the database happens to read the rows in. The field is named {@code ranking}, because a CTE column
 * called {@code rank} is MySQL's reserved word and one called {@code placing} is PostgreSQL's (R-SRC-12).
 */
@SourceModel(root = NullableSortEntity.class)
public record RankedSortRow(
        @PrimaryKey Long id,
        Integer sortInt,
        LocalDateTime sortTs,
        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "sortInt", orderBy = "sortTs desc")
        Long ranking) {}
