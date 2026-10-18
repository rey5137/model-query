package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.annotations.WindowFunction;
import java.math.BigDecimal;

/** A source over the grouped {@link QuantitySource}, ranking quantity groups by revenue (AC-SRC-27, R-SRC-16). */
@SourceModel(root = QuantitySource.class)
public record RankedQuantityTier(
        @PrimaryKey Integer quantity,
        Long lineCount,
        BigDecimal revenue,
        @Window(function = WindowFunction.ROW_NUMBER, orderBy = "revenue desc") Long rank) {}
