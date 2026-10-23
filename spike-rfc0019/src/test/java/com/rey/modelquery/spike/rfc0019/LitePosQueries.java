package com.rey.modelquery.spike.rfc0019;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SortSpec;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Cashier;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Sale;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.SaleStatus;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Store;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Terminal;
import com.rey.modelquery.spike.rfc0019.LitePosModels.FlatSaleProjection;
import com.rey.modelquery.spike.rfc0019.LitePosModels.GroupedCountModel;
import com.rey.modelquery.spike.rfc0019.LitePosModels.SaleSummaryModel;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.time.Instant;

public final class LitePosQueries {

    private LitePosQueries() {}

    // Roots and Joins
    public static final TableField<Sale, Sale> ROOT = TableField.root(Sale.class);
    public static final TableField<Sale, Store> STORE_JOIN = TableField.join(ROOT, "store", JoinType.INNER);
    public static final TableField<Sale, Terminal> TERMINAL_JOIN = TableField.join(ROOT, "terminal", JoinType.INNER);
    public static final TableField<Sale, Cashier> CASHIER_JOIN = TableField.join(ROOT, "cashier", JoinType.INNER);

    // Columns for TodaySaleModel
    public static final OrderedColumnField<TodaySaleModel, Sale, Long> TODAY_ID =
            ColumnField.of(TodaySaleModel.class, ROOT, "id", Long.class);
    public static final OrderedColumnField<TodaySaleModel, Sale, String> TODAY_RECEIPT =
            ColumnField.of(TodaySaleModel.class, ROOT, "receiptNumber", String.class);
    public static final OrderedColumnField<TodaySaleModel, Sale, Instant> TODAY_TIME =
            ColumnField.of(TodaySaleModel.class, ROOT, "saleTime", Instant.class);
    public static final ColumnField<TodaySaleModel, Sale, SaleStatus> TODAY_STATUS =
            ColumnField.of(TodaySaleModel.class, ROOT, "status", SaleStatus.class);
    public static final OrderedColumnField<TodaySaleModel, Sale, BigDecimal> TODAY_TOTAL =
            ColumnField.of(TodaySaleModel.class, ROOT, "totalAmount", BigDecimal.class);
    public static final OrderedColumnField<TodaySaleModel, Store, Long> TODAY_STORE_ID =
            ColumnField.of(TodaySaleModel.class, STORE_JOIN, "id", Long.class);
    public static final OrderedColumnField<TodaySaleModel, Store, String> TODAY_STORE_NAME =
            ColumnField.of(TodaySaleModel.class, STORE_JOIN, "name", String.class);

    // Query 1: "today" read (about 50 rows, 1 join, 2 filters)
    public static final ModelQuery<Sale, Long, TodaySaleModel> TODAY_QUERY =
            ModelQuery.builder(ROOT, row -> new TodaySaleModel(
                    row.get(TODAY_ID),
                    row.get(TODAY_RECEIPT),
                    row.get(TODAY_TIME),
                    row.get(TODAY_STORE_NAME),
                    row.get(TODAY_TOTAL),
                    row.get(TODAY_STATUS)
            ))
            .select(SelectSet.of(TODAY_ID, TODAY_RECEIPT, TODAY_TIME, TODAY_STORE_NAME, TODAY_TOTAL, TODAY_STATUS))
            .primaryKey(PrimaryKey.of(TODAY_ID))
            .where(f -> f
                    .between(TODAY_TIME, LitePosDataset.TODAY_START, LitePosDataset.TODAY_END)
                    .eq(TODAY_STORE_ID, 1L)
            )
            .build();

    // Query 1 with custom orderBy: compiled orderedBy call
    public static final ModelQuery<Sale, ?, TodaySaleModel> ORDERED_BY_QUERY =
            TODAY_QUERY.orderedBy(SortSpec.of(SortSpec.Key.desc("saleTime")));

    // Columns for SaleSummaryModel (by-key one & offset page)
    public static final OrderedColumnField<SaleSummaryModel, Sale, Long> SUM_ID =
            ColumnField.of(SaleSummaryModel.class, ROOT, "id", Long.class);
    public static final OrderedColumnField<SaleSummaryModel, Sale, String> SUM_RECEIPT =
            ColumnField.of(SaleSummaryModel.class, ROOT, "receiptNumber", String.class);
    public static final OrderedColumnField<SaleSummaryModel, Sale, BigDecimal> SUM_TOTAL =
            ColumnField.of(SaleSummaryModel.class, ROOT, "totalAmount", BigDecimal.class);

    // Query 2: by-key one query
    public static final ModelQuery<Sale, Long, SaleSummaryModel> ONE_QUERY =
            ModelQuery.builder(ROOT, row -> new SaleSummaryModel(
                    row.get(SUM_ID),
                    row.get(SUM_RECEIPT),
                    row.get(SUM_TOTAL)
            ))
            .select(SelectSet.of(SUM_ID, SUM_RECEIPT, SUM_TOTAL))
            .primaryKey(PrimaryKey.of(SUM_ID))
            .build();

    // Query 3: offset page with COUNT
    public static final ModelQuery<Sale, ?, SaleSummaryModel> PAGE_QUERY =
            ModelQuery.builder(ROOT, row -> new SaleSummaryModel(
                    row.get(SUM_ID),
                    row.get(SUM_RECEIPT),
                    row.get(SUM_TOTAL)
            ))
            .select(SelectSet.of(SUM_ID, SUM_RECEIPT, SUM_TOTAL))
            .orderBy(SUM_ID.asc())
            .build();

    // Columns for GroupedCountModel
    public static final ColumnField<GroupedCountModel, Sale, SaleStatus> GRP_STATUS =
            ColumnField.of(GroupedCountModel.class, ROOT, "status", SaleStatus.class);
    public static final AggregateField<GroupedCountModel, Long> GRP_COUNT = Agg.count(ROOT);

    // Query 4: 365-day grouped count (the "variances" read)
    public static final ModelQuery<Sale, ?, GroupedCountModel> GROUPED_QUERY =
            ModelQuery.builder(ROOT, row -> new GroupedCountModel(
                    row.get(GRP_STATUS),
                    row.get(GRP_COUNT)
            ))
            .select(SelectSet.of(GRP_STATUS, GRP_COUNT))
            .groupBy(GRP_STATUS)
            .orderBy(GRP_STATUS.asc())
            .build();

    // Columns for FlatSaleProjection (12 columns)
    public static final OrderedColumnField<FlatSaleProjection, Sale, Long> FLAT_ID =
            ColumnField.of(FlatSaleProjection.class, ROOT, "id", Long.class);
    public static final OrderedColumnField<FlatSaleProjection, Sale, String> FLAT_RECEIPT =
            ColumnField.of(FlatSaleProjection.class, ROOT, "receiptNumber", String.class);
    public static final OrderedColumnField<FlatSaleProjection, Sale, Instant> FLAT_TIME =
            ColumnField.of(FlatSaleProjection.class, ROOT, "saleTime", Instant.class);
    public static final ColumnField<FlatSaleProjection, Sale, SaleStatus> FLAT_STATUS =
            ColumnField.of(FlatSaleProjection.class, ROOT, "status", SaleStatus.class);
    public static final OrderedColumnField<FlatSaleProjection, Sale, BigDecimal> FLAT_TOTAL =
            ColumnField.of(FlatSaleProjection.class, ROOT, "totalAmount", BigDecimal.class);
    public static final OrderedColumnField<FlatSaleProjection, Sale, BigDecimal> FLAT_TAX =
            ColumnField.of(FlatSaleProjection.class, ROOT, "taxAmount", BigDecimal.class);
    public static final OrderedColumnField<FlatSaleProjection, Sale, BigDecimal> FLAT_DISCOUNT =
            ColumnField.of(FlatSaleProjection.class, ROOT, "discountAmount", BigDecimal.class);
    public static final OrderedColumnField<FlatSaleProjection, Sale, String> FLAT_NOTE =
            ColumnField.of(FlatSaleProjection.class, ROOT, "note", String.class);
    public static final OrderedColumnField<FlatSaleProjection, Store, Long> FLAT_STORE_ID =
            ColumnField.of(FlatSaleProjection.class, STORE_JOIN, "id", Long.class);
    public static final OrderedColumnField<FlatSaleProjection, Terminal, Long> FLAT_TERMINAL_ID =
            ColumnField.of(FlatSaleProjection.class, TERMINAL_JOIN, "id", Long.class);
    public static final OrderedColumnField<FlatSaleProjection, Cashier, Long> FLAT_CASHIER_ID =
            ColumnField.of(FlatSaleProjection.class, CASHIER_JOIN, "id", Long.class);
    public static final OrderedColumnField<FlatSaleProjection, Sale, Long> FLAT_ITEM_COUNT =
            ColumnField.of(FlatSaleProjection.class, ROOT, "itemCount", Long.class);

    // Query 6 (Part B): Flat 86k projection (12 columns)
    public static final ModelQuery<Sale, ?, FlatSaleProjection> FLAT_QUERY =
            ModelQuery.builder(ROOT, row -> new FlatSaleProjection(
                    row.get(FLAT_ID),
                    row.get(FLAT_RECEIPT),
                    row.get(FLAT_TIME),
                    row.get(FLAT_STATUS),
                    row.get(FLAT_TOTAL),
                    row.get(FLAT_TAX),
                    row.get(FLAT_DISCOUNT),
                    row.get(FLAT_NOTE),
                    row.get(FLAT_STORE_ID),
                    row.get(FLAT_TERMINAL_ID),
                    row.get(FLAT_CASHIER_ID),
                    row.get(FLAT_ITEM_COUNT)
            ))
            .select(SelectSet.of(
                    FLAT_ID, FLAT_RECEIPT, FLAT_TIME, FLAT_STATUS, FLAT_TOTAL, FLAT_TAX, FLAT_DISCOUNT, FLAT_NOTE,
                    FLAT_STORE_ID, FLAT_TERMINAL_ID, FLAT_CASHIER_ID, FLAT_ITEM_COUNT
            ))
            .primaryKey(PrimaryKey.of(FLAT_ID))
            .build();
}
