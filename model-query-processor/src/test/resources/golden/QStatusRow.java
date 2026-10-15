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
import javax.annotation.processing.Generated;

@Generated("com.rey.modelquery.processor.ModelQueryProcessor")
public final class QStatusRow {
    public static final TableField<StatusTotals, StatusTotals> ROOT = TableField.source(StatusTotals.class);

    public static final OrderedColumnField<StatusRow, StatusTotals, String> STATUS = ColumnField.over(StatusRow.class,
            ROOT, QStatusTotals.STATUS).named("status");

    public static final OrderedColumnField<StatusRow, StatusTotals, Long> ORDERS = ColumnField.over(StatusRow.class,
            ROOT, QStatusTotals.ORDERS).named("orders");

    public static final SelectSet<StatusRow> ALL = SelectSet.of(STATUS, ORDERS);

    public static final SelectSet<StatusRow> DEFAULT = ALL;

    public static final PrimaryKey<StatusRow, String> KEY = PrimaryKey.of(STATUS);

    public static final RowMapper<StatusRow> MAPPER = QStatusRow::map;

    private QStatusRow() {
    }

    public static ModelQuery.Builder<StatusTotals, String, StatusRow> query(
            SourceQuery<StatusTotals> source) {
        return ModelQuery.builder(ROOT, MAPPER, source).primaryKey(KEY);
    }

    private static StatusRow map(Row row) {
        return new StatusRow(row.get(STATUS), row.get(ORDERS));
    }

    /**
     * This model's fields by name, built on the first call ({@code R-GEN-32}); incubating.
     */
    @Incubating
    public static FieldIndex<StatusRow> fields() {
        return Index.INSTANCE;
    }

    private static final class Index {
        static final FieldIndex<StatusRow> INSTANCE = FieldIndex.builder(StatusRow.class)
                .select(STATUS, ORDERS)
                .set("ALL", ALL)
                .set("DEFAULT", DEFAULT)
                .build();
    }
}
