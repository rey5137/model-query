package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.annotations.WindowFunction;
import java.math.BigDecimal;

/** Third level in the {@link OrderSource} -> {@link RankedOrderSource} -> {@code TopOrderSource} chain (AC-SRC-31). */
@SourceModel(root = RankedOrderSource.class)
public record TopOrderSource(
        @PrimaryKey Long id,
        String status,
        BigDecimal total,
        Long rank,
        @Window(function = WindowFunction.ROW_NUMBER, orderBy = "total desc") Long overallRank) {}
