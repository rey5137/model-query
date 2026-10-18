package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.FilterColumn;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.annotations.WindowFunction;
import java.math.BigDecimal;

/** A source over the plain {@link OrderSource}, ranking orders within each status (AC-SRC-26, R-SRC-16). */
@SourceModel(root = OrderSource.class)
@FilterColumn(name = "ORDER_TOTAL", path = "total")
public record RankedOrderSource(
        @PrimaryKey Long id,
        String status,
        BigDecimal total,
        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "status", orderBy = "total desc") Long rank) {}
