package shop;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import java.math.BigDecimal;
import java.time.LocalDate;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QSalesSummary {
    public static final TableField<SaleEntity, SaleEntity> ROOT = TableField.root(SaleEntity.class);

    public static final OrderedColumnField<SalesSummary, SaleEntity, String> REGION = ColumnField.of(SalesSummary.class,
            ROOT, "region", String.class).named("region");

    public static final OrderedColumnField<SalesSummary, SaleEntity, String> PRODUCT = ColumnField.of(SalesSummary.class,
            ROOT, "product", String.class).named("product");

    public static final AggregateField<SalesSummary, Long> LINES = Agg.count(ROOT);

    public static final AggregateField<SalesSummary, Long> PRODUCTS = Agg.countDistinct(
            ColumnField.of(SalesSummary.class, ROOT, "product", String.class));

    public static final AggregateField<SalesSummary, BigDecimal> REVENUE = Agg.sum(
            ColumnField.of(SalesSummary.class, ROOT, "amount", BigDecimal.class));

    public static final AggregateField<SalesSummary, Long> UNITS = Agg.sumAsLong(
            ColumnField.of(SalesSummary.class, ROOT, "units", Integer.class));

    public static final AggregateField<SalesSummary, Double> AVERAGE_WEIGHT = Agg.avg(
            ColumnField.of(SalesSummary.class, ROOT, "weight", Double.class));

    public static final AggregateField<SalesSummary, LocalDate> FIRST_SALE = Agg.min(
            ColumnField.of(SalesSummary.class, ROOT, "placedOn", LocalDate.class));

    public static final AggregateField<SalesSummary, BigDecimal> BIGGEST_SALE = Agg.max(
            ColumnField.of(SalesSummary.class, ROOT, "amount", BigDecimal.class));

    public static final SelectSet<SalesSummary> ALL = SelectSet.of(REGION, PRODUCT);

    public static final SelectSet<SalesSummary> DEFAULT = ALL;

    public static final SelectSet<SalesSummary> GROUP_KEYS = SelectSet.of(REGION, PRODUCT);

    public static final RowMapper<SalesSummary> MAPPER = QSalesSummary::map;

    private QSalesSummary() {
    }

    public static ModelQuery.Builder<SaleEntity, Object, SalesSummary> query() {
        return ModelQuery.builder(ROOT, MAPPER).groupBy(GROUP_KEYS);
    }

    private static SalesSummary map(Row row) {
        return new SalesSummary(row.get(REGION), row.get(PRODUCT), row.get(LINES),
                row.get(PRODUCTS), row.get(REVENUE), row.get(UNITS), row.get(AVERAGE_WEIGHT),
                row.get(FIRST_SALE), row.get(BIGGEST_SALE));
    }
}
