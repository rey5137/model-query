package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import java.math.BigDecimal;

/** The outer model over {@link WindowSource}: every window is read back as a plain column (R-GEN-38). */
@QueryModel(root = WindowSource.class)
public record WindowFeed(
        @PrimaryKey Long id,
        String status,
        Long rowIndex,
        Long idOrder,
        Long ranking,
        Long denseRanking,
        BigDecimal previousTotal,
        BigDecimal nextTotal,
        BigDecimal firstTotal,
        BigDecimal lastTotal,
        BigDecimal runningTotal,
        BigDecimal rangeTotal,
        Double averageId,
        BigDecimal smallestTotal,
        BigDecimal largestTotal,
        Long remaining) {}
