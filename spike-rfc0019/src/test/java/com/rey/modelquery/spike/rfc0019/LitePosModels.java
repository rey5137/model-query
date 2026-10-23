package com.rey.modelquery.spike.rfc0019;

import com.rey.modelquery.spike.rfc0019.LitePosEntities.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;

public final class LitePosModels {

    private LitePosModels() {}

    /** The "today" read model (about 50 rows, 1 join, 2 filters). */
    public record TodaySaleModel(
            Long id,
            String receiptNumber,
            Instant saleTime,
            String storeName,
            BigDecimal totalAmount,
            SaleStatus status
    ) {}

    /** The single row by-key model. */
    public record SaleSummaryModel(
            Long id,
            String receiptNumber,
            BigDecimal totalAmount
    ) {}

    /** The grouped count model (variances read). */
    public record GroupedCountModel(
            SaleStatus status,
            Long count
    ) {}

    /** The flat 86k projection model (12 columns: Long, String, Instant, BigDecimal, enum converter). */
    public record FlatSaleProjection(
            Long id,
            String receiptNumber,
            Instant saleTime,
            SaleStatus status,
            BigDecimal totalAmount,
            BigDecimal taxAmount,
            BigDecimal discountAmount,
            String note,
            Long storeId,
            Long terminalId,
            Long cashierId,
            Long itemCount
    ) {}
}
