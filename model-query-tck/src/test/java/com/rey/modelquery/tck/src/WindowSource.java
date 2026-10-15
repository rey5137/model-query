package com.rey.modelquery.tck.src;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Window;
import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;

/**
 * A source of window functions, one per function and per frame (R-SRC-04): the orders of each status, ranked and
 * accumulated in the order they were placed. Each field names a CTE column as it is (R-SRC-12), so none may be a
 * vendor's reserved word: {@code rank}, {@code rowNumber} and {@code denseRank} are MySQL's and {@code placing} is
 * PostgreSQL's, which is why these are {@code ranking}, {@code rowIndex} and {@code denseRanking}. {@code idOrder}
 * orders by the source key itself, so its rendered {@code order by} must not repeat that key; every other
 * order-sensitive window is ordered by {@code placedAt} alone, so the key is appended after it.
 *
 * <p>{@code averageId} is an average of the arithmetic progression a status' ids form, so every vendor's average is
 * an integer and the value comparison is exact.
 */
@SourceModel(root = OrderEntity.class)
public record WindowSource(
        @PrimaryKey Long id,
        String status,
        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "status", orderBy = "placedAt") Long rowIndex,
        @Window(function = WindowFunction.ROW_NUMBER, partitionBy = "status", orderBy = "id") Long idOrder,
        @Window(function = WindowFunction.RANK, partitionBy = "status", orderBy = "placedAt") Long ranking,
        @Window(function = WindowFunction.DENSE_RANK, partitionBy = "status", orderBy = "placedAt")
        Long denseRanking,
        @Window(function = WindowFunction.LAG, argument = "total", partitionBy = "status",
                orderBy = "placedAt") BigDecimal previousTotal,
        @Window(function = WindowFunction.LEAD, argument = "total", partitionBy = "status",
                orderBy = "placedAt") BigDecimal nextTotal,
        @Window(function = WindowFunction.FIRST_VALUE, argument = "total", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING) BigDecimal firstTotal,
        @Window(function = WindowFunction.LAST_VALUE, argument = "total", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING) BigDecimal lastTotal,
        @Window(function = WindowFunction.SUM, argument = "total", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal runningTotal,
        @Window(function = WindowFunction.SUM, argument = "total", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.RANGE_UNBOUNDED_PRECEDING_TO_CURRENT_ROW) BigDecimal rangeTotal,
        @Window(function = WindowFunction.AVG, argument = "id", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.ROWS_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING) Double averageId,
        @Window(function = WindowFunction.MIN, argument = "total", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.RANGE_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING) BigDecimal smallestTotal,
        @Window(function = WindowFunction.MAX, argument = "total", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.RANGE_UNBOUNDED_PRECEDING_TO_UNBOUNDED_FOLLOWING) BigDecimal largestTotal,
        @Window(function = WindowFunction.COUNT, argument = "total", partitionBy = "status", orderBy = "placedAt",
                frame = WindowFrame.ROWS_CURRENT_ROW_TO_UNBOUNDED_FOLLOWING) Long remaining) {}
