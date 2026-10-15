package shop;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QOrderRow {
    public static final TableField<OrderTotals, OrderTotals> ROOT = TableField.source(OrderTotals.class);

    public static final OrderedColumnField<OrderRow, OrderTotals, Long> ID = ColumnField.over(OrderRow.class,
            ROOT, QOrderTotals.ID).named("id");

    public static final OrderedColumnField<OrderRow, OrderTotals, String> STATUS = ColumnField.over(OrderRow.class,
            ROOT, QOrderTotals.STATUS).named("status");

    public static final OrderedColumnField<OrderRow, OrderTotals, BigDecimal> TOTAL = ColumnField.over(OrderRow.class,
            ROOT, QOrderTotals.TOTAL).named("total");

    public static final SelectSet<OrderRow> ALL = SelectSet.of(ID, STATUS, TOTAL);

    public static final SelectSet<OrderRow> DEFAULT = ALL;

    public static final PrimaryKey<OrderRow, Long> KEY = PrimaryKey.of(ID);

    public static final RowMapper<OrderRow> MAPPER = QOrderRow::map;

    private QOrderRow() {
    }

    public static ModelQuery.Builder<OrderTotals, Long, OrderRow> query(
            SourceQuery<OrderTotals> source) {
        return ModelQuery.builder(ROOT, MAPPER, source).primaryKey(KEY);
    }

    private static OrderRow map(Row row) {
        return new OrderRow(row.get(ID), row.get(STATUS), row.get(TOTAL));
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<OrderRow> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<OrderRow> INSTANCE = FieldIndex.builder(OrderRow.class)
                .select(ID, STATUS, TOTAL)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
